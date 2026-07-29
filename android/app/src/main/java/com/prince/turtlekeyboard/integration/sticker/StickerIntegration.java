package com.prince.turtlekeyboard.integration.sticker;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
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
import com.prince.turtlekeyboard.ai.AiErrorMessages;
import com.prince.turtlekeyboard.ai.AlphaMatte;
import com.prince.turtlekeyboard.ai.ImageHistory;

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
 * <p>This integration replaces the previous {@code TurtleAiClient} branch
 * for {@code /sticker}, which produced a single-pass opaque image. Old
 * stickers in the user's history still display correctly — only newly
 * generated stickers go through the matte pipeline.
 */
public class StickerIntegration implements KeyboardIntegration {

    private static final String TAG = "StickerIntegration";

    private static final long BUSY_BANNER_MS  = 60_000L;
    private static final long FAIL_BANNER_MS  = 2_500L;
    private static final long EMPTY_BANNER_MS = 2_200L;

    /** Number of Pass-2 retries when the model returns an unchanged / non-black
     *  background. Pass-2 is the failure point ~10–15% of the time; one retry
     *  meaningfully lifts cut-out success. We don't retry Pass-1 — that's
     *  generative, and waiting another ~10s for a fresh subject the user might
     *  not want is the wrong tradeoff. */
    private static final int PASS2_RETRIES = 1;

    /** Off by default in every build. When on, the white-bg and black-bg PNG
     *  passes are written to {@link ImageHistory} so they appear in the host's
     *  History panel for inspection. Off in shipped AND dev builds because the
     *  keyboard is used normally during development — two stray opaque PNGs
     *  per /sticker clutter the user-facing list next to the actual cut-out.
     *  Flip to {@code true} locally while debugging matte / dimension issues;
     *  flip back before committing. The final matted (or opaque-fallback) PNG
     *  is recorded unconditionally via {@link #writePngAndCommit} /
     *  {@link #commitOpaqueFallbackInline}. */
    private static final boolean DEBUG_HISTORY = false;

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

    @Override public void destroy() { io.shutdown(); }

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
        IntegrationContext.PickedImage picked = com.prince.turtlekeyboard.TurtleApp
                .from(ctx.appContext()).stagingPipeline().consumeEditImageAsPicked();
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
                Log.w(TAG, "sticker pass-1 failed: " + reason);
                ctx.showBanner(AiErrorMessages.userMessage(reason), FAIL_BANNER_MS);
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
     *  subtracts the two renders to recover per-pixel alpha.
     *
     *  <p>Uses {@link GeminiService#imageEditPro} (Nano Banana Pro) — Pass 2 is
     *  exactly the kind of "preserve everything, change one specific thing"
     *  task Pro is tuned for, and the standard model ignores
     *  {@link #EDIT_TO_BLACK_PROMPT} ~10–15% of the time. Pass 1 stays on the
     *  cheaper model since it's generative.
     *
     *  <p>On Pass-2 failure we retry up to {@link #PASS2_RETRIES} times. If
     *  retries are exhausted, fall back to committing Pass 1's white-bg PNG
     *  as-is — the user still gets a sticker, just without the cut-out. */
    private void runMattePass(IntegrationContext ctx, byte[] whitePng, String userPrompt) {
        recordPngToHistory(ctx, whitePng, userPrompt, "sticker_white");
        runMattePassInternal(ctx, whitePng, userPrompt, PASS2_RETRIES);
    }

    private void runMattePassInternal(IntegrationContext ctx, byte[] whitePng,
                                      String userPrompt, int retriesLeft) {
        GeminiService.InlineImage whiteRef =
                new GeminiService.InlineImage(whitePng, "image/png");
        ctx.ai().imageEditPro(null, EDIT_TO_BLACK_PROMPT,
                Collections.singletonList(whiteRef),
                new GeminiService.ImageCallback() {
                    @Override public void onImage(byte[] blackPng) {
                        assembleStickerWithMatte(ctx, whitePng, blackPng,
                                userPrompt, retriesLeft);
                    }
                    @Override public void onError(String reason) {
                        if (retriesLeft > 0) {
                            Log.w(TAG, "pass-2 model error (retriesLeft="
                                    + retriesLeft + "): " + reason);
                            runMattePassInternal(ctx, whitePng, userPrompt,
                                    retriesLeft - 1);
                        } else {
                            Log.w(TAG, "pass-2 retries exhausted, falling back "
                                    + "to opaque white-bg PNG: " + reason);
                            commitOpaqueFallback(ctx, whitePng, userPrompt);
                        }
                    }
                });
    }

    /** Off the main thread: decode both passes → difference-matte → save as
     *  PNG with alpha → commit. Falls back to the opaque path on dimension
     *  mismatch or any decode failure so the user still gets *something*.
     *
     *  <p>Pass verification before matting:
     *  <ul>
     *    <li><b>Pass-1 (white) drift</b> — if Pass 1's bg isn't really white,
     *        the matte will leak colored fringes around the subject. Bail
     *        straight to opaque (no Pass-2 retry — Pass-1 is the problem and
     *        Pass-2 can't fix it).</li>
     *    <li><b>Pass-2 (black) drift</b> — if Pass 2 returned the unchanged
     *        white image (model ignored the prompt), the matte produces an
     *        opaque-everywhere result. Retry Pass-2 once, then fall back.</li>
     *  </ul> */
    private void assembleStickerWithMatte(IntegrationContext ctx, byte[] whitePng,
                                          byte[] blackPng, String userPrompt,
                                          int retriesLeft) {
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
                    int wA = onWhite.getWidth(), hA = onWhite.getHeight();
                    int wB = onBlack.getWidth(), hB = onBlack.getHeight();
                    double driftW = Math.abs(wA - wB) / (double) Math.max(wA, wB);
                    double driftH = Math.abs(hA - hB) / (double) Math.max(hA, hB);
                    double maxDrift = Math.max(driftW, driftH);

                    if (maxDrift <= 0.05) {
                        // Within 5% — Gemini returns slightly different dims per pass
                        // (e.g. 1344×768 vs 1372×784) even when the prompt locks size.
                        // Bilinear-scale Pass-2 to Pass-1's exact dims; sub-pixel shift
                        // doesn't significantly degrade the matte because subject
                        // pixels still match within the snap-to-opaque threshold.
                        Log.d(TAG, "matte dim within tolerance, scaling pass-2 "
                                + wB + "x" + hB + " -> " + wA + "x" + hA
                                + " (drift " + maxDrift + ")");
                        Bitmap aligned = Bitmap.createScaledBitmap(onBlack, wA, hA, true);
                        if (aligned != onBlack) {
                            onBlack.recycle();
                            onBlack = aligned;
                        }
                    } else {
                        Log.w(TAG, "matte dim mismatch white=" + wA + "x" + hA
                                + " black=" + wB + "x" + hB + " (drift " + maxDrift
                                + ") — falling back to opaque white-bg");
                        onWhite.recycle();
                        onBlack.recycle();
                        commitOpaqueFallbackInline(ctx, whitePng, userPrompt);
                        return;
                    }
                }

                int whiteDrift = AlphaMatte.maxCornerDistance(onWhite, 255, 255, 255);
                int blackDrift = AlphaMatte.maxCornerDistance(onBlack,   0,   0,   0);
                Log.d(TAG, "matte verify whiteCornerDrift=" + whiteDrift
                        + " blackCornerDrift=" + blackDrift
                        + " (max " + AlphaMatte.MAX_BG_DRIFT + ")");

                // Pass-1 bail: if the white background isn't really white,
                // retrying Pass-2 won't help — Pass-1 set the floor. Skip
                // matting and ship the white-bg PNG as opaque so the user
                // still gets a usable sticker.
                if (whiteDrift > AlphaMatte.MAX_BG_DRIFT) {
                    Log.w(TAG, "pass-1 didn't produce a white bg (corner drift="
                            + whiteDrift + ") — falling back to opaque white-bg");
                    onWhite.recycle();
                    onBlack.recycle();
                    commitOpaqueFallbackInline(ctx, whitePng, userPrompt);
                    return;
                }

                // Pass-2 retry: model occasionally ignores EDIT_TO_BLACK_PROMPT
                // and returns the original white image — differenceMatte on
                // two identical-bg images yields alpha=1.0 everywhere. One
                // retry catches most of these.
                if (blackDrift > AlphaMatte.MAX_BG_DRIFT) {
                    onWhite.recycle();
                    onBlack.recycle();
                    if (retriesLeft > 0) {
                        Log.w(TAG, "pass-2 didn't produce a black bg (drift="
                                + blackDrift + "), retrying (retriesLeft="
                                + retriesLeft + ")");
                        main.post(() -> runMattePassInternal(ctx, whitePng,
                                userPrompt, retriesLeft - 1));
                    } else {
                        Log.w(TAG, "pass-2 retries exhausted (drift=" + blackDrift
                                + ") — falling back to opaque white-bg");
                        commitOpaqueFallbackInline(ctx, whitePng, userPrompt);
                    }
                    return;
                }

                Bitmap matted = AlphaMatte.differenceMatte(onWhite, onBlack);
                onWhite.recycle();
                onBlack.recycle();

                // Pad to square when the model returned a non-square frame.
                // The sticker prompt asks for 1024² but Gemini occasionally
                // returns landscape (e.g. 1344×768). Scaling a landscape matte
                // to 512×512 horizontally squishes the subject; padding to a
                // square transparent canvas first preserves proportions.
                if (matted.getWidth() != matted.getHeight()) {
                    int side = Math.max(matted.getWidth(), matted.getHeight());
                    Bitmap squared = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(squared);
                    int dx = (side - matted.getWidth())  / 2;
                    int dy = (side - matted.getHeight()) / 2;
                    c.drawBitmap(matted, dx, dy, null);
                    matted.recycle();
                    matted = squared;
                }

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

    /** Compresses the matted bitmap to a {@code .png} (alpha preserved), drops
     *  it into the shared cache, records into {@link ImageHistory}, and commits
     *  the URI into the host editor with {@code image/png} MIME. PNG is what
     *  WhatsApp (and every other modern chat compose field) advertises in
     *  {@code contentMimeTypes}, so {@code commitContent} actually lands the
     *  image inline rather than falling back to clipboard. WebP was tried but
     *  WhatsApp's compose field doesn't advertise {@code image/webp},
     *  triggering the clipboard fallback for every sticker.
     *
     *  <p>Must run on the io thread; recycles {@code matted} before returning. */
    private void writePngAndCommit(IntegrationContext ctx, Bitmap matted, String userPrompt) {
        File outFile = null;
        Bitmap scaled = null;
        try {
            File outDir = new File(ctx.appContext().getCacheDir(), "shared_images");
            if (!outDir.exists() && !outDir.mkdirs()) {
                throw new IOException("cannot create cache dir");
            }

            // The sticker.txt prompt locks output to 1024×1024, so a single
            // bilinear downscale lands exactly on the 512 target without
            // padding or aspect math. createScaledBitmap may return the same
            // bitmap when source size already matches — guard against that
            // before recycling so we don't recycle out from under ourselves.
            scaled = (matted.getWidth() == 512 && matted.getHeight() == 512)
                    ? matted
                    : Bitmap.createScaledBitmap(matted, 512, 512, true);
            if (scaled != matted) matted.recycle();

            outFile = new File(outDir, "sticker_" + System.currentTimeMillis() + ".png");
            try (OutputStream out = new FileOutputStream(outFile)) {
                // Quality is ignored for PNG (always lossless), but the
                // parameter is non-optional. Lossless PNG preserves the
                // recovered alpha channel exactly so chat hosts that respect
                // transparency render the sticker as a cut-out.
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            scaled.recycle();
            ImageHistory.record(ctx.appContext(), outFile, "sticker", userPrompt);
            publishToHost(ctx, outFile);
        } catch (Exception e) {
            Log.w(TAG, "sticker write failed", e);
            if (scaled != null && !scaled.isRecycled()) scaled.recycle();
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
     *  host editor on the main thread as {@code image/png}. WhatsApp + most
     *  chat compose fields advertise PNG in {@code contentMimeTypes}, so this
     *  is the MIME that lets {@code commitContent} actually insert inline. */
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
     *  never blocks the user-visible result.
     *
     *  <p>Production-gated: two opaque white/black PNG passes cluttering the
     *  user-facing History panel for every /sticker is confusing. The final
     *  matted sticker is still recorded via {@link ImageHistory#record} in
     *  {@link #writePngAndCommit} / {@link #commitOpaqueFallbackInline},
     *  unaffected. */
    private static void recordPngToHistory(IntegrationContext ctx, byte[] png,
                                           String userPrompt, String label) {
        if (!DEBUG_HISTORY) return;
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
