package com.prince.turtlekeyboard.integration.gif;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Slices a sprite-sheet bitmap into {@code cols × rows} equal cells, returned in
 * row-major order. Cell size is floored when sheet dimensions aren't exact multiples,
 * so trailing edge pixels are dropped rather than producing unequal cells.
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
