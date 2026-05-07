package com.prince.split.ui;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tiny wrapper over ZXing core for rendering QR codes to {@link Bitmap}s. Avoids the
 * heavier {@code zxing-android-embedded} library since we only need rendering, not
 * scanning (the OS camera handles that).
 */
final class QrRenderer {

    private QrRenderer() {}

    /**
     * Renders {@code text} as a square QR bitmap of {@code size} px on each side.
     * Returns {@code null} if encoding fails (extremely rare for short URLs).
     */
    static Bitmap render(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1); // small quiet zone
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int off = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[off + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        } catch (WriterException e) {
            return null;
        }
    }
}
