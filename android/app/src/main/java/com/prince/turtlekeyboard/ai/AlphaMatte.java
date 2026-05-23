package com.prince.turtlekeyboard.ai;

import android.graphics.Bitmap;

/**
 * Difference-matting math: recovers per-pixel alpha from two model renders of the
 * same subject on pure white and pure black backgrounds. Shared by GifIntegration
 * and StickerIntegration; each caller supplies its own pass-2 prompt.
 *
 * <p>Algorithm: {@code alpha = 1 - euclidean(onWhite, onBlack) / sqrt(3·255²)};
 * true RGB back-solved from the black render as {@code observed / alpha}.
 */
public final class AlphaMatte {

    /** Snap thresholds clean up sub-pixel model-side RGB drift at the two ends. */
    private static final double SNAP_OPAQUE = 0.95;
    private static final double SNAP_TRANS  = 0.05;

    /** Max corner-pixel RGB drift from the expected background before a pass is
     *  treated as invalid; beyond this the matte collapses to garbage. */
    public static final int MAX_BG_DRIFT = 105;

    private AlphaMatte() {}

    /**
     * Max euclidean RGB distance between the bitmap's four corners and the
     * expected background. Callers use this to detect a pass where the model
     * ignored the background instruction and bail to a non-matte fallback.
     */
    public static int maxCornerDistance(Bitmap bitmap, int er, int eg, int eb) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] corners = {
                bitmap.getPixel(0, 0),
                bitmap.getPixel(w - 1, 0),
                bitmap.getPixel(0, h - 1),
                bitmap.getPixel(w - 1, h - 1),
        };
        int max = 0;
        for (int c : corners) {
            int dr = ((c >> 16) & 0xff) - er;
            int dg = ((c >> 8)  & 0xff) - eg;
            int db = ( c        & 0xff) - eb;
            int d = (int) Math.sqrt(dr * dr + dg * dg + db * db);
            if (d > max) max = d;
        }
        return max;
    }

    /**
     * Recovers per-pixel alpha and subject RGB from white/black-background renders.
     * Caller must ensure matching dimensions; passed-in bitmaps are not recycled.
     *
     * @param onWhite render with the locked white background
     * @param onBlack render with the locked black background
     * @return a new {@code ARGB_8888} bitmap with recovered alpha
     */
    public static Bitmap differenceMatte(Bitmap onWhite, Bitmap onBlack) {
        int w = onWhite.getWidth();
        int h = onWhite.getHeight();
        final double BG_DIST = Math.sqrt(3.0 * 255.0 * 255.0);

        int[] whitePix = new int[w * h];
        int[] blackPix = new int[w * h];
        onWhite.getPixels(whitePix, 0, w, 0, 0, w, h);
        onBlack.getPixels(blackPix, 0, w, 0, 0, w, h);

        int[] out = new int[w * h];
        for (int i = 0; i < whitePix.length; i++) {
            int pw = whitePix[i];
            int pb = blackPix[i];
            int rw = (pw >> 16) & 0xff, gw = (pw >> 8) & 0xff, bw = pw & 0xff;
            int rb = (pb >> 16) & 0xff, gb = (pb >> 8) & 0xff, bb = pb & 0xff;
            int dr = rw - rb, dg = gw - gb, db = bw - bb;
            double pixDist = Math.sqrt(dr * dr + dg * dg + db * db);
            double alpha = 1.0 - (pixDist / BG_DIST);
            if      (alpha > SNAP_OPAQUE) alpha = 1.0;
            else if (alpha < SNAP_TRANS)  alpha = 0.0;
            else if (alpha < 0.0)         alpha = 0.0;
            else if (alpha > 1.0)         alpha = 1.0;

            int a = (int) Math.round(alpha * 255.0);
            int r, g, b;
            if (alpha <= 0.0) {
                // Zero color on fully-transparent pixels so the encoder doesn't waste a palette slot.
                r = 0; g = 0; b = 0;
            } else {
                // observed = α·subject ⇒ subject = observed/α.
                r = (int) Math.min(255.0, Math.round(rb / alpha));
                g = (int) Math.min(255.0, Math.round(gb / alpha));
                b = (int) Math.min(255.0, Math.round(bb / alpha));
            }
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(out, 0, w, 0, 0, w, h);
        return result;
    }
}
