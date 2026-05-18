package com.prince.turtlekeyboard.ai;

import android.graphics.Bitmap;

/**
 * Difference-matting math shared between any AI-image feature that needs to
 * recover per-pixel transparency from two model renders of the same subject —
 * one on pure white, one on pure black. Used by:
 *
 * <ul>
 *   <li>{@code GifIntegration} — recovers alpha for the sprite-sheet so
 *       animated GIF frames drop cleanly onto chat backgrounds.</li>
 *   <li>{@code StickerIntegration} — recovers alpha for single-frame stickers
 *       so they read as cut-outs rather than rectangles with a baked bg.</li>
 * </ul>
 *
 * <p>Each caller supplies its own pass-2 user prompt (the "swap white to
 * black" instruction text), since the surrounding constraints differ — GIF
 * tells the model to preserve the cell grid; sticker just tells it to
 * preserve the subject. The math itself is identical in both cases, hence
 * its presence here.
 *
 * <p>Algorithm (from <em>jidefr.medium.com/generating-transparent-background-images-with-nano-banana-pro</em>):
 * <pre>
 *   pixelDist = euclidean(pixOnWhite, pixOnBlack)
 *   bgDist    = euclidean(WHITE, BLACK) = sqrt(3 · 255²) ≈ 441.67
 *   alpha     = 1 - (pixelDist / bgDist)
 * </pre>
 * Subject pixels match across both renders ⇒ {@code pixelDist ≈ 0} ⇒
 * {@code alpha = 1}. Background pixels differ by the full distance ⇒
 * {@code alpha = 0}. Anti-aliased edges and translucent regions get a
 * proportional alpha. True RGB is back-solved from the black render:
 * {@code observed = α · true} ⇒ {@code true = observed / α}. Snap thresholds
 * at the two ends clean up small model-side RGB drift.
 */
public final class AlphaMatte {

    /** Snap-to-opaque / snap-to-transparent thresholds on recovered alpha.
     *  Tiny model-side RGB drift on subject pixels otherwise leaves them at
     *  ~0.97 alpha (faint translucency); pure-background pixels otherwise
     *  sit at ~0.02 (faint ghost). Snapping cleans both ends. */
    private static final double SNAP_OPAQUE = 0.95;
    private static final double SNAP_TRANS  = 0.05;

    private AlphaMatte() {}

    /**
     * Given two model renders of the same scene on pure-white and pure-black
     * backgrounds, recover per-pixel alpha and true subject RGB.
     *
     * <p>Caller is responsible for ensuring the two bitmaps have matching
     * dimensions — this method does not validate. (In practice both callers
     * already check dimensions before invoking, so duplicating the guard
     * here would just hide bugs in their bookkeeping.)
     *
     * @param onWhite render with the locked white background
     * @param onBlack render with the locked black background
     * @return a new {@code ARGB_8888} bitmap with recovered alpha; the
     *         passed-in bitmaps are not recycled or otherwise touched.
     */
    public static Bitmap differenceMatte(Bitmap onWhite, Bitmap onBlack) {
        int w = onWhite.getWidth();
        int h = onWhite.getHeight();
        final double BG_DIST = Math.sqrt(3.0 * 255.0 * 255.0); // = 441.67

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
                // Fully transparent — color is irrelevant; zero it so the
                // downstream encoder doesn't waste a palette slot on a
                // never-visible color.
                r = 0; g = 0; b = 0;
            } else {
                // From the black render: observed = α·subject ⇒
                // subject = observed/α.
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
