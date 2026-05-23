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
import com.prince.kbd.core.GeminiService;

import org.json.JSONArray;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Notion integration. Contributes {@code /notion} (and {@code /note} alias): structures
 * the prompt with the LLM, creates a Notion page under the user's chosen parent, and
 * surfaces the result via system notification.
 */
public final class NotionIntegration implements KeyboardIntegration {

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
        // Slash-driven, not contextual — no per-input chip.
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

        ctx.showBanner("📓 Creating Notion page…", 1200L);

        final Context appContext = ctx.appContext();
        final String token = auth.accessToken();
        final String userPrompt = prompt;
        final GeminiService llm = ctx.ai();

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
        // Notion web URLs use the dash-stripped id.
        if (pageId == null) return "https://www.notion.so";
        return "https://www.notion.so/" + pageId.replace("-", "");
    }

    private static void post(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
