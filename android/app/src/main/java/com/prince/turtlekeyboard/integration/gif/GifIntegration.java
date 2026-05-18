package com.prince.turtlekeyboard.integration.gif;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.prince.kbd.core.AssetPrompts;
import com.prince.kbd.core.CommandSpec;
import com.prince.kbd.core.GeminiService;
import com.prince.kbd.core.IntegrationContext;
import com.prince.kbd.core.IntegrationSession;
import com.prince.kbd.core.KeyboardIntegration;
import com.prince.turtlekeyboard.ai.AlphaMatte;
import com.prince.turtlekeyboard.ai.GifEncoder;
import com.prince.turtlekeyboard.ai.ImageHistory;
import com.prince.turtlekeyboard.ai.LmStudioAiClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code /gif} — turn a photo + a description into a looping animated GIF.
 *
 * <p>End-to-end flow:
 * <ol>
 *   <li>User types {@code /gif} → the IME pre-launches the photo picker (same hook
 *       {@code /edit} and {@code /style} use). Picked bytes land in
 *       {@code LmStudioAiClient.stagedEditImage} and a thumbnail appears in the
 *       prompt panel.</li>
 *   <li>User types {@code <animation idea>} and hits Go.</li>
 *   <li><b>Pass 1.</b> Handler consumes the staged image via
 *       {@link LmStudioAiClient#consumeStagedEditImage} and sends it plus the user
 *       prompt to {@code ctx.ai().imageEdit} with the locked sprite-sheet system
 *       prompt ({@code commands/prompts/gif.txt}). The prompt requires a pure white
 *       {@code #FFFFFF} background.</li>
 *   <li><b>Pass 2.</b> The white-bg PNG is fed back as an edit reference with the
 *       fixed {@link #EDIT_TO_BLACK_PROMPT} — "change white to black, preserve
 *       everything else." This second render is used purely to recover alpha.</li>
 *   <li>{@link AlphaMatte#differenceMatte} computes per-pixel alpha from the two renders
 *       (technique from {@code jidefr.medium.com/.../1866c88a33c5}). Result is an
 *       ARGB_8888 sheet with proper transparency, including anti-aliased edges and
 *       any translucent regions the model rendered.</li>
 *   <li>Grid shape is auto-detected from the sheet's aspect ratio (5×2 = 10 frames,
 *       5×1 strip = 5 frames). Frames are sliced via {@link SpriteSheetSlicer} and
 *       encoded by {@link GifEncoder#encodeAnimated} with a global palette and
 *       disposal=2. Frame delay scales to keep a 1-second loop either way.</li>
 *   <li>Final {@code image/gif} bytes are written to the shared cache and inserted
 *       into the host editor via {@code ctx.commitImage}.</li>
 * </ol>
 *
 * <p><b>Fallback.</b> If Pass 2 fails at the model level (or the two renders
 * disagree on dimensions), we degrade automatically to single-pass chroma-key on
 * Pass 1's image alone. The user still gets a GIF; just with rougher hard-edge
 * transparency instead of the matted alpha gradient.
 *
 * <p>Latency: two image-edit calls + decode + matte + encode. Budget ~12–15 s; the
 * keyboard shows an "Animating…" gradient loader for up to 60 s. Both raw renders
 * and the matted sheet are persisted to {@link ImageHistory} with distinguishing
 * labels so you can inspect each stage from the host app's History screen.
 */
public class GifIntegration implements KeyboardIntegration {

    private static final String TAG = "GifIntegration";

    private static final long BUSY_BANNER_MS  = 60_000L;
    private static final long FAIL_BANNER_MS  = 2_500L;
    private static final long EMPTY_BANNER_MS = 2_200L;

    /** Locked by {@code gif.txt}: the model always returns 4 columns. The row count
     *  is detected at runtime from the returned sheet's aspect ratio:
     *  <ul>
     *    <li>4×4 (16 frames) — preferred. Sheet 1024×1024 ⇒ aspect 1.0.</li>
     *    <li>4×2 (8 frames) — fallback. Sheet 1024×512 ⇒ aspect 2.0.</li>
     *    <li>4×1 (4 frames) — last resort. Sheet 1024×256 ⇒ aspect 4.0.</li>
     *  </ul> */
    private static final int COLS = 4;

    /** Aspect-ratio cutoffs between the three layouts. {@code ≤ GRID_4X4_MAX_ASPECT}
     *  is the 4×4 square; {@code GRID_4X4_MAX_ASPECT..STRIP_4X1_MIN_ASPECT} is
     *  the 4×2 wide grid; above {@code STRIP_4X1_MIN_ASPECT} is the 4×1 strip.
     *  Picked at the midpoints between the nominal aspects (1.0, 2.0, 4.0) so
     *  noise on either side doesn't flip the detection. */
    private static final double GRID_4X4_MAX_ASPECT  = 1.5;
    private static final double STRIP_4X1_MIN_ASPECT = 3.0;

    /** Per-frame delay in centiseconds. Each layout targets ~1-second loop so
     *  perceived speed is layout-independent:
     *  16 frames ×  6 cs = 0.96 s,
     *   8 frames × 12 cs = 0.96 s,
     *   4 frames × 25 cs = 1.00 s. */
    private static final int FRAME_DELAY_CS_4X4 = 6;
    private static final int FRAME_DELAY_CS_4X2 = 12;
    private static final int FRAME_DELAY_CS_4X1 = 25;

    /** 0 = loop forever. */
    private static final int LOOP_FOREVER = 0;

    /** Pass-2 user prompt for the difference-matte technique. Pass 1 renders the
     *  subject on pure white (per {@code gif.txt}); Pass 2 asks the model to swap
     *  the background to pure black while preserving everything else. The client
     *  then computes alpha per pixel from the two renders. Article:
     *  jidefr.medium.com/generating-transparent-background-images-with-nano-banana-pro-2 */
    private static final String EDIT_TO_BLACK_PROMPT =
            "Change the white background to a solid pure black #000000 background. "
                    + "Keep every cell, every frame, every pose, every expression, and "
                    + "every pixel of the subject exactly unchanged. Do not move, resize, "
                    + "recolor, or restyle the subject. Do not change the layout, cell "
                    + "count, or frame ordering. Only the background color changes.";

    /** Same affinity set as the other image-generating commands in
     *  {@code BuiltinAiCommands}: chat-style apps where dropping a GIF inline is
     *  the dominant use case. Redeclared locally because the constant in
     *  {@code BuiltinAiCommands} is private and this integration owns its own
     *  affinity per the migration direction. */
    private static final Set<String> CHAT_AFFINITY;
    static {
        Set<String> s = new HashSet<>(Arrays.asList(
                "com.whatsapp",
                "org.telegram.messenger",
                "com.discord",
                "com.facebook.orca",
                "com.instagram.android"));
        CHAT_AFFINITY = Collections.unmodifiableSet(s);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public String id() { return "gif"; }

    @Override
    @Nullable
    public IntegrationSession activate(EditorInfo info, IntegrationContext ctx) {
        return null;
    }

    @Override
    public List<CommandSpec> commands() {
        // Two surface commands, one integration:
        //   /gif  — fast, single-pass, model picks a tone-appropriate background.
        //           Uses the gif_color.txt prompt and encodes the sheet opaque.
        //   /gift — slower, two-pass white-bg + black-bg + difference matte for
        //           transparent output. Uses the gif.txt prompt (which locks bg
        //           to #FFFFFF so the matte can recover alpha cleanly).
        return Arrays.asList(
                new CommandSpec("gif", "GIF", "🎞️", true, this::handleGifColor,
                        CHAT_AFFINITY, "Animating"),
                new CommandSpec("gift", "GIF cutout", "✂️", true, this::handleGiftTransparent,
                        CHAT_AFFINITY, "Cutting out"));
    }

    // -- handlers ------------------------------------------------------------

    /** {@code /gif} — fast path. Single model call with the free-background
     *  prompt; output sheet is encoded as-is (opaque GIF). The matte pipeline
     *  is bypassed entirely so the user gets a result in ~half the time of
     *  {@code /gift}, at the cost of a baked-in background. */
    private void handleGifColor(String prompt, IntegrationContext ctx) {
        runGif(prompt, ctx, /*transparent*/ false);
    }

    /** {@code /gift} — transparent path. Two model calls (white-bg, black-bg)
     *  fed through {@link AlphaMatte#differenceMatte} to recover per-pixel alpha. Roughly
     *  2× the latency of {@code /gif} but the result drops cleanly onto any
     *  chat background. Falls back to the opaque path on Pass-2 failure. */
    private void handleGiftTransparent(String prompt, IntegrationContext ctx) {
        runGif(prompt, ctx, /*transparent*/ true);
    }

    /** Shared dispatch for both commands. The {@code transparent} flag picks
     *  the system prompt (color-bg vs strict-white-bg) and the post-processing
     *  pipeline (opaque encode vs matte-then-encode). Everything else — the
     *  picker hand-off, staged-image consume, busy banner, error surface — is
     *  identical between the two paths, so it lives here once. */
    private void runGif(String prompt, IntegrationContext ctx, boolean transparent) {
        final String trimmed = prompt == null ? "" : prompt.trim();
        if (trimmed.isEmpty()) {
            String hint = transparent
                    ? "Describe the animation, e.g. /gift wave hello"
                    : "Describe the animation, e.g. /gif make me dance";
            ctx.showBanner(hint, EMPTY_BANNER_MS);
            return;
        }
        // /gift uses the existing strict-white-bg prompt so the matte can run.
        // /gif uses the free-bg variant so Nano Banana can pick a scene.
        final String promptAsset = transparent ? "gif" : "gif_color";
        final String systemPrompt = AssetPrompts.load(ctx.appContext(), promptAsset);
        if (systemPrompt.isEmpty()) {
            ctx.showBanner("Gif prompt missing — clean rebuild needed", FAIL_BANNER_MS);
            return;
        }
        // Picker was pre-launched when the user entered prompt mode (same hook
        // as /edit and /style), so the image is already staged by now.
        // Consume-and-clear so a re-dispatch doesn't reuse a stale image.
        IntegrationContext.PickedImage picked = LmStudioAiClient.consumeStagedEditImage();
        if (picked == null) {
            ctx.showBanner("Pick a photo first", FAIL_BANNER_MS);
            return;
        }
        ctx.showBanner(transparent ? "Cutting out…" : "Animating…", BUSY_BANNER_MS);
        GeminiService.InlineImage ref =
                new GeminiService.InlineImage(picked.bytes, picked.mime);
        // Nano Banana Pro — holds the 4×4 layout reliably so we get one composite
        // sheet back instead of Flash's "3 separate images" failure mode.
        ctx.ai().imageEditPro(systemPrompt, trimmed,
                Collections.singletonList(ref),
                new GeminiService.ImageCallback() {
                    @Override public void onImage(byte[] sheetPng) {
                        if (transparent) {
                            runMattePass(ctx, sheetPng, trimmed);
                        } else {
                            assembleGifChromaFallback(ctx, sheetPng, trimmed);
                        }
                    }
                    @Override public void onError(String reason) {
                        ctx.showBanner("Gif failed: " + reason, FAIL_BANNER_MS);
                    }
                });
    }

    /** Pass 2 of the difference-matte technique. Re-runs the same image as an edit
     *  with a fixed "swap white to black" prompt; the client then subtracts the two
     *  renders to recover per-pixel alpha. On Pass 2 failure (network, decode, or
     *  any model-side error) we fall back to the single-pass chroma-key path on
     *  Pass 1's image alone — the user still gets a GIF, just with the rougher
     *  hard-edge transparency. */
    private void runMattePass(IntegrationContext ctx, byte[] whitePng, String userPrompt) {
        GeminiService.InlineImage whiteRef =
                new GeminiService.InlineImage(whitePng, "image/png");
        ctx.ai().imageEdit(null, EDIT_TO_BLACK_PROMPT,
                Collections.singletonList(whiteRef),
                new GeminiService.ImageCallback() {
                    @Override public void onImage(byte[] blackPng) {
                        assembleGifWithMatte(ctx, whitePng, blackPng, userPrompt);
                    }
                    @Override public void onError(String reason) {
                        Log.w(TAG, "pass-2 (black bg) failed, falling back to "
                                + "chroma-key on pass-1: " + reason);
                        assembleGifChromaFallback(ctx, whitePng, userPrompt);
                    }
                });
    }

    /** Off the main thread: decode both passes → difference-matte → slice → encode.
     *  Saves both raw sheets and the matted sheet to history for debugging. Any
     *  decode or dimension-mismatch problem falls back to chroma-key on Pass 1. */
    private void assembleGifWithMatte(IntegrationContext ctx, byte[] whitePng,
                                      byte[] blackPng, String userPrompt) {
        io.execute(() -> {
            recordSheetToHistory(ctx, whitePng, userPrompt, "white");
            recordSheetToHistory(ctx, blackPng, userPrompt, "black");

            Bitmap onWhite = null;
            Bitmap onBlack = null;
            try {
                onWhite = BitmapFactory.decodeByteArray(whitePng, 0, whitePng.length);
                onBlack = BitmapFactory.decodeByteArray(blackPng, 0, blackPng.length);
                if (onWhite == null || onBlack == null) {
                    throw new IOException("pass decode failed");
                }
                if (onWhite.getWidth()  != onBlack.getWidth()
                 || onWhite.getHeight() != onBlack.getHeight()) {
                    // Pass-2 redrew at a different size — matte impossible. Recycle
                    // here and bail to the chroma fallback inline (we're already on
                    // the io thread, so just call the body directly).
                    Log.w(TAG, "matte dim mismatch white=" + onWhite.getWidth() + "x"
                            + onWhite.getHeight() + " black=" + onBlack.getWidth() + "x"
                            + onBlack.getHeight() + " — falling back to chroma");
                    onWhite.recycle();
                    onBlack.recycle();
                    assembleGifChromaFallbackInline(ctx, whitePng, userPrompt);
                    return;
                }

                Bitmap matted = AlphaMatte.differenceMatte(onWhite, onBlack);
                onWhite.recycle();
                onBlack.recycle();
                recordBitmapToHistory(ctx, matted, userPrompt, "matte");

                sliceAndEncode(ctx, matted, "matte", userPrompt);
            } catch (Exception e) {
                Log.w(TAG, "matte assemble failed, falling back to chroma", e);
                if (onWhite != null && !onWhite.isRecycled()) onWhite.recycle();
                if (onBlack != null && !onBlack.isRecycled()) onBlack.recycle();
                assembleGifChromaFallbackInline(ctx, whitePng, userPrompt);
            }
        });
    }

    /** Single-pass chroma-key fallback path. Schedules onto the io thread; used
     *  when Pass 2 hasn't been attempted yet (e.g. it errored at the model level). */
    private void assembleGifChromaFallback(IntegrationContext ctx, byte[] sheetPng,
                                           String userPrompt) {
        io.execute(() -> assembleGifChromaFallbackInline(ctx, sheetPng, userPrompt));
    }

    /** Body of the chroma-key fallback. Must run on the io thread. Used from both
     *  {@link #assembleGifChromaFallback} (model-level error) and
     *  {@link #assembleGifWithMatte} (mid-matte error, where we're already on io). */
    private void assembleGifChromaFallbackInline(IntegrationContext ctx, byte[] sheetPng,
                                                 String userPrompt) {
        recordSheetToHistory(ctx, sheetPng, userPrompt, "chroma");
        try {
            Bitmap decoded = BitmapFactory.decodeByteArray(sheetPng, 0, sheetPng.length);
            if (decoded == null) throw new IOException("model output failed to decode");
            // Background masking is currently disabled — output the model's
            // sheet as-is. The user's chat will see whatever bg color the
            // model produced (white per gif.txt). To re-enable masking later,
            // wrap `decoded` with BackgroundChromaKey.applyForColor(...) here
            // (deterministic single-color) or .apply(...) (corner-heuristic).
            sliceAndEncode(ctx, decoded, "raw", userPrompt);
        } catch (Exception e) {
            Log.w(TAG, "chroma fallback failed", e);
            final String msg = e.getMessage() == null ? "encode failed" : e.getMessage();
            main.post(() -> ctx.showBanner("Gif failed: " + msg, FAIL_BANNER_MS));
        }
    }

    /** Shared tail of both pipelines: detect grid shape from {@code sheet}'s aspect
     *  ratio, slice into frames, encode the animated GIF, publish via FileProvider,
     *  and commit into the host on the main thread. Recycles {@code sheet} and all
     *  sliced frames before returning. Must run on the io thread.
     *
     *  <p>{@code label} is a short tag ({@code "matte"} / {@code "chroma"}) used in
     *  log output so logcat reveals which path produced the GIF. {@code userPrompt}
     *  is recorded into {@link ImageHistory} alongside the encoded file so the
     *  keyboard's GIFs panel can list it later. */
    private void sliceAndEncode(IntegrationContext ctx, Bitmap sheet, String label,
                                String userPrompt) {
        File outFile = null;
        try {
            double aspect = (double) sheet.getWidth() / sheet.getHeight();
            int rows = rowsForAspect(aspect);
            int delayCs = frameDelayForRows(rows);
            Log.d(TAG, label + " sheet " + sheet.getWidth() + "x" + sheet.getHeight()
                    + " aspect=" + String.format("%.2f", aspect)
                    + " ⇒ " + COLS + "x" + rows + " @ " + delayCs + "cs");

            List<Bitmap> frames;
            try {
                frames = SpriteSheetSlicer.slice(sheet, COLS, rows);
            } finally {
                sheet.recycle();
            }

            File outDir = new File(ctx.appContext().getCacheDir(), "shared_images");
            if (!outDir.exists() && !outDir.mkdirs()) {
                throw new IOException("cannot create cache dir");
            }
            outFile = new File(outDir, "gif_" + System.currentTimeMillis() + ".gif");
            try (OutputStream out = new FileOutputStream(outFile)) {
                GifEncoder.encodeAnimated(frames, delayCs, LOOP_FOREVER, out);
            }
            for (Bitmap f : frames) f.recycle();

            // Persist the final GIF to the long-lived history dir before
            // exposing the cache file to the host. The cache dir is fair
            // game for the OS to clear; ImageHistory's dir is in filesDir
            // and survives across launches so the GIFs tab can re-surface
            // it later. ImageHistory preserves the .gif extension so the
            // tab can filter on it.
            ImageHistory.record(ctx.appContext(), outFile, "gif", userPrompt);

            final Uri uri = FileProvider.getUriForFile(
                    ctx.appContext(),
                    ctx.appContext().getPackageName() + ".fileprovider",
                    outFile);
            main.post(() -> ctx.commitImage(uri, "image/gif"));
        } catch (Exception e) {
            Log.w(TAG, label + " slice/encode failed", e);
            final String msg = e.getMessage() == null ? "encode failed" : e.getMessage();
            main.post(() -> ctx.showBanner("Gif failed: " + msg, FAIL_BANNER_MS));
            if (outFile != null && outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
        }
    }

    /** Aspect-ratio → row count classifier. See {@link #GRID_4X4_MAX_ASPECT}
     *  and {@link #STRIP_4X1_MIN_ASPECT} for the cutoff rationale. */
    private static int rowsForAspect(double aspect) {
        if (aspect > STRIP_4X1_MIN_ASPECT) return 1;
        if (aspect > GRID_4X4_MAX_ASPECT)  return 2;
        return 4;
    }

    /** Row count → per-frame delay (centiseconds). Each layout targets ~1 s loop. */
    private static int frameDelayForRows(int rows) {
        switch (rows) {
            case 1:  return FRAME_DELAY_CS_4X1;
            case 2:  return FRAME_DELAY_CS_4X2;
            default: return FRAME_DELAY_CS_4X4;
        }
    }

    /** Writes the raw sprite-sheet PNG to a temp file in the shared cache and
     *  hands it to {@link ImageHistory} so it appears in the host app's History
     *  screen alongside /cap and /edit outputs. The {@code label} is appended to
     *  the prompt sidecar so the four debug artifacts produced by one /gif call
     *  (Pass-1 white, Pass-2 black, post-matte, and the chroma fallback) are
     *  distinguishable in History. Best-effort: IO failures are logged and
     *  swallowed so a history hiccup never blocks the user-visible result. */
    private static void recordSheetToHistory(IntegrationContext ctx, byte[] png,
                                             String userPrompt, String label) {
        try {
            File tmpDir = new File(ctx.appContext().getCacheDir(), "shared_images");
            if (!tmpDir.exists() && !tmpDir.mkdirs()) return;
            File tmp = new File(tmpDir,
                    "gif_sheet_" + label + "_" + System.currentTimeMillis() + ".png");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(png);
            }
            ImageHistory.record(ctx.appContext(), tmp, "gif",
                    userPrompt + " — " + label);
        } catch (Exception e) {
            Log.w(TAG, "sprite sheet history record failed (non-fatal)", e);
        }
    }

    /** Sibling of {@link #recordSheetToHistory} for an in-memory bitmap (e.g. the
     *  matted sheet — never present as bytes). PNG-encodes the bitmap to a temp
     *  file, then routes through the same {@link ImageHistory#record} path so it
     *  shows up alongside the other debug artifacts. */
    private static void recordBitmapToHistory(IntegrationContext ctx, Bitmap bitmap,
                                              String userPrompt, String label) {
        try {
            File tmpDir = new File(ctx.appContext().getCacheDir(), "shared_images");
            if (!tmpDir.exists() && !tmpDir.mkdirs()) return;
            File tmp = new File(tmpDir,
                    "gif_sheet_" + label + "_" + System.currentTimeMillis() + ".png");
            try (OutputStream out = new FileOutputStream(tmp)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            ImageHistory.record(ctx.appContext(), tmp, "gif",
                    userPrompt + " — " + label);
        } catch (Exception e) {
            Log.w(TAG, "bitmap history record failed (non-fatal)", e);
        }
    }
}
