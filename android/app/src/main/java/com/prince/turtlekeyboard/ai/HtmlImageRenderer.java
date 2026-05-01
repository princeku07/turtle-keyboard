package com.prince.turtlekeyboard.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Renders an HTML snippet to a PNG. Returns the absolute file path AND a FileProvider
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
    private static final int RENDER_WIDTH_PX = 1080;
    private static final int MAX_HEIGHT_PX = 4096;
    private static final long DRAW_DELAY_MS = 250L;

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
        Log.d(TAG, "render() called, html=" + (htmlFragment == null ? 0 : htmlFragment.length()) + " chars");
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                renderOnMain(ctx, htmlFragment, callback);
            } catch (Throwable t) {
                Log.w(TAG, "render setup failed", t);
                callback.onError("Render failed: " + t.getMessage());
            }
        });
    }

    private static void renderOnMain(Context ctx, String html, Callback cb) {
        Log.d(TAG, "renderOnMain: creating WebView (ctx=" + ctx.getClass().getSimpleName() + ")");
        WebView webView = new WebView(ctx);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        webView.setBackgroundColor(Color.WHITE);
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "onPageFinished url=" + url
                        + " contentHeight=" + view.getContentHeight()
                        + " width=" + view.getWidth() + " height=" + view.getHeight());
                view.postDelayed(() -> {
                    try {
                        Result r = capture(ctx, view);
                        Log.d(TAG, "capture OK: file=" + r.file.getAbsolutePath()
                                + " size=" + r.file.length() + "B uri=" + r.uri);
                        cb.onRendered(r);
                    } catch (Throwable t) {
                        Log.w(TAG, "capture failed", t);
                        cb.onError("Render failed: " + t.getMessage());
                    } finally {
                        view.destroy();
                    }
                }, DRAW_DELAY_MS);
            }
        });
        Log.d(TAG, "loadDataWithBaseURL invoked");
        webView.loadDataWithBaseURL(null, wrap(html), "text/html", "utf-8", null);
    }

    private static Result capture(Context ctx, WebView view) throws Exception {
        Log.d(TAG, "capture() measuring at width=" + RENDER_WIDTH_PX);
        view.measure(
                View.MeasureSpec.makeMeasureSpec(RENDER_WIDTH_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int measured = view.getMeasuredHeight();
        int contentH = view.getContentHeight();
        Log.d(TAG, "measured=" + measured + " contentHeight(css)=" + contentH);
        int height = Math.max(measured, 400);
        height = Math.min(height, MAX_HEIGHT_PX);
        view.layout(0, 0, RENDER_WIDTH_PX, height);
        Log.d(TAG, "layout done at " + RENDER_WIDTH_PX + "x" + height
                + " (post-layout w=" + view.getWidth() + " h=" + view.getHeight() + ")");

        Bitmap bitmap = Bitmap.createBitmap(RENDER_WIDTH_PX, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        view.draw(canvas);
        Log.d(TAG, "drew WebView into " + RENDER_WIDTH_PX + "x" + height + " bitmap; sampling pixels...");
        // Sample a few pixels around the table-header area (top portion) to see if we got
        // anything but white. If ALL samples are white (0xFFFFFFFF), the WebView produced
        // a blank canvas — almost certainly a window-attachment / hardware-layer problem.
        int sampleY = Math.min(120, height - 1);
        boolean anyNonWhite = false;
        for (int x = 60; x < RENDER_WIDTH_PX && !anyNonWhite; x += 40) {
            int p = bitmap.getPixel(x, sampleY) & 0xFFFFFF;
            if (p != 0xFFFFFF) anyNonWhite = true;
        }
        Log.d(TAG, "pixel-sample row y=" + sampleY + " non-white=" + anyNonWhite);

        File dir = new File(ctx.getCacheDir(), "shared_images");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache dir unavailable");
        File png = new File(dir, "org_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(png)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", png);
        return new Result(png, uri);
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
