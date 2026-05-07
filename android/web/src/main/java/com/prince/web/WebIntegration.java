package com.prince.web;

import android.net.Uri;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.prince.kbd.core.CommandSpec;
import com.prince.kbd.core.IntegrationContext;
import com.prince.kbd.core.IntegrationSession;
import com.prince.kbd.core.KeyboardIntegration;

import java.util.Arrays;
import java.util.List;

/**
 * Pluggable Web integration. Contributes {@code /web <url-or-query>} — the standard
 * composer prompt UI takes the input, then this handler mounts a full-panel
 * {@link WebViewPanel} above the keys with the URL loaded.
 *
 * <p>No persistence, no AI, no chip — the simplest module shape. A great example of a
 * feature that opts out of every capability the {@link IntegrationContext} exposes.
 */
public final class WebIntegration implements KeyboardIntegration {

    /** Panel height in dp. Roughly matches the visible keyboard area so the WebView
     *  feels full-screen above the keys without occluding the system bars. */
    private static final int PANEL_HEIGHT_DP = 440;

    @Override public String id() { return "web"; }

    @Override
    @Nullable
    public IntegrationSession activate(EditorInfo info, IntegrationContext ctx) {
        return null;
    }

    @Override
    public List<CommandSpec> commands() {
        return Arrays.asList(
                new CommandSpec("web", "Web", "🌐", true, this::handleWeb)
        );
    }

    private void handleWeb(String prompt, IntegrationContext ctx) {
        if (prompt == null || prompt.trim().isEmpty()) {
            ctx.showBanner("Try /web wikipedia.org", 1500L);
            return;
        }
        String url = resolveUrl(prompt.trim());
        WebViewPanel panel = new WebViewPanel(ctx.appContext());
        int hPx = (int) (PANEL_HEIGHT_DP * ctx.appContext().getResources().getDisplayMetrics().density);
        panel.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, hPx));
        panel.setListener(ctx::hidePanel);
        ctx.showPanel(panel);
        panel.load(url);
    }

    /** URL-ish (contains a dot, no spaces) → load directly; otherwise Google search. */
    private static String resolveUrl(String input) {
        boolean looksLikeUrl = input.contains(".") && !input.contains(" ");
        if (looksLikeUrl) {
            return input.startsWith("http://") || input.startsWith("https://")
                    ? input : "https://" + input;
        }
        return "https://www.google.com/search?q=" + Uri.encode(input);
    }
}
