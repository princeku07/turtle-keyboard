package com.prince.turtlekeyboard.integration.gif;

import android.graphics.Bitmap;
import android.util.Log;

/**
 * Replaces a uniform solid-color sprite-sheet background with full transparency
 * (alpha=0) so the downstream GIF encoder can render the subject against the
 * player's background.
 *
 * <p>Strategy: sample the four sheet corners. If they're all opaque and at least
 * three share the same RGB, treat that color as the sheet's background and
 * rewrite every matching pixel to transparent. If corners disagree — e.g. the
 * subject extends to a corner — the bitmap is returned unchanged. Conservative
 * on purpose; a wrong key would punch holes in the subject.
 *
 * <p>Used by:
 * <ul>
 *   <li>{@link GifIntegration} as the fallback when the difference-matte pass
 *       can't run.</li>
 *   <li>{@code SpriteToGifTestActivity} on the bundled green-screen test sheet
 *       and on any user-picked sheet.</li>
 * </ul>
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
        // All four corners must be opaque (model gave us a solid bg, not the
        // intended transparent output). If any corner already has low alpha,
        // assume the source did honor transparency — leave the bitmap alone.
        for (int c : corners) {
            if (((c >>> 24) & 0xff) < 250) return sheet;
        }
        // Pick the most-common corner RGB. Need ≥ 3 agreement to commit.
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

    /** Deterministic single-color mask. Sweeps every opaque pixel; any pixel
     *  within Euclidean RGB distance ≤ {@code tolerance} of {@code targetRgb}
     *  is replaced with alpha=0. Used by {@code /gif} where the system prompt
     *  locks the model's background to a specific known color — there's no
     *  need to detect it via corner sampling, and a fixed target catches
     *  anti-aliasing artifacts that exact-match {@link #apply} would miss.
     *
     *  @param sheet       source bitmap; recycled if a new ARGB_8888 result is built
     *  @param targetRgb   24-bit RGB (no alpha channel), e.g. {@code 0xFFFFFF} for white
     *  @param tolerance   Euclidean RGB distance (0 = exact match; 10 catches mild
     *                     anti-aliasing; 30+ risks eating subject highlights)
     *  @return new ARGB_8888 bitmap with matching pixels keyed transparent, or the
     *          input bitmap unchanged when no pixels matched (input recycled either way) */
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
            if (alpha < 250) continue; // already (semi-)transparent — leave alone
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
