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
 * Generic sheet host for WebView-based games. One class per game-type would be
 * boilerplate — instead, integrations register {@code WebGameSheetView::new} for their
 * route key and the game's identity is read from {@link SheetContext#routeKey()} at
 * mount time. The JS bundle is loaded from the APK's bundled assets at
 * {@code file:///android_asset/games/<routeKey>/index.html}; the bundle is produced
 * by the {@code games/} workspace at the repo root, with everything (HTML + CSS + JS
 * + bridge shim) inlined into a single self-contained HTML file by
 * {@code vite-plugin-singlefile}.
 *
 * <p>Companion: {@link GameBridge}. Exposed on JS side as {@code window.TurtleGame_native}
 * via {@link WebView#addJavascriptInterface}. The shim that comes inlined with each
 * game's HTML promotes the raw native interface into a Promise-based
 * {@code window.TurtleGame} surface — same shim is reused on iOS (over
 * {@code WKScriptMessageHandler}) so games stay portable.
 *
 * <p>Because the WebView loads a {@code file://} URL with no query string, the
 * artifact id is delivered to JS via {@link GameBridge#artifactId()} instead of a
 * URL search param.
 *
 * <p>Backend choice is Firestore. Games needing a different backend (puzzle uses
 * RTDB) will need a sibling sheet view or backend dispatch inside the bridge.
 */
public class WebGameSheetView implements SheetView {

    /** Path under {@code app/src/main/assets/} where the games workspace deposits
     *  built HTML files via the {@code copyGamesHtml} Gradle task. */
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
        // file:// origin needs file access for the inlined module scripts to run.
        // The WebView only ever loads file:///android_asset/games/* (URLs are
        // built from a route key registered by an integration, not user input),
        // so the attack surface stays controlled.
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowContentAccess(false);

        bridge = new GameBridge(webView, sheet.routeKey(), sheet.artifactId());
        // addJavascriptInterface must be called before loadUrl. Interface name
        // intentionally `_native` — the shim that JS games ship wraps it into
        // the canonical `window.TurtleGame` surface.
        webView.addJavascriptInterface(bridge, "TurtleGame_native");

        // Route key like "wyr" → file:///android_asset/games/wyr/index.html.
        // No URL encoding — route keys are ASCII identifiers controlled by
        // integration code, never user input.
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
            // loadUrl("about:blank") + destroy() is the documented WebView teardown —
            // skipping either leaks JS contexts and any in-flight network.
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
    }
}
