package com.prince.turtlekeyboard.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTML to bitmap renderer that hosts the WebView inside a real window. Use from an
 * Activity by passing an existing FrameLayout as host. Window attachment is required;
 * unattached WebViews (see {@link HtmlImageRenderer}) draw blank from the IME process.
 */
public final class AttachedHtmlRenderer {

    private static final String TAG = "AttachedHtmlRenderer";
    private static final int OUTPUT_SIZE_PX = 500;
    private static final long DRAW_DELAY_MS = 32L;

    private static final AtomicInteger TRACE_SEQ = new AtomicInteger(0);

    private static final class Trace {
        final int id;
        final long start = SystemClock.uptimeMillis();
        long lastMark = start;
        Trace(int id) { this.id = id; }
        long total() { return SystemClock.uptimeMillis() - start; }
        void mark(String phase) {
            long now = SystemClock.uptimeMillis();
            long sinceStart = now - start;
            long sinceLast = now - lastMark;
            lastMark = now;
            Log.d(TAG, "[r" + id + " +" + sinceStart + "ms Δ" + sinceLast + "ms] " + phase);
        }
        void mark(String phase, String extra) { mark(phase + " | " + extra); }
    }

    public interface Callback {
        void onRendered(Bitmap bitmap);
        void onError(String message);
    }

    private AttachedHtmlRenderer() {}

    public static void render(Context ctx, ViewGroup host, String htmlFragment, Callback cb) {
        Trace t = new Trace(TRACE_SEQ.incrementAndGet());
        int htmlLen = htmlFragment == null ? 0 : htmlFragment.length();
        t.mark("render() entry", "htmlChars=" + htmlLen + " host=" + host.getClass().getSimpleName());

        // Render at OUTPUT_SIZE × density so CSS px ≈ device px, then downscale.
        float density = ctx.getResources().getDisplayMetrics().density;
        final int captureSize = Math.max(OUTPUT_SIZE_PX, Math.round(OUTPUT_SIZE_PX * density));

        WebView webView = new WebView(ctx);
        t.mark("WebView constructed", "captureSize=" + captureSize + " density=" + density);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        webView.setBackgroundColor(Color.WHITE);
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        // Offscreen via negative margin so the host's measured size isn't affected
        // (expanding the IME's SoftInputWindow would resize the visible keyboard).
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(captureSize, captureSize);
        lp.leftMargin = -captureSize;
        lp.topMargin = -captureSize;
        webView.setVisibility(View.INVISIBLE);
        host.addView(webView, lp);
        t.mark("WebView attached to host (offscreen)");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                t.mark("onPageFinished",
                        "contentH=" + view.getContentHeight()
                                + " w=" + view.getWidth() + " h=" + view.getHeight());
                view.postDelayed(() -> {
                    t.mark("post-onPageFinished delay elapsed (" + DRAW_DELAY_MS + "ms target)");
                    try {
                        Bitmap bmp = capture(view, captureSize, t);
                        t.mark("DONE", "bitmap=" + bmp.getWidth() + "x" + bmp.getHeight()
                                + " totalElapsed=" + t.total() + "ms");
                        cb.onRendered(bmp);
                    } catch (Throwable th) {
                        Log.w(TAG, "[r" + t.id + "] capture failed at " + t.total() + "ms", th);
                        cb.onError(th.getMessage() == null ? th.toString() : th.getMessage());
                    } finally {
                        host.removeView(view);
                        view.destroy();
                    }
                }, DRAW_DELAY_MS);
            }
        });
        webView.loadDataWithBaseURL(null, wrap(htmlFragment), "text/html", "utf-8", null);
        t.mark("loadDataWithBaseURL returned (now waiting for onPageFinished)");
    }

    private static Bitmap capture(WebView view, int captureSize, Trace t) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(captureSize, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(captureSize, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, captureSize, captureSize);
        t.mark("measure+layout done", captureSize + "x" + captureSize);

        Bitmap full = Bitmap.createBitmap(captureSize, captureSize, Bitmap.Config.ARGB_8888);
        t.mark("full bitmap allocated", "bytes=" + full.getByteCount());

        Canvas fullCanvas = new Canvas(full);
        fullCanvas.drawColor(Color.WHITE);
        view.draw(fullCanvas);
        t.mark("view.draw(canvas) done");

        if (captureSize == OUTPUT_SIZE_PX) return full;

        Bitmap out = Bitmap.createBitmap(OUTPUT_SIZE_PX, OUTPUT_SIZE_PX, Bitmap.Config.ARGB_8888);
        Canvas dst = new Canvas(out);
        dst.drawColor(Color.WHITE);
        dst.drawBitmap(full,
                new Rect(0, 0, captureSize, captureSize),
                new Rect(0, 0, OUTPUT_SIZE_PX, OUTPUT_SIZE_PX),
                null);
        full.recycle();
        t.mark("downscaled " + captureSize + "→" + OUTPUT_SIZE_PX);
        return out;
    }

    private static String wrap(String inner) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>"
                + "*{box-sizing:border-box;}"
                + "html,body{height:100%;width:100%;margin:0;}"
                + "body{padding:20px;font-family:-apple-system,Roboto,'Helvetica Neue',sans-serif;"
                +   "font-size:18px;line-height:1.35;color:#0c0c0c;background:#f4efe4;"
                +   "display:flex;flex-direction:column;justify-content:center;gap:10px;}"
                + "h1{font-size:26px;margin:0;font-weight:800;}"
                + "h2{font-size:22px;margin:0;font-weight:700;}"
                + "h3{font-size:20px;margin:0;font-weight:700;}"
                + "p{margin:0;}"
                + "b,strong{font-weight:700;}"
                + "em{font-style:italic;}"
                + "table{border-collapse:collapse;width:100%;box-shadow:3px 3px 0 #0c0c0c;}"
                + "th,td{border:2px solid #0c0c0c;padding:10px 12px;text-align:left;"
                +   "vertical-align:middle;font-size:17px;}"
                + "th{background:#15803d;color:#fff;font-weight:700;}"
                + "tr:nth-child(even) td{background:#fffaf0;}"
                + "tfoot td{background:#0c0c0c;color:#fff;font-weight:700;}"
                + "ul,ol{margin:0;padding:0 0 0 22px;}"
                + "li{margin:4px 0;}"
                + "ul.checklist{list-style:none;padding:0;}"
                + "ul.checklist li{padding-left:28px;position:relative;}"
                + "ul.checklist li::before{content:'✓';position:absolute;left:0;top:0;"
                +   "color:#15803d;font-weight:700;font-size:20px;}"
                + ".grid{display:grid;gap:8px;grid-template-columns:1fr 1fr;}"
                + ".grid.cols-3{grid-template-columns:1fr 1fr 1fr;}"
                + ".card{border:2px solid #0c0c0c;background:#fff;padding:12px;"
                +   "box-shadow:3px 3px 0 #0c0c0c;}"
                + ".card .title{font-size:14px;font-weight:700;text-transform:uppercase;"
                +   "letter-spacing:0.04em;color:#0c0c0c;margin-bottom:4px;}"
                + ".card .body{font-size:16px;}"
                + ".stat{text-align:center;padding:8px;}"
                + ".stat .num{font-size:64px;font-weight:800;line-height:1;color:#15803d;}"
                + ".stat .label{font-size:16px;text-transform:uppercase;letter-spacing:0.05em;"
                +   "margin-top:6px;}"
                + "dl{display:grid;grid-template-columns:auto 1fr;gap:6px 14px;margin:0;}"
                + "dt{font-weight:700;color:#0c0c0c;}"
                + "dd{margin:0;text-align:right;}"
                + ".callout{border:2px solid #0c0c0c;background:#fffaf0;padding:12px 14px;"
                +   "border-left:8px solid #ff7a1a;}"
                + ".badge{display:inline-block;padding:2px 8px;border:2px solid #0c0c0c;"
                +   "background:#5b6cff;color:#fff;font-size:14px;font-weight:700;"
                +   "border-radius:999px;}"
                + ".badge.green{background:#15803d;}"
                + ".badge.pink{background:#ff4fa3;}"
                + "</style></head><body>" + inner + "</body></html>";
    }
}
