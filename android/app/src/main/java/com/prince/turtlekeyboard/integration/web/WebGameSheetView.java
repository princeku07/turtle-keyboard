package com.prince.turtlekeyboard.integration.web;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.Nullable;

import com.prince.kbd.core.SheetContext;
import com.prince.kbd.core.SheetView;

/**
 * Generic sheet host for WebView-based games. Integrations register
 * {@code WebGameSheetView::new} for their route key; the game identity is read from
 * {@link SheetContext#routeKey()} at mount time and the HTML is loaded from
 * {@code file:///android_asset/games/<routeKey>/index.html}.
 *
 * <p>Companion: {@link GameBridge}, exposed to JS as {@code window.TurtleGame_native}.
 */
public class WebGameSheetView implements SheetView {

    private static final String ASSET_BASE = "file:///android_asset/games/";

    @Nullable private WebView webView;
    @Nullable private GameBridge bridge;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    public View buildView(SheetContext sheet) {
        Context ctx = sheet.androidContext();
        webView = new WebView(ctx);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // file:// origin needs file access for inlined module scripts; URLs are built
        // from integration-controlled route keys, never user input.
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowContentAccess(false);

        bridge = new GameBridge(webView, sheet.routeKey(), sheet.artifactId());
        // addJavascriptInterface must be called before loadUrl.
        webView.addJavascriptInterface(bridge, "TurtleGame_native");

        webView.loadUrl(ASSET_BASE + sheet.routeKey() + "/index.html");
        return webView;
    }

    @Override
    public void onDismiss() {
        if (bridge != null) {
            bridge.cancel();
            bridge = null;
        }
        if (webView != null) {
            // loadUrl("about:blank") + destroy() is the documented WebView teardown.
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
    }
}
