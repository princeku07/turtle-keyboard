package com.prince.turtlekeyboard.integration.poll;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;

import com.prince.kbd.core.AssetPrompts;
import com.prince.kbd.core.CommandSpec;
import com.prince.kbd.core.GeminiService;
import com.prince.kbd.core.IntegrationContext;
import com.prince.kbd.core.IntegrationSession;
import com.prince.kbd.core.KeyboardIntegration;
import com.prince.kbd.core.SheetViewFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Poll integration — owns the full {@code /poll} flow end-to-end. The local handler:
 *
 * <ol>
 *   <li>Loads {@code assets/prompts/poll.txt} via {@link AssetPrompts}.</li>
 *   <li>Calls {@link GeminiService#text} to shape the user's terse prompt into a
 *       {@code {question, options}} JSON object.</li>
 *   <li>Parses + validates, then POSTs to the {@code turtle-worker} via
 *       {@link PollClient#createPoll}.</li>
 *   <li>Commits the returned shareable HTTPS App Link URL into the host field.</li>
 * </ol>
 *
 * <p>No round trip through {@code LmStudioAiClient}. New AI features should follow this
 * shape: own your prompt, own your dispatch, call {@code ctx.ai()} directly.
 */
public class PollIntegration implements KeyboardIntegration {

    private static final String TAG = "PollIntegration";

    /** URL route key — matches {@code https://www.turtlekeyboard.com/poll/<id>}. */
    public static final String ROUTE_KEY = "poll";

    private static final long BUSY_BANNER_MS = 30_000L; // long enough to last through gen + Worker POST
    private static final long FAIL_BANNER_MS = 2_500L;
    private static final long EMPTY_BANNER_MS = 2_200L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public String id() { return "poll"; }

    @Override
    @Nullable
    public IntegrationSession activate(EditorInfo info, IntegrationContext ctx) {
        return null;
    }

    @Override
    public List<CommandSpec> commands() {
        return Collections.singletonList(
                new CommandSpec("poll", "Poll", "📊", true, this::handlePoll)
        );
    }

    @Override
    public Map<String, SheetViewFactory> sheetRoutes() {
        return Collections.singletonMap(ROUTE_KEY, PollSheetView::new);
    }

    // -- handler -------------------------------------------------------------

    private void handlePoll(String prompt, IntegrationContext ctx) {
        final String trimmed = prompt == null ? "" : prompt.trim();
        if (trimmed.isEmpty()) {
            ctx.showBanner("What's the poll? e.g. /poll best dinner spot", EMPTY_BANNER_MS);
            return;
        }
        String systemPrompt = AssetPrompts.load(ctx.appContext(), "poll");
        if (systemPrompt.isEmpty()) {
            // Build-time copy of commands/prompts/poll.txt → assets/prompts/poll.txt
            // didn't happen. Clean rebuild fixes it; surface so it doesn't look like a
            // model error.
            ctx.showBanner("Poll prompt missing — clean rebuild needed", FAIL_BANNER_MS);
            return;
        }
        ctx.showBanner("Creating poll…", BUSY_BANNER_MS);
        ctx.ai().text(systemPrompt, trimmed, new GeminiService.TextCallback() {
            @Override public void onText(String text) { onModelText(ctx, text); }
            @Override public void onError(String reason) {
                ctx.showBanner("Poll failed: " + reason, FAIL_BANNER_MS);
            }
        });
    }

    /** Main thread. Parses Gemini's output, dispatches the Worker POST on the IO executor. */
    private void onModelText(IntegrationContext ctx, String rawJson) {
        String stripped = stripCodeFences(rawJson);
        JSONObject parsed;
        try {
            parsed = new JSONObject(stripped);
        } catch (Exception e) {
            Log.w(TAG, "poll JSON parse failed; raw=" + stripped, e);
            ctx.showBanner("Couldn't shape that into a poll — try a clearer prompt", FAIL_BANNER_MS);
            return;
        }
        final String question = parsed.optString("question", "").trim();
        JSONArray optsArr = parsed.optJSONArray("options");
        if (question.isEmpty() || optsArr == null || optsArr.length() < 2) {
            ctx.showBanner("Couldn't shape that into a poll — try a clearer prompt", FAIL_BANNER_MS);
            return;
        }
        final List<String> options = new ArrayList<>(optsArr.length());
        for (int i = 0; i < optsArr.length(); i++) {
            String label = optsArr.optString(i, "").trim();
            if (!label.isEmpty()) options.add(label);
        }
        if (options.size() < 2) {
            ctx.showBanner("Couldn't shape that into a poll — try a clearer prompt", FAIL_BANNER_MS);
            return;
        }
        io.execute(() -> {
            try {
                PollClient.CreateResult result = PollClient.createPoll(question, options);
                main.post(() -> ctx.commitText(result.url));
            } catch (IOException e) {
                Log.w(TAG, "PollClient.createPoll failed", e);
                final String msg = e.getMessage() == null ? "network error" : e.getMessage();
                main.post(() -> ctx.showBanner("Poll create failed: " + msg, FAIL_BANNER_MS));
            }
        });
    }

    /** Even when told not to, models occasionally wrap JSON in ```json … ```. */
    private static String stripCodeFences(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
            int closing = t.lastIndexOf("```");
            if (closing >= 0) t = t.substring(0, closing);
        }
        return t.trim();
    }
}
