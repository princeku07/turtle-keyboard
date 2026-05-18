package com.prince.turtlekeyboard.integration.gif;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Slices a sprite-sheet bitmap into {@code cols × rows} equal cells, returned in
 * row-major order (left-to-right then top-to-bottom). The /gif system prompt locks
 * the model to a 5×2 grid of 256² cells, so the expected call here is
 * {@code slice(sheet, 5, 2)} → 10 frames.
 *
 * <p>If the sheet's dimensions are not exact multiples of {@code cols × rows}, the
 * cell size is the floored division and trailing rows / columns of pixels are
 * dropped. Nano Banana sometimes returns a sheet 1–2 px off the requested size; the
 * floor is intentional so cells stay equal-sized rather than mixing a fat row at
 * the edge.
 */
public final class SpriteSheetSlicer {

    private SpriteSheetSlicer() {}

    public static List<Bitmap> slice(Bitmap sheet, int cols, int rows) {
        if (cols <= 0 || rows <= 0) {
            throw new IllegalArgumentException("cols and rows must be positive");
        }
        int cellW = sheet.getWidth() / cols;
        int cellH = sheet.getHeight() / rows;
        if (cellW <= 0 || cellH <= 0) {
            throw new IllegalArgumentException(
                    "sheet too small: " + sheet.getWidth() + "x" + sheet.getHeight()
                            + " for " + cols + "x" + rows);
        }
        List<Bitmap> frames = new ArrayList<>(cols * rows);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int x = c * cellW;
                int y = r * cellH;
                frames.add(Bitmap.createBitmap(sheet, x, y, cellW, cellH));
            }
        }
        return frames;
    }
}
