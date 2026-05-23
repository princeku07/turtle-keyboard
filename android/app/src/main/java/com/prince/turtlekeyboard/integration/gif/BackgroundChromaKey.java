package com.prince.turtlekeyboard.integration.gif;

import android.graphics.Bitmap;
import android.util.Log;

/**
 * Replaces a uniform solid-color sprite-sheet background with full transparency by
 * sampling the four corners and rewriting every matching pixel to alpha=0. Requires
 * three of four corners to agree on the same RGB; otherwise the bitmap is returned
 * unchanged so the subject is never accidentally punched through.
 */
public final class BackgroundChromaKey {

    private static final String TAG = "BackgroundChromaKey";

    private BackgroundChromaKey() {}

    /** Returns the input bitmap when no key is applied, or a new ARGB_8888 bitmap
     *  (with the input recycled) when pixels are replaced. */
    public static Bitmap apply(Bitmap sheet) {
        int w = sheet.getWidth();
        int h = sheet.getHeight();
        int[] corners = new int[]{
                sheet.getPixel(0, 0),
                sheet.getPixel(w - 1, 0),
                sheet.getPixel(0, h - 1),
                sheet.getPixel(w - 1, h - 1)
        };
        // If any corner already has low alpha, assume the source honored transparency.
        for (int c : corners) {
            if (((c >>> 24) & 0xff) < 250) return sheet;
        }
        int target = corners[0] & 0xFFFFFF;
        int matches = countMatches(corners, target);
        if (matches < 3) {
            target = corners[1] & 0xFFFFFF;
            matches = countMatches(corners, target);
            if (matches < 3) return sheet;
        }

        int[] pixels = new int[w * h];
        sheet.getPixels(pixels, 0, w, 0, 0, w, h);
        int keyed = 0;
        for (int i = 0; i < pixels.length; i++) {
            if ((pixels[i] & 0xFFFFFF) == target) {
                pixels[i] = 0; // fully transparent
                keyed++;
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        sheet.recycle();
        Log.d(TAG, "keyed " + keyed + " px of #"
                + String.format("%06X", target) + " to transparent");
        return out;
    }

    private static int countMatches(int[] corners, int targetRgb) {
        int n = 0;
        for (int c : corners) if ((c & 0xFFFFFF) == targetRgb) n++;
        return n;
    }

    /**
     * Single-color mask. Replaces every opaque pixel within Euclidean RGB distance
     * ≤ {@code tolerance} of {@code targetRgb} with alpha=0. Used when the system
     * prompt locks the background to a known color.
     */
    public static Bitmap applyForColor(Bitmap sheet, int targetRgb, int tolerance) {
        int w = sheet.getWidth();
        int h = sheet.getHeight();
        int tr = (targetRgb >> 16) & 0xff;
        int tg = (targetRgb >>  8) & 0xff;
        int tb =  targetRgb        & 0xff;
        int tolSq = tolerance * tolerance;

        int[] pixels = new int[w * h];
        sheet.getPixels(pixels, 0, w, 0, 0, w, h);
        int keyed = 0;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int alpha = (p >>> 24) & 0xff;
            if (alpha < 250) continue; // already (semi-)transparent
            int dr = ((p >> 16) & 0xff) - tr;
            int dg = ((p >>  8) & 0xff) - tg;
            int db = ( p        & 0xff) - tb;
            if (dr * dr + dg * dg + db * db <= tolSq) {
                pixels[i] = 0; // fully transparent
                keyed++;
            }
        }
        if (keyed == 0) {
            Log.d(TAG, "applyForColor: 0 px matched #"
                    + String.format("%06X", targetRgb) + " (±" + tolerance + ")");
            return sheet;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        sheet.recycle();
        Log.d(TAG, "applyForColor keyed " + keyed + " px within "
                + tolerance + " of #" + String.format("%06X", targetRgb));
        return out;
    }
}
