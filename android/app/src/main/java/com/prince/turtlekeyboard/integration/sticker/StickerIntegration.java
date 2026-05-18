package com.prince.turtlekeyboard.integration.sticker;

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
 * {@code /sticker} — generate a cut-out sticker PNG with proper transparency.
 *
 * <p>End-to-end flow:
 * <ol>
 *   <li><b>Pass 1.</b> Text → image with the {@code sticker.txt} system prompt
 *       (locks subject onto a pure-white background).</li>
 *   <li><b>Pass 2.</b> The white-bg PNG is fed back as an image-edit reference
 *       with the fixed {@link #EDIT_TO_BLACK_PROMPT} — "change white to black,
 *       preserve everything else." This second render is used purely to
 *       recover alpha.</li>
 *   <li>{@link AlphaMatte#differenceMatte(Bitmap, Bitmap)} computes per-pixel
 *       alpha from the two renders. Result is an ARGB_8888 sticker with proper
 *       transparency, including anti-aliased edges and any translucent regions
 *       the model rendered.</li>
 *   <li>Save the matted bitmap as a PNG with alpha into the shared cache and
 *       commit into the host editor via {@code ctx.commitImage(uri,
 *       "image/png")}. The PNG is also persisted to {@link ImageHistory} so it
 *       lands in the keyboard's history panel and the host app's gallery.</li>
 * </ol>
 *
 * <p><b>Fallback.</b> If Pass 2 fails at the model level (or the two renders
 * disagree on dimensions), we degrade automatically to encoding Pass 1's
 * white-bg PNG as-is — the user still gets a sticker, just on a white card
 * instead of a clean cut-out. Better than blocking on a refresh.
 *
 * <p>Latency: two image calls + decode + matte + encode. Budget ~20–25 s
 * end-to-end; the keyboard shows a "Making sticker…" loader for up to 60 s.
 * Both raw renders and the matted result are persisted to {@link ImageHistory}
 * with distinguishing labels so the History screen can show every stage from
 * one /sticker call when you're debugging.
 *
 * <p>This integration replaces the previous {@code LmStudioAiClient} branch
 * for {@code /sticker}, which produced a single-pass opaque image. Old
 * stickers in the user's history still display correctly — only newly
 * generated stickers go through the matte pipeline.
 */
public class StickerIntegration implements KeyboardIntegration {

    private static final String TAG = "StickerIntegration";

    private static final long BUSY_BANNER_MS  = 60_000L;
    private static final long FAIL_BANNER_MS  = 2_500L;
    private static final long EMPTY_BANNER_MS = 2_200L;

    /** Pass-2 user prompt for the difference-matte technique. Pass 1 renders
     *  the sticker on pure white (per {@code sticker.txt}); Pass 2 asks the
     *  model to swap the background to pure black while preserving the
     *  subject. The client then computes alpha per pixel from the two
     *  renders. Article: jidefr.medium.com/generating-transparent-background-images-with-nano-banana-pro */
    private static final String EDIT_TO_BLACK_PROMPT =
            "Change the white background to a solid pure black #000000 background. "
                    + "Keep the subject and every pixel of it exactly unchanged: "
                    + "same identity, same colors, same outline, same pose, same "
                    + "expression, same proportions, same line weight. Do not move, "
                    + "resize, recolor, or restyle the subject. Only the background "
                    + "color changes.";

    /** Same affinity set as the GIF/image-generating integrations: chat-style
     *  apps where dropping a sticker inline is the dominant use case. */
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

    @Override public String id() { return "sticker"; }

    @Override
    @Nullable
    public IntegrationSession activate(EditorInfo info, IntegrationContext ctx) {
        return null;
    }

    @Override
    public List<CommandSpec> commands() {
        return Collections.singletonList(
                new CommandSpec("sticker", "Sticker", "🪄", true, this::handleSticker,
                        CHAT_AFFINITY, "Making sticker"));
    }

    // -- handler -------------------------------------------------------------

    private void handleSticker(String prompt, IntegrationContext ctx) {
        final String trimmed = prompt == null ? "" : prompt.trim();
        if (trimmed.isEmpty()) {
            ctx.showBanner("Describe the sticker, e.g. /sticker happy turtle",
                    EMPTY_BANNER_MS);
            return;
        }
        // /sticker has two flows:
        //   • Photo flow — user picked a photo via the pre-launched picker;
        //     pass 1 redraws that photo as a sticker on white bg. Loads the
        //     sticker_photo.txt system prompt.
        //   • Text-only flow — user dismissed the picker (no staged photo);
        //     pass 1 generates from scratch using the user's prompt. Loads
        //     the sticker.txt system prompt.
        // Either way, pass 2 (handled in runMattePass) swaps the background
        // to black on the pass-1 output and the client recovers per-pixel
        // alpha via difference matting.
        IntegrationContext.PickedImage picked =
                LmStudioAiClient.consumeStagedEditImage();
        final boolean photoMode = picked != null;
        final String promptAsset = photoMode ? "sticker_photo" : "sticker";
        final String systemPrompt = AssetPrompts.load(ctx.appContext(), promptAsset);
        if (systemPrompt.isEmpty()) {
            ctx.showBanner("Sticker prompt missing — clean rebuild needed",
                    FAIL_BANNER_MS);
            return;
        }
        ctx.showBanner("Making sticker…", BUSY_BANNER_MS);

        GeminiService.ImageCallback pass1Cb = new GeminiService.ImageCallback() {
            @Override public void onImage(byte[] whitePng) {
                runMattePass(ctx, whitePng, trimmed);
            }
            @Override public void onError(String reason) {
                ctx.showBanner("Sticker failed: " + reason, FAIL_BANNER_MS);
            }
        };

        if (photoMode) {
            GeminiService.InlineImage ref =
                    new GeminiService.InlineImage(picked.bytes, picked.mime);
            ctx.ai().imageEdit(systemPrompt, trimmed,
                    Collections.singletonList(ref), pass1Cb);
        } else {
            // Plain image() call (text → image). Same matte pipeline after.
            ctx.ai().image(systemPrompt, trimmed, pass1Cb);
        }
    }

    /** Pass 2 of the difference-matte technique. Re-runs Pass 1's image as an
     *  edit with the fixed "swap white to black" prompt; the client then
     *  subtracts the two renders to recover per-pixel alpha. On Pass-2 failure
     *  (network, decode, or model-side error) we fall back to committing
     *  Pass 1's white-bg PNG as-is — the user still gets a sticker, just
     *  without the cut-out. */
    private void runMattePass(IntegrationContext ctx, byte[] whitePng, String userPrompt) {
        recordPngToHistory(ctx, whitePng, userPrompt, "sticker_white");
        GeminiService.InlineImage whiteRef =
                new GeminiService.InlineImage(whitePng, "image/png");
        ctx.ai().imageEdit(null, EDIT_TO_BLACK_PROMPT,
                Collections.singletonList(whiteRef),
                new GeminiService.ImageCallback() {
                    @Override public void onImage(byte[] blackPng) {
                        assembleStickerWithMatte(ctx, whitePng, blackPng, userPrompt);
                    }
                    @Override public void onError(String reason) {
                        Log.w(TAG, "pass-2 (black bg) failed, falling back to "
                                + "opaque white-bg PNG: " + reason);
                        commitOpaqueFallback(ctx, whitePng, userPrompt);
                    }
                });
    }

    /** Off the main thread: decode both passes → difference-matte → save as
     *  PNG with alpha → commit. Falls back to the opaque path on dimension
     *  mismatch or any decode failure so the user still gets *something*. */
    private void assembleStickerWithMatte(IntegrationContext ctx, byte[] whitePng,
                                          byte[] blackPng, String userPrompt) {
        io.execute(() -> {
            recordPngToHistory(ctx, blackPng, userPrompt, "sticker_black");

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
                    Log.w(TAG, "matte dim mismatch white=" + onWhite.getWidth() + "x"
                            + onWhite.getHeight() + " black=" + onBlack.getWidth() + "x"
                            + onBlack.getHeight() + " — falling back to opaque white-bg");
                    onWhite.recycle();
                    onBlack.recycle();
                    commitOpaqueFallbackInline(ctx, whitePng, userPrompt);
                    return;
                }

                Bitmap matted = AlphaMatte.differenceMatte(onWhite, onBlack);
                onWhite.recycle();
                onBlack.recycle();

                writePngAndCommit(ctx, matted, userPrompt);
            } catch (Exception e) {
                Log.w(TAG, "matte assemble failed, falling back to opaque", e);
                if (onWhite != null && !onWhite.isRecycled()) onWhite.recycle();
                if (onBlack != null && !onBlack.isRecycled()) onBlack.recycle();
                commitOpaqueFallbackInline(ctx, whitePng, userPrompt);
            }
        });
    }

    /** Opaque-PNG fallback (scheduled). Used when Pass 2 errored at the model
     *  level — the io thread isn't doing anything yet, so we hop onto it. */
    private void commitOpaqueFallback(IntegrationContext ctx, byte[] whitePng,
                                      String userPrompt) {
        io.execute(() -> commitOpaqueFallbackInline(ctx, whitePng, userPrompt));
    }

    /** Body of the opaque fallback. Must run on the io thread. Used from
     *  {@link #commitOpaqueFallback} (model-level error) and from
     *  {@link #assembleStickerWithMatte} (mid-matte error). */
    private void commitOpaqueFallbackInline(IntegrationContext ctx, byte[] whitePng,
                                            String userPrompt) {
        try {
            File outFile = writePngBytes(ctx, whitePng);
            ImageHistory.record(ctx.appContext(), outFile, "sticker", userPrompt);
            publishToHost(ctx, outFile);
        } catch (Exception e) {
            Log.w(TAG, "opaque fallback failed", e);
            final String msg = e.getMessage() == null ? "encode failed" : e.getMessage();
            main.post(() -> ctx.showBanner("Sticker failed: " + msg, FAIL_BANNER_MS));
        }
    }

    /** Compress the matted bitmap as a PNG (alpha preserved), drop it into the
     *  shared cache, record into {@link ImageHistory}, and commit the URI into
     *  the host editor. Must run on the io thread; recycles {@code matted}
     *  before returning. */
    private void writePngAndCommit(IntegrationContext ctx, Bitmap matted, String userPrompt) {
        File outFile = null;
        try {
            File outDir = new File(ctx.appContext().getCacheDir(), "shared_images");
            if (!outDir.exists() && !outDir.mkdirs()) {
                throw new IOException("cannot create cache dir");
            }
            outFile = new File(outDir, "sticker_" + System.currentTimeMillis() + ".png");
            try (OutputStream out = new FileOutputStream(outFile)) {
                // 100 = lossless. PNG preserves the recovered alpha channel
                // unchanged so chat hosts that respect transparency render
                // the sticker as a cut-out.
                matted.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            matted.recycle();
            ImageHistory.record(ctx.appContext(), outFile, "sticker", userPrompt);
            publishToHost(ctx, outFile);
        } catch (Exception e) {
            Log.w(TAG, "sticker write failed", e);
            if (!matted.isRecycled()) matted.recycle();
            final String msg = e.getMessage() == null ? "encode failed" : e.getMessage();
            main.post(() -> ctx.showBanner("Sticker failed: " + msg, FAIL_BANNER_MS));
            if (outFile != null && outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
        }
    }

    /** Writes raw PNG bytes to a fresh file in the shared cache and returns
     *  it. Used by the opaque fallback path which already has bytes in hand
     *  and doesn't need to re-encode. */
    private static File writePngBytes(IntegrationContext ctx, byte[] png) throws IOException {
        File outDir = new File(ctx.appContext().getCacheDir(), "shared_images");
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("cannot create cache dir");
        }
        File outFile = new File(outDir, "sticker_" + System.currentTimeMillis() + ".png");
        try (OutputStream out = new FileOutputStream(outFile)) {
            out.write(png);
        }
        return outFile;
    }

    /** Common tail: wrap the file in a FileProvider URI and hand it to the
     *  host editor on the main thread. */
    private void publishToHost(IntegrationContext ctx, File file) {
        final Uri uri = FileProvider.getUriForFile(
                ctx.appContext(),
                ctx.appContext().getPackageName() + ".fileprovider",
                file);
        main.post(() -> ctx.commitImage(uri, "image/png"));
    }

    /** Writes a debug-stage PNG to {@link ImageHistory} so the four artifacts
     *  produced by one /sticker call (white pass, black pass, opaque fallback
     *  if any, final matted sticker) are inspectable from the History screen.
     *  Best-effort — IO failures are logged and swallowed so a history hiccup
     *  never blocks the user-visible result. */
    private static void recordPngToHistory(IntegrationContext ctx, byte[] png,
                                           String userPrompt, String label) {
        try {
            File tmpDir = new File(ctx.appContext().getCacheDir(), "shared_images");
            if (!tmpDir.exists() && !tmpDir.mkdirs()) return;
            File tmp = new File(tmpDir,
                    "sticker_" + label + "_" + System.currentTimeMillis() + ".png");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(png);
            }
            ImageHistory.record(ctx.appContext(), tmp, "sticker",
                    userPrompt + " — " + label);
        } catch (Exception e) {
            Log.w(TAG, "history record failed (non-fatal)", e);
        }
    }
}
