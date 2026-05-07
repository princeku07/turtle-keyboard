package com.prince.web;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.browser.customtabs.CustomTabsIntent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Full-panel WebView host. Layout matches the rest of the keyboard's panels: a thin
 * header (URL on the left, close button on the right), a 2 dp progress strip while
 * loading, then the WebView filling the remainder.
 *
 * <p>The host IME mounts this with whatever LayoutParams it wants — the panel uses
 * {@code MATCH_PARENT} for both axes internally so it fills whatever box it's placed in.
 */
public class WebViewPanel extends LinearLayout {

    public interface Listener {
        void onClose();
    }

    // Match the landing-page palette: ink border, cream surface.
    private static final int CREAM = 0xFFF4EFE4;
    private static final int INK   = 0xFF0C0C0C;
    private static final int MUTED = 0xFF6B6B6B;
    private static final int LIME  = 0xFF15803D;

    private final WebView webView;
    private final TextView urlLabel;
    private final ProgressBar progress;
    @Nullable private Listener listener;
    @Nullable private String currentUrl;

    public WebViewPanel(@NonNull Context context) { this(context, null); }
    public WebViewPanel(@NonNull Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }

    @SuppressLint("SetJavaScriptEnabled")
    public WebViewPanel(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setBackgroundColor(CREAM);
        setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Header: URL + close.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = dp(12);
        header.setPadding(hPad, dp(8), hPad, dp(8));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addView(header, headerLp);

        urlLabel = new TextView(context);
        urlLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        urlLabel.setTextColor(MUTED);
        urlLabel.setSingleLine(true);
        urlLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        urlLabel.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(urlLabel, urlLp);

        // "Open in Chrome" hand-off — Custom Tabs share Chrome's profile (cookies, login,
        // password manager), so a logged-in user lands on the same page already signed
        // in. We can't get that inside the embedded WebView (per-app cookie jar), so
        // this is the escape hatch when the user actually needs their browser session.
        TextView openExternalBtn = new TextView(context);
        openExternalBtn.setText("↗");
        openExternalBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        openExternalBtn.setTextColor(INK);
        openExternalBtn.setPadding(dp(8), 0, dp(8), 0);
        openExternalBtn.setOnClickListener(v -> openInExternalBrowser());
        header.addView(openExternalBtn);

        TextView closeBtn = new TextView(context);
        closeBtn.setText("×");
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        closeBtn.setTextColor(INK);
        closeBtn.setPadding(dp(8), 0, dp(4), 0);
        closeBtn.setOnClickListener(v -> { if (listener != null) listener.onClose(); });
        header.addView(closeBtn);

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(GONE);
        progress.getProgressDrawable().setTint(LIME);
        addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        webView = new WebView(context);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progress.setVisibility(VISIBLE);
                urlLabel.setText(url);
                currentUrl = url;
            }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(GONE);
                urlLabel.setText(url);
                currentUrl = url;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
            }
        });
        addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    public void load(@NonNull String url) {
        urlLabel.setText(url);
        currentUrl = url;
        webView.loadUrl(url);
    }

    private void openInExternalBrowser() {
        if (currentUrl == null || currentUrl.isEmpty()) return;
        try {
            // Custom Tabs reuses Chrome's profile (cookies, autofill) when Chrome is the
            // user's default; falls through to whichever browser is set otherwise.
            CustomTabsIntent intent = new CustomTabsIntent.Builder().build();
            intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.launchUrl(getContext(), Uri.parse(currentUrl));
        } catch (Exception e) {
            // Fallback for devices without a Custom-Tabs-capable browser.
            Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl));
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { getContext().startActivity(fallback); } catch (Exception ignored) {}
        }
    }

    public void setListener(@Nullable Listener listener) { this.listener = listener; }

    @NonNull public WebView getWebView() { return webView; }

    /** Call from the IME's onDestroy / onFinishInputView to release the WebView. */
    public void destroy() {
        removeView(webView);
        webView.destroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
