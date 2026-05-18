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
 * Async, cached thumbnail loader shared by the keyboard's image surfaces
 * ({@link HistoryPanelView}, the GIF tab in {@link EmojiPanelView}). Replaces
 * the previous pattern of {@code BitmapFactory.decodeFile} on the main thread
 * in {@code getView()}, which was the dominant source of scroll lag in the
 * history panel.
 *
 * <p>Decode runs on a small fixed pool; results are stored in an
 * {@link LruCache} keyed by absolute path, sized in KB so we don't blow up on
 * larger sheets. The cache survives panel teardown — opening the same surface
 * again is instant after the first decode.
 *
 * <p>Each call tags the {@code target} ImageView with the requested file path
 * so an in-flight decode for a recycled view doesn't stomp on a newer request.
 * If the view is rebound to a different file before the bitmap is ready, the
 * stale result is dropped on the main-thread post.
 */
public final class ThumbnailLoader {

    private static final String TAG = "ThumbnailLoader";

    /** Total cache budget in KB. 6 MB holds ~250 96-dp thumbs at ARGB_8888,
     *  comfortably more than the {@code ImageHistory} 100-entry cap. */
    private static final int CACHE_KB = 6 * 1024;

    /** Two background threads — enough to keep visible cells flowing on
     *  scroll without monopolising the device while big GIFs decode. */
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_KB) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    /** Per-ImageView "ownership token". The decoder reads this on the main
     *  thread before applying the bitmap; if the view has since been bound to
     *  a different path (e.g. GridView reused it for a new cell), the result
     *  is dropped instead of flashing the wrong image. WeakHashMap so the
     *  map doesn't pin GridView's view pool. */
    private static final Map<ImageView, String> TOKENS = new WeakHashMap<>();

    private ThumbnailLoader() {}

    /**
     * Load a thumbnail for {@code file} into {@code target}, decoded async at
     * roughly {@code reqSidePx} on the longest side.
     *
     * <p>If the bitmap is in cache it's applied synchronously and the method
     * returns immediately. Otherwise the target's drawable is cleared (to
     * avoid the previous tile flashing through) and the decode is dispatched.
     *
     * @param reqSidePx the target display side in pixels — we pick the
     *                  smallest {@code inSampleSize} that keeps each dimension
     *                  at least {@code reqSidePx}. Smaller = faster decode +
     *                  less memory, at the cost of resampling artifacts.
     */
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
                // Only apply if this view is still bound to the same file —
                // GridView may have recycled it for a different cell.
                if (key.equals(TOKENS.get(target))) {
                    target.setImageBitmap(bm);
                }
            });
        });
    }

    /** Two-pass decode: first read bounds, then pick a power-of-two
     *  {@code inSampleSize} large enough to undershoot {@code reqSidePx}.
     *  Returns null on decode failure. */
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
