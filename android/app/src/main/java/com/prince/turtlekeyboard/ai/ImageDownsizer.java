package com.prince.turtlekeyboard.ai;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Shrinks picked images to a heap-safe ceiling before they're staged. Output is
 * always JPEG for a predictable mime; callers don't special-case PNG/HEIC sources.
 */
public final class ImageDownsizer {

    private static final String TAG = "ImageDownsizer";

    public static final int DEFAULT_MAX_SIDE_PX = 1024;
    public static final int DEFAULT_QUALITY = 85;

    private ImageDownsizer() {}

    /**
     * Decodes, scales so the longest side is at most {@code maxSide}, and re-encodes
     * as JPEG. Returns the original bytes on any failure.
     */
    public static byte[] downsizeToJpegBytes(byte[] src, int maxSide, int quality) {
        if (src == null || src.length == 0) return src;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(src, 0, src.length, bounds);
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0) return src;

            // Power-of-two subsample so the full-res bitmap never hits heap.
            int sample = 1;
            int sw = w, sh = h;
            while (sw / 2 >= maxSide && sh / 2 >= maxSide) { sw /= 2; sh /= 2; sample *= 2; }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(src, 0, src.length, decode);
            if (bmp == null) return src;

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
            // OOM lands here too — fall back to original bytes rather than crash.
            Log.w(TAG, "downsize failed, using original bytes", t);
            return src;
        }
    }

    public static byte[] downsizeToJpegBytes(byte[] src) {
        return downsizeToJpegBytes(src, DEFAULT_MAX_SIDE_PX, DEFAULT_QUALITY);
    }
}
