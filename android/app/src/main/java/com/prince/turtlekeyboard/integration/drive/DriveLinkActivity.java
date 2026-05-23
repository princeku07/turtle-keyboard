package com.prince.turtlekeyboard.integration.drive;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.prince.kbd.core.GoogleAuth;
import com.prince.kbd.core.GoogleAuthImpl;
import com.prince.kbd.core.KeyValueStore;
import com.prince.kbd.core.SharedPrefsKeyValueStore;
import com.prince.turtlekeyboard.ai.ImageDownsizer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-app screen for linking Google Drive to enable {@code /us}. Requests the
 * {@code drive.file} scope, then accepts up to {@link DriveKeys#MAX_REFERENCE_PHOTOS}
 * reference photos; each is cached locally under {@code getFilesDir/drive_photos/}
 * and uploaded to the user's Drive via {@link DriveFilesClient}.
 */
public class DriveLinkActivity extends AppCompatActivity {

    private static final String TAG = "DriveLinkActivity";

    private static final int CREAM = 0xFFF4EFE4;
    private static final int INK   = 0xFF0C0C0C;
    private static final int LIME  = 0xFF15803D;
    private static final int PINK  = 0xFFFF4FA3;
    private static final int BLUE  = 0xFF5B6CFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MUTED = 0xFF6B6B6B;

    private static final String PHOTO_DIR = "drive_photos";

    private GoogleAuth auth;
    private KeyValueStore driveStore;

    private TextView statusLine;
    private TextView profileEmail;
    private TextView profileAvatar;
    private TextView connectBtn;
    private TextView disconnectBtn;

    private LinearLayout photosCardBody;
    private TextView photosHelper;
    private LinearLayout thumbsRow;
    private TextView addPhotosBtn;
    private TextView clearPhotosBtn;
    private TextView uploadStatusLine;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean uploadingNow = new AtomicBoolean(false);

    private final ActivityResultLauncher<IntentSenderRequest> authLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(),
                    result -> finishAuth(result.getData()));

    private final ActivityResultLauncher<String> photosLauncher =
            registerForActivityResult(new ActivityResultContracts.GetMultipleContents(),
                    this::onPhotosPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        KeyValueStore root = new SharedPrefsKeyValueStore(this, SharedPrefsKeyValueStore.DEFAULT_FILE);
        auth = new GoogleAuthImpl(this, root.scoped("google"));
        driveStore = root.scoped("drive");
        setContentView(buildLayout());
        setTitle("Connect Drive");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (auth.isSignedIn()) auth.fetchAndStoreEmailIfMissing();
        render();
        maybeStartUploads();
    }

    @Override
    protected void onDestroy() {
        recycleThumbnails();
        io.shutdownNow();
        super.onDestroy();
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(CREAM);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(this);
        heading.setText("Connect your Drive");
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38);
        heading.setTextColor(INK);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(heading);

        TextView sub = new TextView(this);
        sub.setText("Lets /us generate stylized images using selfies stored in your own Google Drive — never our servers.");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(MUTED);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        sub.setLayoutParams(subLp);
        root.addView(sub);

        root.addView(buildPrivacyCard());
        root.addView(buildStatusCard());
        root.addView(buildPhotosCard());

        return scroll;
    }

    private View buildPrivacyCard() {
        LinearLayout card = brutalCard(WHITE);
        LinearLayout.LayoutParams lp = brutalCardLp();
        lp.topMargin = dp(20);
        card.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText("HOW IT WORKS");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setTextColor(MUTED);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.18f);
        card.addView(label);

        TextView body = new TextView(this);
        body.setText(
                "• Pick 3–5 selfies once.\n"
              + "• Photos live in your Drive — only files this app created.\n"
              + "• Each /us call fetches them with a scoped token, generates the image,\n"
              + "  and discards the photos. We never store your face.\n"
              + "• Disconnect any time — Drive removes our access instantly.");
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setTextColor(INK);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(8);
        body.setLayoutParams(bLp);
        card.addView(body);
        return card;
    }

    private View buildStatusCard() {
        LinearLayout card = brutalCard(WHITE);
        LinearLayout.LayoutParams lp = brutalCardLp();
        lp.topMargin = dp(16);
        card.setLayoutParams(lp);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        profileAvatar = new TextView(this);
        profileAvatar.setBackground(circleDrawable(MUTED));
        profileAvatar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        profileAvatar.setTextColor(WHITE);
        profileAvatar.setTypeface(Typeface.DEFAULT_BOLD);
        profileAvatar.setGravity(Gravity.CENTER);
        profileAvatar.setText("·");
        int sz = dp(44);
        profileAvatar.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        topRow.addView(profileAvatar);

        addHSpacer(topRow, dp(12));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        statusLine = new TextView(this);
        statusLine.setText("STATUS");
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        statusLine.setTextColor(MUTED);
        statusLine.setTypeface(Typeface.DEFAULT_BOLD);
        statusLine.setLetterSpacing(0.15f);
        col.addView(statusLine);

        profileEmail = new TextView(this);
        profileEmail.setText("");
        profileEmail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        profileEmail.setTextColor(INK);
        profileEmail.setTypeface(Typeface.DEFAULT_BOLD);
        profileEmail.setSingleLine(true);
        profileEmail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(profileEmail);

        topRow.addView(col);
        card.addView(topRow);

        connectBtn = new TextView(this);
        connectBtn.setText("Connect with Google");
        connectBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        connectBtn.setTextColor(WHITE);
        connectBtn.setTypeface(Typeface.DEFAULT_BOLD);
        connectBtn.setBackground(pillDrawable(LIME));
        connectBtn.setPadding(dp(14), dp(12), dp(14), dp(12));
        connectBtn.setGravity(Gravity.CENTER);
        connectBtn.setOnClickListener(v -> startAuth());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(14);
        connectBtn.setLayoutParams(cLp);
        card.addView(connectBtn);

        disconnectBtn = new TextView(this);
        disconnectBtn.setText("Disconnect");
        disconnectBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        disconnectBtn.setTextColor(INK);
        disconnectBtn.setTypeface(Typeface.DEFAULT_BOLD);
        disconnectBtn.setPaintFlags(disconnectBtn.getPaintFlags()
                | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        disconnectBtn.setOnClickListener(v -> confirmDisconnect());
        disconnectBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        dLp.topMargin = dp(8);
        dLp.gravity = Gravity.CENTER_HORIZONTAL;
        disconnectBtn.setLayoutParams(dLp);
        card.addView(disconnectBtn);

        return card;
    }

    private View buildPhotosCard() {
        LinearLayout card = brutalCard(0xFFFEF3C7); // soft yellow
        LinearLayout.LayoutParams lp = brutalCardLp();
        lp.topMargin = dp(16);
        card.setLayoutParams(lp);
        photosCardBody = card;

        TextView label = new TextView(this);
        label.setText("REFERENCE PHOTOS");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setTextColor(MUTED);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.18f);
        card.addView(label);

        photosHelper = new TextView(this);
        photosHelper.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        photosHelper.setTextColor(INK);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(8);
        photosHelper.setLayoutParams(hLp);
        card.addView(photosHelper);

        thumbsRow = new LinearLayout(this);
        thumbsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(12);
        thumbsRow.setLayoutParams(tLp);
        card.addView(thumbsRow);

        addPhotosBtn = new TextView(this);
        addPhotosBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        addPhotosBtn.setTextColor(WHITE);
        addPhotosBtn.setTypeface(Typeface.DEFAULT_BOLD);
        addPhotosBtn.setBackground(pillDrawable(PINK));
        addPhotosBtn.setPadding(dp(14), dp(12), dp(14), dp(12));
        addPhotosBtn.setGravity(Gravity.CENTER);
        addPhotosBtn.setOnClickListener(v -> launchPicker());
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        aLp.topMargin = dp(12);
        addPhotosBtn.setLayoutParams(aLp);
        card.addView(addPhotosBtn);

        clearPhotosBtn = new TextView(this);
        clearPhotosBtn.setText("Clear all photos");
        clearPhotosBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        clearPhotosBtn.setTextColor(INK);
        clearPhotosBtn.setTypeface(Typeface.DEFAULT_BOLD);
        clearPhotosBtn.setPaintFlags(clearPhotosBtn.getPaintFlags()
                | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        clearPhotosBtn.setOnClickListener(v -> confirmClearPhotos());
        clearPhotosBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        clLp.topMargin = dp(6);
        clLp.gravity = Gravity.CENTER_HORIZONTAL;
        clearPhotosBtn.setLayoutParams(clLp);
        card.addView(clearPhotosBtn);

        uploadStatusLine = new TextView(this);
        uploadStatusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        uploadStatusLine.setTextColor(MUTED);
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        uLp.topMargin = dp(10);
        uploadStatusLine.setLayoutParams(uLp);
        card.addView(uploadStatusLine);

        return card;
    }

    private void render() {
        boolean linked = auth.isSignedIn();
        if (linked) {
            statusLine.setText("LINKED AS");
            String email = auth.accountEmail();
            profileEmail.setText(email != null ? email : "your Google account");
            profileEmail.setTextColor(INK);
            profileAvatar.setText(initial(email));
            profileAvatar.setBackground(circleDrawable(avatarColor(email)));
            connectBtn.setVisibility(View.GONE);
            disconnectBtn.setVisibility(View.VISIBLE);
            photosCardBody.setVisibility(View.VISIBLE);
        } else {
            statusLine.setText("NOT CONNECTED");
            profileEmail.setText("Pick the Google account you want /us to use.");
            profileEmail.setTextColor(MUTED);
            profileAvatar.setText("·");
            profileAvatar.setBackground(circleDrawable(MUTED));
            connectBtn.setVisibility(View.VISIBLE);
            connectBtn.setEnabled(true);
            disconnectBtn.setVisibility(View.GONE);
            photosCardBody.setVisibility(View.GONE);
        }
        renderPhotos();
    }

    // -- auth flow -----------------------------------------------------------

    private void startAuth() {
        connectBtn.setEnabled(false);
        auth.authorize(this, DriveScopes.SCOPES, new GoogleAuth.Callback() {
            @Override public void onToken(String accessToken) {
                runOnUiThread(DriveLinkActivity.this::onLinked);
            }
            @Override public void onError(String reason, GoogleAuth.PendingUi pendingUi) {
                if (pendingUi != null) {
                    runOnUiThread(() -> launchAuthUi(pendingUi.intentSender));
                } else {
                    runOnUiThread(() -> {
                        connectBtn.setEnabled(true);
                        Toast.makeText(DriveLinkActivity.this,
                                "Sign-in failed: " + reason, Toast.LENGTH_LONG).show();
                    });
                }
            }
        });
    }

    private void launchAuthUi(IntentSender sender) {
        try {
            authLauncher.launch(new IntentSenderRequest.Builder(sender).build());
        } catch (Exception e) {
            connectBtn.setEnabled(true);
            Toast.makeText(this, "Could not open sign-in: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void finishAuth(Intent data) {
        auth.onAuthorizationResult(this, data, new GoogleAuth.Callback() {
            @Override public void onToken(String accessToken) {
                runOnUiThread(DriveLinkActivity.this::onLinked);
            }
            @Override public void onError(String reason, GoogleAuth.PendingUi pendingUi) {
                runOnUiThread(() -> {
                    connectBtn.setEnabled(true);
                    Toast.makeText(DriveLinkActivity.this,
                            "Sign-in failed: " + reason, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void onLinked() {
        Toast.makeText(this, "Drive linked", Toast.LENGTH_SHORT).show();
        render();
        maybeStartUploads();
    }

    private void confirmDisconnect() {
        new AlertDialog.Builder(this)
                .setTitle("Disconnect Drive?")
                .setMessage("/us will stop working until you reconnect. Other Google-backed features in the app will also need to re-authorize. Local reference photos are kept; Drive copies stay until you remove them from your Drive.")
                .setPositiveButton("Disconnect", (d, w) -> {
                    auth.signOut();
                    Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // -- photo picker --------------------------------------------------------

    private void launchPicker() {
        List<PhotoEntry> existing = readEntries();
        if (existing.size() >= DriveKeys.MAX_REFERENCE_PHOTOS) {
            Toast.makeText(this,
                    "You already have " + DriveKeys.MAX_REFERENCE_PHOTOS + " photos. Clear some first.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            photosLauncher.launch("image/*");
        } catch (Exception e) {
            Toast.makeText(this, "No image picker available.", Toast.LENGTH_LONG).show();
        }
    }

    private void onPhotosPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        List<PhotoEntry> existing = readEntries();
        int slotsLeft = DriveKeys.MAX_REFERENCE_PHOTOS - existing.size();
        if (slotsLeft <= 0) return;

        final List<Uri> accepted = uris.size() > slotsLeft ? uris.subList(0, slotsLeft) : uris;
        if (uris.size() > slotsLeft) {
            Toast.makeText(this,
                    "Saved the first " + slotsLeft + " photo(s); " + DriveKeys.MAX_REFERENCE_PHOTOS + " is the cap.",
                    Toast.LENGTH_LONG).show();
        }

        addPhotosBtn.setEnabled(false);
        io.execute(() -> {
            List<PhotoEntry> entries = new ArrayList<>(existing);
            File outDir = new File(getFilesDir(), PHOTO_DIR);
            if (!outDir.exists()) outDir.mkdirs();
            long ts = System.currentTimeMillis();
            int idx = 0;
            for (Uri uri : accepted) {
                // Downsize to JPEG — full-res selfies would OOM the IME service heap
                // once base64-encoded into the model request body.
                File dest = new File(outDir, ts + "_" + (idx++) + ".jpg");
                if (copyDownsizedToFile(uri, dest)) {
                    entries.add(new PhotoEntry(dest.getAbsolutePath(), ""));
                }
            }
            writeEntries(entries);
            main.post(() -> {
                addPhotosBtn.setEnabled(true);
                renderPhotos();
                maybeStartUploads();
            });
        });
    }

    /** Reads {@code uri}, downsizes to JPEG via {@link ImageDownsizer}, writes to
     *  {@code dest}. Returns false on read or encode failure. */
    private boolean copyDownsizedToFile(Uri uri, File dest) {
        byte[] raw = null;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            raw = buf.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "read failed for " + uri, e);
            return false;
        }
        byte[] downsized = ImageDownsizer.downsizeToJpegBytes(raw);
        //noinspection UnusedAssignment
        raw = null;
        try (FileOutputStream out = new FileOutputStream(dest)) {
            out.write(downsized);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "write failed for " + dest, e);
            return false;
        }
    }

    // -- upload pipeline -----------------------------------------------------

    /** Kicks off uploads for entries with empty file-id. Idempotent. */
    private void maybeStartUploads() {
        if (!auth.isSignedIn()) return;
        List<PhotoEntry> entries = readEntries();
        boolean anyPending = false;
        for (PhotoEntry e : entries) if (e.fileId.isEmpty()) { anyPending = true; break; }
        if (!anyPending) {
            updateUploadStatus(entries);
            return;
        }
        if (!uploadingNow.compareAndSet(false, true)) return;
        updateUploadStatus(entries);

        auth.freshToken(this, DriveScopes.SCOPES, new GoogleAuth.Callback() {
            @Override public void onToken(String accessToken) {
                io.execute(() -> uploadAll(accessToken));
            }
            @Override public void onError(String reason, GoogleAuth.PendingUi pendingUi) {
                uploadingNow.set(false);
                if (pendingUi != null) {
                    runOnUiThread(() -> launchAuthUi(pendingUi.intentSender));
                } else {
                    Log.w(TAG, "upload aborted, no token: " + reason);
                    runOnUiThread(() -> updateUploadStatus(readEntries()));
                }
            }
        });
    }

    private void uploadAll(String accessToken) {
        try {
            List<PhotoEntry> entries = readEntries();
            for (int i = 0; i < entries.size(); i++) {
                PhotoEntry entry = entries.get(i);
                if (!entry.fileId.isEmpty()) continue;
                File local = new File(entry.localPath);
                if (!local.exists()) continue;
                try {
                    byte[] bytes = readAllBytes(local);
                    String name = DriveKeys.UPLOAD_NAME_PREFIX + local.getName();
                    String mime = mimeFromPath(entry.localPath);
                    String fileId = DriveFilesClient.uploadImage(accessToken, name, mime, bytes);
                    entries.set(i, new PhotoEntry(entry.localPath, fileId));
                    writeEntries(entries);
                    final List<PhotoEntry> snapshot = new ArrayList<>(entries);
                    main.post(() -> {
                        renderPhotos();
                        updateUploadStatus(snapshot);
                    });
                } catch (IOException ex) {
                    Log.w(TAG, "upload failed for " + entry.localPath, ex);
                    // Leave fileId empty so onResume retries.
                }
            }
        } finally {
            uploadingNow.set(false);
            main.post(() -> updateUploadStatus(readEntries()));
        }
    }

    private static byte[] readAllBytes(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            return buf.toByteArray();
        }
    }

    private void updateUploadStatus(List<PhotoEntry> entries) {
        if (uploadStatusLine == null) return;
        int total = entries.size();
        if (total == 0) {
            uploadStatusLine.setText("");
            return;
        }
        int uploaded = 0;
        for (PhotoEntry e : entries) if (!e.fileId.isEmpty()) uploaded++;
        if (uploadingNow.get()) {
            uploadStatusLine.setText("Uploading to Drive… " + uploaded + " of " + total + " synced");
            uploadStatusLine.setTextColor(MUTED);
        } else if (uploaded == total) {
            uploadStatusLine.setText("All " + total + " photo" + (total == 1 ? "" : "s") + " in your Drive ✓");
            uploadStatusLine.setTextColor(LIME);
        } else {
            uploadStatusLine.setText(uploaded + " of " + total + " in Drive — " + (total - uploaded) + " pending. Reopen this screen to retry.");
            uploadStatusLine.setTextColor(MUTED);
        }
    }

    // -- entry persistence ---------------------------------------------------

    private static final class PhotoEntry {
        final String localPath;
        final String fileId;
        PhotoEntry(String localPath, String fileId) {
            this.localPath = localPath;
            this.fileId = fileId == null ? "" : fileId;
        }
    }

    private List<PhotoEntry> readEntries() {
        String raw = driveStore.getString(DriveKeys.REFERENCE_PHOTOS, "");
        List<PhotoEntry> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\|", -1);
            String path = parts[0];
            String fileId = parts.length > 1 ? parts[1] : "";
            if (path.isEmpty()) continue;
            if (!new File(path).exists()) continue;
            out.add(new PhotoEntry(path, fileId));
        }
        return out;
    }

    private void writeEntries(List<PhotoEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append('\n');
            PhotoEntry e = entries.get(i);
            sb.append(e.localPath).append('|').append(e.fileId);
        }
        driveStore.putString(DriveKeys.REFERENCE_PHOTOS, sb.toString());
    }

    // -- render --------------------------------------------------------------

    private void renderPhotos() {
        if (thumbsRow == null) return;
        // Recycle previous thumbnails so renderPhotos doesn't pile bitmaps on heap.
        recycleThumbnails();
        thumbsRow.removeAllViews();
        List<PhotoEntry> entries = readEntries();
        int count = entries.size();

        if (count == 0) {
            photosHelper.setText("Add 3–5 selfies to use /us. They'll upload to your own Drive automatically.");
            clearPhotosBtn.setVisibility(View.GONE);
            addPhotosBtn.setText("Add photos");
            thumbsRow.setVisibility(View.GONE);
            updateUploadStatus(entries);
            return;
        }

        photosHelper.setText(count + " of " + DriveKeys.MAX_REFERENCE_PHOTOS + " photos selected.");
        clearPhotosBtn.setVisibility(View.VISIBLE);
        addPhotosBtn.setText(count >= DriveKeys.MAX_REFERENCE_PHOTOS ? "Cap reached" : "Add more");
        addPhotosBtn.setEnabled(count < DriveKeys.MAX_REFERENCE_PHOTOS);
        thumbsRow.setVisibility(View.VISIBLE);

        int thumbSize = dp(72);
        for (int i = 0; i < entries.size(); i++) {
            PhotoEntry entry = entries.get(i);
            ImageView img = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(thumbSize, thumbSize);
            if (i > 0) lp.leftMargin = dp(8);
            img.setLayoutParams(lp);
            // Border color signals upload state: lime = in Drive, ink = pending.
            img.setBackground(thumbBorder(entry.fileId.isEmpty() ? INK : LIME));
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap bmp = decodeThumb(entry.localPath, thumbSize);
            if (bmp != null) img.setImageBitmap(bmp);
            thumbsRow.addView(img);
        }
        updateUploadStatus(entries);
    }

    /** Subsampling decode to avoid OOM on large sources. */
    private Bitmap decodeThumb(String path, int targetPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            int w = bounds.outWidth, h = bounds.outHeight;
            while (w / 2 >= targetPx && h / 2 >= targetPx) { w /= 2; h /= 2; sample *= 2; }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, decode);
        } catch (Exception e) {
            Log.w(TAG, "thumb decode failed for " + path, e);
            return null;
        }
    }

    private void recycleThumbnails() {
        if (thumbsRow == null) return;
        for (int i = 0; i < thumbsRow.getChildCount(); i++) {
            View c = thumbsRow.getChildAt(i);
            if (!(c instanceof ImageView)) continue;
            ImageView iv = (ImageView) c;
            Drawable d = iv.getDrawable();
            iv.setImageDrawable(null);
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                if (b != null && !b.isRecycled()) b.recycle();
            }
        }
    }

    private Drawable thumbBorder(int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(WHITE);
        d.setStroke(dp(2), strokeColor);
        d.setCornerRadius(dp(2));
        return d;
    }

    private void confirmClearPhotos() {
        new AlertDialog.Builder(this)
                .setTitle("Clear all reference photos?")
                .setMessage("Removes the photos from this device AND from your Drive. Re-add them any time.")
                .setPositiveButton("Clear", (d, w) -> startClearAll())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startClearAll() {
        // If token fetch fails, still wipe locally — the user asked for it.
        auth.freshToken(this, DriveScopes.SCOPES, new GoogleAuth.Callback() {
            @Override public void onToken(String accessToken) {
                io.execute(() -> deleteAll(accessToken));
            }
            @Override public void onError(String reason, GoogleAuth.PendingUi pendingUi) {
                if (pendingUi != null) {
                    runOnUiThread(() -> launchAuthUi(pendingUi.intentSender));
                } else {
                    io.execute(() -> deleteAll(null));
                }
            }
        });
    }

    private void deleteAll(String accessToken) {
        List<PhotoEntry> entries = readEntries();
        int driveFails = 0;
        for (PhotoEntry e : entries) {
            //noinspection ResultOfMethodCallIgnored
            new File(e.localPath).delete();
            if (accessToken != null && !e.fileId.isEmpty()) {
                try {
                    DriveFilesClient.deleteFile(accessToken, e.fileId);
                } catch (IOException ex) {
                    Log.w(TAG, "drive delete failed for " + e.fileId, ex);
                    driveFails++;
                }
            }
        }
        driveStore.putString(DriveKeys.REFERENCE_PHOTOS, "");
        final int finalDriveFails = driveFails;
        main.post(() -> {
            renderPhotos();
            if (finalDriveFails > 0) {
                Toast.makeText(this,
                        finalDriveFails + " Drive file(s) couldn't be deleted — remove them manually from drive.google.com",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String mimeFromPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return "image/jpeg";
        String ext = path.substring(dot + 1).toLowerCase();
        switch (ext) {
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "png":  return "image/png";
            case "webp": return "image/webp";
            case "heic": return "image/heic";
            case "gif":  return "image/gif";
            default:     return "image/jpeg";
        }
    }

    private LinearLayout brutalCard(int fill) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(brutalistCard(fill));
        int p = dp(16);
        card.setPadding(p, p, p, p);
        return card;
    }

    private LinearLayout.LayoutParams brutalCardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(4);
        lp.bottomMargin = dp(4);
        return lp;
    }

    private Drawable brutalistCard(int fill) {
        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(INK);
        shadow.setCornerRadius(dp(2));
        GradientDrawable card = new GradientDrawable();
        card.setColor(fill);
        card.setStroke(dp(2), INK);
        card.setCornerRadius(dp(2));
        LayerDrawable layered = new LayerDrawable(new Drawable[]{shadow, card});
        layered.setLayerInset(0, dp(4), dp(4), 0, 0);
        layered.setLayerInset(1, 0, 0, dp(4), dp(4));
        return layered;
    }

    private Drawable pillDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(999));
        d.setStroke(dp(1), INK);
        return d;
    }

    private Drawable circleDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(2), INK);
        return d;
    }

    private static String initial(String email) {
        if (email == null || email.isEmpty()) return "·";
        char c = email.charAt(0);
        return String.valueOf(Character.toUpperCase(c));
    }

    private static int avatarColor(String email) {
        int[] palette = { LIME, PINK, BLUE, 0xFFFF7A1A /* orange */ };
        if (email == null || email.isEmpty()) return MUTED;
        return palette[Math.floorMod(email.hashCode(), palette.length)];
    }

    private void addHSpacer(LinearLayout container, int width) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        container.addView(v);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
