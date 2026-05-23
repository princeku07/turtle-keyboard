package com.prince.turtlekeyboard.integration.puzzle;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;

import com.prince.kbd.core.CommandSpec;
import com.prince.kbd.core.GoogleAuth;
import com.prince.kbd.core.IntegrationContext;
import com.prince.kbd.core.IntegrationSession;
import com.prince.kbd.core.KeyboardIntegration;
import com.prince.kbd.core.SheetViewFactory;
import com.prince.turtlekeyboard.integration.drive.DriveFilesClient;
import com.prince.turtlekeyboard.integration.drive.DriveScopes;
import com.prince.turtlekeyboard.integration.web.WebGameSheetView;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Puzzle integration. End-to-end flow:
 *
 * <ol>
 *   <li>{@code /puzzle} → IME's {@link IntegrationContext#pickImage} pops the gallery.</li>
 *   <li>On pick → {@link GoogleAuth#freshToken} for {@code drive.file} (re-uses
 *       the same scope and consent already granted for /us; no extra dialog).</li>
 *   <li>{@link DriveFilesClient#uploadImage} writes the bytes to the creator's
 *       Drive; {@link DriveFilesClient#makePublicReadable} flips the file to
 *       "anyone with the link can view" so other players' WebViews can fetch
 *       it without their own Drive auth.</li>
 *   <li>{@link DriveFilesClient#publicImageUrl} gives the embeddable URL, and
 *       {@link PuzzleClient#create} writes the {@code games/<id>} doc.</li>
 *   <li>Shareable App Link URL committed into the host editor.</li>
 * </ol>
 *
 * <p>Trade-off vs Firebase Storage: image lives in the creator's personal Drive
 * quota (15 GB free per Google account), not Firebase's centralized bucket.
 * Cleanup story: when puzzle TTL/expiry lands, also delete the Drive file via
 * {@link DriveFilesClient#deleteFile} — keeps the user's Drive tidy.
 *
 * <p>Difficulty is chosen via {@link PuzzleSetupPanel} after the image pick;
 * the user sees a preview of their image and picks 3×3 / 4×4 / 5×5 before the
 * upload fires.
 */
public class PuzzleIntegration implements KeyboardIntegration {

    private static final String TAG = "PuzzleIntegration";

    public static final String ROUTE_KEY = "puzzle";

    private static final long BUSY_BANNER_MS = 30_000L;
    private static final long FAIL_BANNER_MS = 3_000L;
    private static final long EMPTY_BANNER_MS = 1_800L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Live panel for the in-flight /puzzle flow, or null when not in the flow.
     *  Survives across IME input-lifecycle teardowns (e.g. the picker activity
     *  covering the keyboard fires onFinishInputView, which the registry uses
     *  to wipe panelHost). We re-mount this object from {@link #activate} on
     *  the next onInputStart, before the registry's first matching integration
     *  gets a shot — so Puzzle has to be ordered first in the integration list. */
    @Nullable private PuzzleSetupPanel pendingPanel;

    @Override public String id() { return "puzzle"; }

    @Override public void destroy() { io.shutdown(); }

    @Override
    @Nullable
    public IntegrationSession activate(EditorInfo info, IntegrationContext ctx) {
        if (pendingPanel != null) {
            // Re-mount the live panel object — its state (selected difficulty,
            // picked image bytes) is preserved on the Java side even though
            // panelHost was wiped during the picker's focus-steal. Returning a
            // non-null session also "claims" the input session so the registry
            // doesn't hand it off to another integration that would showChip or
            // similar over the puzzle UI.
            ctx.showPanel(pendingPanel);
            return new IntegrationSession() {
                @Override public void onTextChanged(CharSequence before, CharSequence after) {}
                @Override public void onDeactivate() {}
            };
        }
        return null;
    }

    @Override
    public List<CommandSpec> commands() {
        return Collections.singletonList(
                new CommandSpec("puzzle", "Puzzle", "🧩", false, this::handlePuzzle)
        );
    }

    @Override
    public Map<String, SheetViewFactory> sheetRoutes() {
        return Collections.singletonMap(ROUTE_KEY, WebGameSheetView::new);
    }

    private void handlePuzzle(String prompt, IntegrationContext ctx) {
        // Mount the config panel IMMEDIATELY (while the keyboard is still
        // visible, mirrors SplitIntegration). Store the reference in
        // pendingPanel so the registry's activate-cycle re-mounts it after
        // the picker's onFinishInputView → deactivate → hidePanel cycle.
        final PuzzleSetupPanel panel = new PuzzleSetupPanel(ctx.appContext());
        pendingPanel = panel;

        panel.bind(new PuzzleSetupPanel.Callback() {
            @Override public void onPickImage() {
                ctx.pickImage(picked -> {
                    if (picked == null) {
                        ctx.showBanner("Cancelled", EMPTY_BANNER_MS);
                        return;
                    }
                    panel.setImage(picked.bytes, picked.mime);
                    // Defensive re-mount: callback fires before the registry's
                    // post-picker onInputStart, so activate() hasn't run yet.
                    // Showing here gets the panel up immediately; activate()
                    // re-mounts it (no-op-effectively) when onInputStart fires.
                    ctx.showPanel(panel);
                });
            }
            @Override public void onConfirm(byte[] bytes, String mime, int gridSize) {
                pendingPanel = null;
                ctx.hidePanel();
                ctx.showBanner("Uploading to your Drive…", BUSY_BANNER_MS);
                uploadAndCreate(ctx, bytes, mime, gridSize);
            }
            @Override public void onCancel() {
                pendingPanel = null;
                ctx.hidePanel();
                ctx.showBanner("Cancelled", EMPTY_BANNER_MS);
            }
        });
        ctx.showPanel(panel);
    }

    private void uploadAndCreate(IntegrationContext ctx, byte[] bytes, @Nullable String mime,
                                 int gridSize) {
        GoogleAuth auth = ctx.googleAuth();
        // freshToken can fire callback synchronously for cached tokens or async via
        // PendingUi. For the IME (no activity), we can't host the consent UI — if
        // the token isn't cached we bounce the user to the host app to grant Drive
        // through MainActivity / DriveLinkActivity.
        auth.freshToken(null, DriveScopes.SCOPES, new GoogleAuth.Callback() {
            @Override public void onToken(String accessToken) {
                io.execute(() -> performUpload(ctx, accessToken, bytes, mime, gridSize));
            }
            @Override public void onError(String reason, @Nullable GoogleAuth.PendingUi pendingUi) {
                main.post(() -> {
                    if (GoogleAuth.ERROR_NEEDS_UI.equals(reason)) {
                        ctx.showBanner("Open Turtle and link Drive to make puzzles",
                                FAIL_BANNER_MS);
                    } else {
                        Log.w(TAG, "Drive auth failed: " + reason);
                        ctx.showBanner("Drive sign-in failed", FAIL_BANNER_MS);
                    }
                });
            }
        });
    }

    /** Runs on the IO executor. Three blocking Drive calls in sequence. */
    private void performUpload(IntegrationContext ctx, String accessToken,
                               byte[] bytes, @Nullable String mime, int gridSize) {
        String mimeType = (mime == null || mime.isEmpty()) ? "image/jpeg" : mime;
        String name = "turtle-puzzle-" + System.currentTimeMillis() + extensionFor(mimeType);
        try {
            String fileId = DriveFilesClient.uploadImage(accessToken, name, mimeType, bytes);
            DriveFilesClient.makePublicReadable(accessToken, fileId);
            String publicUrl = DriveFilesClient.publicImageUrl(fileId);
            main.post(() -> createPuzzle(ctx, publicUrl, gridSize));
        } catch (IOException e) {
            Log.w(TAG, "Drive upload chain failed", e);
            String message = e.getMessage() == null ? "" : e.getMessage();
            // A 401 means the cached access token is no longer valid for Drive (token
            // revoked, scope dropped, or — common after the project consolidation —
            // the token was minted under an OAuth client that's no longer in use).
            // Clear the cache so the next attempt at freshToken doesn't keep returning
            // the same dead token; the user gets bounced to MainActivity / DriveLink
            // to re-grant.
            if (message.contains("HTTP 401")) {
                ctx.googleAuth().signOut();
                main.post(() -> ctx.showBanner(
                        "Drive link expired — open Turtle and reconnect Drive",
                        FAIL_BANNER_MS));
                return;
            }
            main.post(() -> ctx.showBanner(
                    "Drive upload failed: " + (message.isEmpty() ? "network error" : message),
                    FAIL_BANNER_MS));
        }
    }

    /** Main thread. Firestore write — fast async, no thread hop needed afterwards. */
    private void createPuzzle(IntegrationContext ctx, String imageUrl, int gridSize) {
        PuzzleClient.create(imageUrl, gridSize, new PuzzleClient.CreateCallback() {
            @Override public void onSuccess(PuzzleClient.CreateResult result) {
                ctx.commitText(result.url);
            }
            @Override public void onError(String reason) {
                Log.w(TAG, "PuzzleClient.create failed: " + reason);
                ctx.showBanner(bannerForError(reason), FAIL_BANNER_MS);
            }
        });
    }

    private static String extensionFor(String mime) {
        switch (mime) {
            case "image/jpeg":
            case "image/jpg":  return ".jpg";
            case "image/png":  return ".png";
            case "image/webp": return ".webp";
            case "image/gif":  return ".gif";
            default:           return ".jpg";
        }
    }

    private static String bannerForError(String code) {
        switch (code) {
            case "not_signed_in":
                return "Open Turtle and sign in to make puzzles";
            case "invalid_image":
                return "Couldn't load that image — try another";
            case "invalid_grid_size":
                return "Puzzle grid size out of range";
            case "permission_denied":
                return "Puzzle blocked by rules — try signing out and back in";
            case "network":
                return "Puzzle create failed — check your connection";
            default:
                return "Puzzle failed: " + code;
        }
    }
}
