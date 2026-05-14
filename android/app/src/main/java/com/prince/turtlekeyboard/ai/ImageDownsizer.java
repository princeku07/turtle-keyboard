package com.prince.turtlekeyboard.ai;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Shared helper for shrinking picked images to a heap-safe ceiling before they're staged
 * for upload, gen, or thumbnailing. The keyboard's IME service shares heap with all
 * features; one full-res 12 MP selfie ≈ 50 MB on heap as {@code ARGB_8888}, and {@code /us}
 * holds up to five simultaneously in {@link LmStudioAiClient} for the Nano Banana
 * multipart body. Downsizing at pick time fixes that at the source.
 *
 * <p>Output is always JPEG so the downsized files have a predictable size and mime —
 * callers don't have to special-case PNG / HEIC sources.
 */
public final class ImageDownsizer {

    private static final String TAG = "ImageDownsizer";

    /** Cap on the longest side of a picked reference photo / staged /edit source. Nano
     *  Banana doesn't benefit from larger inputs and the heap budget on an IME service
     *  is too tight to keep multi-MB images around. */
    public static final int DEFAULT_MAX_SIDE_PX = 1024;

    /** JPEG quality used after downsize. 85 is the standard "looks the same, half the
     *  bytes" sweet spot. */
    public static final int DEFAULT_QUALITY = 85;

    private ImageDownsizer() {}

    /**
     * Decodes {@code src}, scales so the longest side is at most {@code maxSide}, and
     * re-encodes as JPEG at {@code quality}. Returns the original bytes if anything
     * fails — better to ship a too-big image than to drop it.
     */
    public static byte[] downsizeToJpegBytes(byte[] src, int maxSide, int quality) {
        if (src == null || src.length == 0) return src;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(src, 0, src.length, bounds);
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0) return src;

            // Power-of-two subsample on the way in so the full-res bitmap is never
            // realized on heap. inSampleSize=N decodes to a 1/N-sized bitmap.
            int sample = 1;
            int sw = w, sh = h;
            while (sw / 2 >= maxSide && sh / 2 >= maxSide) { sw /= 2; sh /= 2; sample *= 2; }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(src, 0, src.length, decode);
            if (bmp == null) return src;

            // Subsampling lands on the nearest power of two; if the result is still
            // wider/taller than maxSide we scale once more before encode.
            Bitmap scaled = bmp;
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            if (bw > maxSide || bh > maxSide) {
                float r = (float) maxSide / Math.max(bw, bh);
                int tw = Math.max(1, Math.round(bw * r));
                int th = Math.max(1, Math.round(bh * r));
                scaled = Bitmap.createScaledBitmap(bmp, tw, th, true);
                if (scaled != bmp) bmp.recycle();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out);
            scaled.recycle();
            return out.toByteArray();
        } catch (Throwable t) {
            // OutOfMemoryError lands here too — fall back to the original bytes rather
            // than crash. The caller will handle whatever downstream pressure remains.
            Log.w(TAG, "downsize failed, using original bytes", t);
            return src;
        }
    }

    /** Same as {@link #downsizeToJpegBytes(byte[], int, int)} with default ceiling
     *  ({@link #DEFAULT_MAX_SIDE_PX}) and quality ({@link #DEFAULT_QUALITY}). */
    public static byte[] downsizeToJpegBytes(byte[] src) {
        return downsizeToJpegBytes(src, DEFAULT_MAX_SIDE_PX, DEFAULT_QUALITY);
    }
}
