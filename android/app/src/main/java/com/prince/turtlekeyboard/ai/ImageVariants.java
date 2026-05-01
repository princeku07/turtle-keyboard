package com.prince.turtlekeyboard.ai;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Re-encodes a source PNG into the format the user picked from the share-type row.
 * Each variant returns a fresh file under {@code shared_images/} so the FileProvider
 * URI mime is derived purely from the extension.
 */
public final class ImageVariants {

    public enum Type { IMAGE, STICKER, GIF }

    public static class Variant {
        public final File file;
        public final String mime;
        public Variant(File f, String m) { this.file = f; this.mime = m; }
    }

    private static final int STICKER_PX = 512;

    private ImageVariants() {}

    public static Variant make(File source, Type type, File outDir) throws Exception {
        if (!outDir.exists() && !outDir.mkdirs()) throw new Exception("cache dir unavailable");
        switch (type) {
            case IMAGE: return new Variant(source, "image/png");
            case STICKER: return new Variant(asSticker(source, outDir), "image/png");
            case GIF: return new Variant(asGif(source, outDir), "image/gif");
        }
        throw new IllegalArgumentException("unknown share type: " + type);
    }

    /** Square 512×512 PNG, source centered with white padding. Acceptable as a sticker
     *  in most messengers that take {@code image/png}. */
    private static File asSticker(File source, File outDir) throws Exception {
        Bitmap src = BitmapFactory.decodeFile(source.getAbsolutePath());
        if (src == null) throw new Exception("decode failed");
        Bitmap canvas = Bitmap.createBitmap(STICKER_PX, STICKER_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(canvas);
        c.drawColor(Color.WHITE);

        float scale = Math.min((float) STICKER_PX / src.getWidth(),
                               (float) STICKER_PX / src.getHeight());
        float drawW = src.getWidth() * scale;
        float drawH = src.getHeight() * scale;
        float left = (STICKER_PX - drawW) / 2f;
        float top = (STICKER_PX - drawH) / 2f;
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(src, null, new RectF(left, top, left + drawW, top + drawH), paint);

        File out = new File(outDir, "sticker_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            canvas.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        src.recycle();
        canvas.recycle();
        return out;
    }

    /** Single-frame static GIF89a built via the embedded {@link GifEncoder}. */
    private static File asGif(File source, File outDir) throws Exception {
        Bitmap src = BitmapFactory.decodeFile(source.getAbsolutePath());
        if (src == null) throw new Exception("decode failed");
        File out = new File(outDir, "anim_" + System.currentTimeMillis() + ".gif");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            GifEncoder.encode(src, fos);
        }
        src.recycle();
        return out;
    }
}
