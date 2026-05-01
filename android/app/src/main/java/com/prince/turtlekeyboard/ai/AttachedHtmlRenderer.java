package com.prince.turtlekeyboard.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * HTML → bitmap renderer that hosts the WebView inside a real window. Use this
 * from an Activity (pass any FrameLayout already in the view tree as host) —
 * window attachment is the missing ingredient that made {@link HtmlImageRenderer}
 * draw blank from the IME process.
 *
 * <p>The host container is expected to be tiny / invisible; we resize it just
 * long enough to lay out the WebView at {@link #RENDER_WIDTH_PX}, capture, then
 * remove the WebView again.
 */
public final class AttachedHtmlRenderer {

    private static final String TAG = "AttachedHtmlRenderer";
    private static final int OUTPUT_SIZE_PX = 500;
    private static final long DRAW_DELAY_MS = 250L;

    public interface Callback {
        void onRendered(Bitmap bitmap);
        void onError(String message);
    }

    private AttachedHtmlRenderer() {}

    public static void render(Context ctx, ViewGroup host, String htmlFragment, Callback cb) {
        Log.d(TAG, "render() html=" + (htmlFragment == null ? 0 : htmlFragment.length())
                + " chars host=" + host.getClass().getSimpleName());
        // Render at OUTPUT_SIZE × density device px so CSS px maps ~1:1 to
        // device px in the laid-out content, then downscale to OUTPUT_SIZE.
        float density = ctx.getResources().getDisplayMetrics().density;
        final int captureSize = Math.max(OUTPUT_SIZE_PX, Math.round(OUTPUT_SIZE_PX * density));

        WebView webView = new WebView(ctx);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        webView.setBackgroundColor(Color.WHITE);
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        // Position the WebView fully offscreen via a negative left margin so
        // the host's measured size isn't affected. In an Activity that's a
        // nicety; in the IME, expanding the host (the SoftInputWindow's decor)
        // would resize the visible keyboard window — must avoid.
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(captureSize, captureSize);
        lp.leftMargin = -captureSize;
        lp.topMargin = -captureSize;
        webView.setVisibility(View.INVISIBLE);
        host.addView(webView, lp);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "onPageFinished contentHeight=" + view.getContentHeight()
                        + " w=" + view.getWidth() + " h=" + view.getHeight());
                view.postDelayed(() -> {
                    try {
                        Bitmap bmp = capture(view, captureSize);
                        cb.onRendered(bmp);
                    } catch (Throwable t) {
                        Log.w(TAG, "capture failed", t);
                        cb.onError(t.getMessage() == null ? t.toString() : t.getMessage());
                    } finally {
                        host.removeView(view);
                        view.destroy();
                    }
                }, DRAW_DELAY_MS);
            }
        });
        webView.loadDataWithBaseURL(null, wrap(htmlFragment), "text/html", "utf-8", null);
    }

    private static Bitmap capture(WebView view, int captureSize) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(captureSize, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(captureSize, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, captureSize, captureSize);
        Log.d(TAG, "layout " + captureSize + "x" + captureSize);

        Bitmap full = Bitmap.createBitmap(captureSize, captureSize, Bitmap.Config.ARGB_8888);
        Canvas fullCanvas = new Canvas(full);
        fullCanvas.drawColor(Color.WHITE);
        view.draw(fullCanvas);

        if (captureSize == OUTPUT_SIZE_PX) return full;

        // Downscale to the exact OUTPUT_SIZE_PX × OUTPUT_SIZE_PX. Bilinear is
        // fine for the typography here.
        Bitmap out = Bitmap.createBitmap(OUTPUT_SIZE_PX, OUTPUT_SIZE_PX, Bitmap.Config.ARGB_8888);
        Canvas dst = new Canvas(out);
        dst.drawColor(Color.WHITE);
        dst.drawBitmap(full,
                new Rect(0, 0, captureSize, captureSize),
                new Rect(0, 0, OUTPUT_SIZE_PX, OUTPUT_SIZE_PX),
                null);
        full.recycle();
        Log.d(TAG, "downscaled " + captureSize + "→" + OUTPUT_SIZE_PX);
        return out;
    }

    private static String wrap(String inner) {
        // Design system: a small, opinionated set of primitives so the model
        // can pick the right structure without inventing inline styles. CSS px
        // ≈ device px in the rendered bitmap because the WebView is sized at
        // OUTPUT_SIZE × density and downscaled afterward. Body is centered so
        // short fragments sit in the middle of the 500×500 frame.
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>"
                + "*{box-sizing:border-box;}"
                + "html,body{height:100%;width:100%;margin:0;}"
                + "body{padding:20px;font-family:-apple-system,Roboto,'Helvetica Neue',sans-serif;"
                +   "font-size:18px;line-height:1.35;color:#0c0c0c;background:#f4efe4;"
                +   "display:flex;flex-direction:column;justify-content:center;gap:10px;}"

                // Headings ---------------------------------------------------
                + "h1{font-size:26px;margin:0;font-weight:800;}"
                + "h2{font-size:22px;margin:0;font-weight:700;}"
                + "h3{font-size:20px;margin:0;font-weight:700;}"
                + "p{margin:0;}"
                + "b,strong{font-weight:700;}"
                + "em{font-style:italic;}"

                // Table ------------------------------------------------------
                + "table{border-collapse:collapse;width:100%;box-shadow:3px 3px 0 #0c0c0c;}"
                + "th,td{border:2px solid #0c0c0c;padding:10px 12px;text-align:left;"
                +   "vertical-align:middle;font-size:17px;}"
                + "th{background:#15803d;color:#fff;font-weight:700;}"
                + "tr:nth-child(even) td{background:#fffaf0;}"
                + "tfoot td{background:#0c0c0c;color:#fff;font-weight:700;}"

                // Lists ------------------------------------------------------
                + "ul,ol{margin:0;padding:0 0 0 22px;}"
                + "li{margin:4px 0;}"
                + "ul.checklist{list-style:none;padding:0;}"
                + "ul.checklist li{padding-left:28px;position:relative;}"
                + "ul.checklist li::before{content:'✓';position:absolute;left:0;top:0;"
                +   "color:#15803d;font-weight:700;font-size:20px;}"

                // Card grid (use .grid > .card) -----------------------------
                + ".grid{display:grid;gap:8px;grid-template-columns:1fr 1fr;}"
                + ".grid.cols-3{grid-template-columns:1fr 1fr 1fr;}"
                + ".card{border:2px solid #0c0c0c;background:#fff;padding:12px;"
                +   "box-shadow:3px 3px 0 #0c0c0c;}"
                + ".card .title{font-size:14px;font-weight:700;text-transform:uppercase;"
                +   "letter-spacing:0.04em;color:#0c0c0c;margin-bottom:4px;}"
                + ".card .body{font-size:16px;}"

                // Big stat — one big number with a label below ---------------
                + ".stat{text-align:center;padding:8px;}"
                + ".stat .num{font-size:64px;font-weight:800;line-height:1;color:#15803d;}"
                + ".stat .label{font-size:16px;text-transform:uppercase;letter-spacing:0.05em;"
                +   "margin-top:6px;}"

                // Key-value list (use <dl><dt>k</dt><dd>v</dd>...</dl>) ------
                + "dl{display:grid;grid-template-columns:auto 1fr;gap:6px 14px;margin:0;}"
                + "dt{font-weight:700;color:#0c0c0c;}"
                + "dd{margin:0;text-align:right;}"

                // Callout / highlighted note --------------------------------
                + ".callout{border:2px solid #0c0c0c;background:#fffaf0;padding:12px 14px;"
                +   "border-left:8px solid #ff7a1a;}"

                // Badge — inline pill (use <span class=\"badge\">) ------------
                + ".badge{display:inline-block;padding:2px 8px;border:2px solid #0c0c0c;"
                +   "background:#5b6cff;color:#fff;font-size:14px;font-weight:700;"
                +   "border-radius:999px;}"
                + ".badge.green{background:#15803d;}"
                + ".badge.pink{background:#ff4fa3;}"

                + "</style></head><body>" + inner + "</body></html>";
    }
}
