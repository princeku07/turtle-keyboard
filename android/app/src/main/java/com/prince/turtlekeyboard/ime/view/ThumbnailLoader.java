package com.prince.turtlekeyboard.ime.view;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async, cached thumbnail loader shared by the keyboard's image surfaces.
 * Decodes happen on a small fixed pool; bitmaps are kept in an LruCache keyed
 * by absolute path. Each target ImageView carries a path token so a stale
 * decode for a recycled view never stomps a newer request.
 */
public final class ThumbnailLoader {

    private static final String TAG = "ThumbnailLoader";

    private static final int CACHE_KB = 6 * 1024;

    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_KB) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    // WeakHashMap so the map doesn't pin GridView's view pool.
    private static final Map<ImageView, String> TOKENS = new WeakHashMap<>();

    private ThumbnailLoader() {}

    public static void load(File file, int reqSidePx, ImageView target) {
        if (file == null || target == null) return;
        final String key = file.getAbsolutePath();
        TOKENS.put(target, key);

        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageBitmap(null);

        IO.execute(() -> {
            Bitmap bm = decodeSampled(file, reqSidePx);
            if (bm == null) {
                Log.w(TAG, "decode failed: " + key);
                return;
            }
            CACHE.put(key, bm);
            MAIN.post(() -> {
                if (key.equals(TOKENS.get(target))) {
                    target.setImageBitmap(bm);
                }
            });
        });
    }

    /** Two-pass decode (bounds, then sampled); returns null on decode failure. */
    private static Bitmap decodeSampled(File file, int reqSidePx) {
        if (!file.exists()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

            int sample = 1;
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w > 0 && h > 0 && reqSidePx > 0) {
                while ((w / (sample * 2)) >= reqSidePx
                        && (h / (sample * 2)) >= reqSidePx) {
                    sample *= 2;
                }
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Throwable t) {
            Log.w(TAG, "decode error " + file.getName(), t);
            return null;
        }
    }
}
