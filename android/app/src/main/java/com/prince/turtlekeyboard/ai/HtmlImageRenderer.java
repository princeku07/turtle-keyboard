package com.prince.turtlekeyboard.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renders an HTML snippet to a WebP. Returns the absolute file path AND a FileProvider
 * URI: the path is what the in-keyboard preview decodes (cheap, no permission paths),
 * the URI is what {@code commitContent()} / clipboard need.
 *
 * <p>Two non-obvious bits:
 * <ul>
 *   <li>WebView is forced to LAYER_TYPE_SOFTWARE before drawing. Hardware-accelerated
 *       WebViews drawn via {@code view.draw(canvas)} into an offscreen bitmap come out
 *       blank on most devices, particularly inside an IME process.</li>
 *   <li>We post twice — first {@code DRAW_DELAY_MS} after onPageFinished to let layout
 *       settle, then a second microtask so the software layer is in place before drawing.</li>
 * </ul>
 */
public final class HtmlImageRenderer {

    private static final String TAG = "HtmlImageRenderer";
    private static final int RENDER_WIDTH_PX = 720;
    private static final int MAX_HEIGHT_PX = 4096;
    private static final long DRAW_DELAY_MS = 32L;
    private static final int WEBP_QUALITY = 85;

    private static final ExecutorService ENCODE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicInteger TRACE_SEQ = new AtomicInteger(0);

    /** Per-render breadcrumb tracker. All times in ms since {@link #start}. */
    private static final class Trace {
        final int id;
        final long start = SystemClock.uptimeMillis();
        long lastMark = start;
        Trace(int id) { this.id = id; }
        /** Total ms since render() entry. */
        long total() { return SystemClock.uptimeMillis() - start; }
        /** Logs phase name + ms-since-start AND ms-since-previous-mark, then resets the lap timer. */
        void mark(String phase) {
            long now = SystemClock.uptimeMillis();
            long sinceStart = now - start;
            long sinceLast = now - lastMark;
            lastMark = now;
            Log.d(TAG, "[r" + id + " +" + sinceStart + "ms Δ" + sinceLast + "ms] " + phase);
        }
        void mark(String phase, String extra) { mark(phase + " | " + extra); }
    }

    public static class Result {
        public final File file;
        public final Uri uri;
        public Result(File f, Uri u) { this.file = f; this.uri = u; }
    }

    public interface Callback {
        void onRendered(Result result);
        void onError(String message);
    }

    private HtmlImageRenderer() {}

    public static void render(Context ctx, String htmlFragment, Callback callback) {
        Trace t = new Trace(TRACE_SEQ.incrementAndGet());
        int htmlLen = htmlFragment == null ? 0 : htmlFragment.length();
        boolean onMain = Looper.myLooper() == Looper.getMainLooper();
        t.mark("render() entry", "htmlChars=" + htmlLen + " onMain=" + onMain);
        new Handler(Looper.getMainLooper()).post(() -> {
            t.mark("UI thread reached");
            try {
                renderOnMain(ctx, htmlFragment, callback, t);
            } catch (Throwable th) {
                Log.w(TAG, "[r" + t.id + "] render setup failed after " + t.total() + "ms", th);
                callback.onError("Render failed: " + th.getMessage());
            }
        });
    }

    private static void renderOnMain(Context ctx, String html, Callback cb, Trace t) {
        WebView webView = new WebView(ctx);
        t.mark("WebView constructed", "ctx=" + ctx.getClass().getSimpleName());
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        webView.setBackgroundColor(Color.WHITE);
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        t.mark("WebView configured (software layer)");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                t.mark("onPageFinished",
                        "contentH=" + view.getContentHeight()
                                + " w=" + view.getWidth() + " h=" + view.getHeight());
                view.postDelayed(() -> {
                    t.mark("post-onPageFinished delay elapsed (" + DRAW_DELAY_MS + "ms target)");
                    Bitmap bitmap;
                    try {
                        bitmap = drawToBitmap(view, t);
                    } catch (Throwable th) {
                        Log.w(TAG, "[r" + t.id + "] draw failed at " + t.total() + "ms", th);
                        cb.onError("Render failed: " + th.getMessage());
                        view.destroy();
                        return;
                    }
                    view.destroy();
                    t.mark("WebView destroyed; handing off to encode executor");
                    final int bmpW = bitmap.getWidth(), bmpH = bitmap.getHeight();
                    ENCODE_EXECUTOR.execute(() -> {
                        t.mark("encode executor picked up", "bitmap=" + bmpW + "x" + bmpH);
                        try {
                            Result r = encodeAndStore(ctx, bitmap, t);
                            t.mark("DONE", "file=" + r.file.getName()
                                    + " size=" + r.file.length() + "B totalElapsed=" + t.total() + "ms");
                            cb.onRendered(r);
                        } catch (Throwable th) {
                            Log.w(TAG, "[r" + t.id + "] encode failed at " + t.total() + "ms", th);
                            cb.onError("Render failed: " + th.getMessage());
                        }
                    });
                }, DRAW_DELAY_MS);
            }
        });
        webView.loadDataWithBaseURL(null, wrap(html), "text/html", "utf-8", null);
        t.mark("loadDataWithBaseURL returned (now waiting for onPageFinished)");
    }

    private static Bitmap drawToBitmap(WebView view, Trace t) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(RENDER_WIDTH_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int measured = view.getMeasuredHeight();
        int height = Math.min(Math.max(measured, 400), MAX_HEIGHT_PX);
        t.mark("measure done", "measured=" + measured + " final=" + RENDER_WIDTH_PX + "x" + height);

        view.layout(0, 0, RENDER_WIDTH_PX, height);
        t.mark("layout done");

        Bitmap bitmap = Bitmap.createBitmap(RENDER_WIDTH_PX, height, Bitmap.Config.ARGB_8888);
        t.mark("bitmap allocated", "bytes=" + bitmap.getByteCount());

        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        view.draw(canvas);
        t.mark("view.draw(canvas) done");
        return bitmap;
    }

    @SuppressWarnings("deprecation")
    private static Result encodeAndStore(Context ctx, Bitmap bitmap, Trace t) throws Exception {
        File dir = new File(ctx.getCacheDir(), "shared_images");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache dir unavailable");
        File out = new File(dir, "org_" + System.currentTimeMillis() + ".webp");
        Bitmap.CompressFormat fmt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? Bitmap.CompressFormat.WEBP_LOSSY
                : Bitmap.CompressFormat.WEBP;
        try (FileOutputStream os = new FileOutputStream(out)) {
            bitmap.compress(fmt, WEBP_QUALITY, os);
        } finally {
            bitmap.recycle();
        }
        t.mark("webp encoded+written", "fmt=" + fmt + " q=" + WEBP_QUALITY + " bytes=" + out.length());
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", out);
        t.mark("FileProvider URI built");
        return new Result(out, uri);
    }

    private static String wrap(String inner) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>"
                + "*{box-sizing:border-box;}"
                + "body{margin:0;padding:32px;font-family:-apple-system,Roboto,'Helvetica Neue',sans-serif;"
                +   "font-size:30px;line-height:1.4;color:#0c0c0c;background:#f4efe4;}"
                + "h1,h2,h3{margin:0 0 16px;}"
                + "table{border-collapse:collapse;width:100%;margin:8px 0;}"
                + "th,td{border:2px solid #0c0c0c;padding:14px 18px;text-align:left;vertical-align:top;}"
                + "th{background:#15803d;color:#fff;font-weight:600;}"
                + "tr:nth-child(even) td{background:#fffaf0;}"
                + "ul,ol{margin:8px 0 8px 28px;padding:0;}"
                + "li{margin:6px 0;}"
                + "</style></head><body>" + inner + "</body></html>";
    }
}
