package com.prince.notion;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;

import com.prince.notion.ui.NotionConnectActivity;
import com.prince.kbd.core.CommandSpec;
import com.prince.kbd.core.IntegrationContext;
import com.prince.kbd.core.IntegrationSession;
import com.prince.kbd.core.KeyValueStore;
import com.prince.kbd.core.KeyboardIntegration;
import com.prince.kbd.core.LlmService;

import org.json.JSONArray;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pluggable Notion integration. Contributes the {@code /notion} slash command (and
 * {@code /note} as a friendly alias). Behavior is fire-and-forget:
 *
 * <ol>
 *   <li>User taps Go on {@code /notion <prompt>}.</li>
 *   <li>Composer closes, banner flashes "Creating Notion page…" for ~1.2s.</li>
 *   <li>Background: LLM structures the prompt → Notion API creates the page.</li>
 *   <li>System notification surfaces the result (tap to open, "Copy link" action).</li>
 * </ol>
 *
 * <p>If the user hasn't completed OAuth + parent picking, the handler shows a banner
 * pointing them to the host app's "Connect Notion" screen and exits without firing.
 */
public final class NotionIntegration implements KeyboardIntegration {

    /** Apps where a "send to Notion" action feels most natural — drives Quick Panel
     *  ranking, never gates command availability. */
    private static final Set<String> AFFINITY = new HashSet<>(Arrays.asList(
            "com.Slack",
            "com.whatsapp",
            "com.notion.id",
            "com.google.android.gm",
            "org.telegram.messenger",
            "com.discord",
            "com.android.chrome"));

    @Override public String id() { return "notion"; }

    @Override
    @Nullable
    public IntegrationSession activate(EditorInfo info, IntegrationContext ctx) {
        // No per-input chip today — Notion is action-driven via slash, not contextual.
        // Returning null leaves any other integration (e.g. Split) free to claim the
        // session in its own host apps.
        return null;
    }

    @Override
    public List<CommandSpec> commands() {
        return Arrays.asList(
                new CommandSpec("notion", "Notion page", "📓", true, this::handleNotion, AFFINITY),
                new CommandSpec("note",   "Notion page", "📓", true, this::handleNotion, AFFINITY));
    }

    private void handleNotion(String prompt, IntegrationContext ctx) {
        KeyValueStore store = ctx.store("notion");
        NotionAuth auth = new NotionAuth(store);
        if (!auth.isSignedIn()) {
            ctx.showBanner("Connect Notion in the Turtle app", 1800L);
            ctx.openScreen("notion-connect");
            return;
        }
        String parent = store.getString(NotionKeys.DEFAULT_PARENT, "");
        if (parent == null || parent.isEmpty()) {
            ctx.showBanner("Pick a Notion parent page in the Turtle app", 1800L);
            ctx.openScreen("notion-connect");
            return;
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            ctx.showBanner("Type something after /notion", 1500L);
            return;
        }

        // Fire-and-forget. UI thread sees only the brief banner; the LLM + API work
        // runs off the IME thread, and the result lands as a system notification.
        ctx.showBanner("📓 Creating Notion page…", 1200L);

        final Context appContext = ctx.appContext();
        final String token = auth.accessToken();
        final String userPrompt = prompt;
        final LlmService llm = ctx.llm();

        NotionLlmBridge.structure(userPrompt, llm, new NotionLlmBridge.Callback() {
            @Override public void onStructured(String title, JSONArray blocks) {
                new NotionClient(token).createPage(parent, title, blocks, new NotionClient.PageCallback() {
                    @Override public void onSuccess(String pageId, String pageUrl) {
                        post(() -> NotionResultNotifier.notifySuccess(
                                appContext, title, pageUrl == null ? canonicalUrl(pageId) : pageUrl));
                    }
                    @Override public void onError(String reason) {
                        post(() -> NotionResultNotifier.notifyError(appContext, userPrompt, reason));
                    }
                });
            }
            @Override public void onError(String reason) {
                post(() -> NotionResultNotifier.notifyError(appContext, userPrompt, "LLM: " + reason));
            }
        });
    }

    private static String canonicalUrl(String pageId) {
        // Notion's web URL has the dashes stripped from the id.
        if (pageId == null) return "https://www.notion.so";
        return "https://www.notion.so/" + pageId.replace("-", "");
    }

    private static void post(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
