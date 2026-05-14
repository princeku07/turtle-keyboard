package com.prince.turtlekeyboard.integration.wyr;

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
 * Would-you-rather integration — mirrors {@link com.prince.turtlekeyboard.integration.poll.PollIntegration}
 * shape. Local handler owns the full flow:
 *
 * <ol>
 *   <li>Loads {@code assets/prompts/wyr.txt}.</li>
 *   <li>Calls {@link GeminiService#text} to shape the user's (optional) theme prompt
 *       into 5 dilemma pairs as JSON.</li>
 *   <li>Parses + validates, POSTs to {@link WyrClient#create}.</li>
 *   <li>Commits the returned shareable URL into the host field.</li>
 * </ol>
 *
 * <p>Empty user prompt is allowed — falls back to "any theme" since the system prompt
 * tells the model to vary across categories.
 */
public class WyrIntegration implements KeyboardIntegration {

    private static final String TAG = "WyrIntegration";

    public static final String ROUTE_KEY = "wyr";

    private static final long BUSY_BANNER_MS = 30_000L;
    private static final long FAIL_BANNER_MS = 2_500L;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public String id() { return "wyr"; }

    @Override
    @Nullable
    public IntegrationSession activate(EditorInfo info, IntegrationContext ctx) {
        return null;
    }

    @Override
    public List<CommandSpec> commands() {
        return Collections.singletonList(
                new CommandSpec("wyr", "Would you rather", "🤔", false, this::handleWyr)
        );
    }

    @Override
    public Map<String, SheetViewFactory> sheetRoutes() {
        return Collections.singletonMap(ROUTE_KEY, WyrSheetView::new);
    }

    private void handleWyr(String prompt, IntegrationContext ctx) {
        // /wyr accepts an empty prompt (system prompt covers the no-theme case) but
        // trims any user input as a theme hint.
        final String themeHint = prompt == null ? "" : prompt.trim();
        final String userPrompt = themeHint.isEmpty()
                ? "Generate a varied set."
                : themeHint;

        String systemPrompt = AssetPrompts.load(ctx.appContext(), "wyr");
        if (systemPrompt.isEmpty()) {
            ctx.showBanner("WYR prompt missing — clean rebuild needed", FAIL_BANNER_MS);
            return;
        }
        ctx.showBanner("Creating game…", BUSY_BANNER_MS);
        ctx.ai().text(systemPrompt, userPrompt, new GeminiService.TextCallback() {
            @Override public void onText(String text) { onModelText(ctx, text); }
            @Override public void onError(String reason) {
                ctx.showBanner("Game failed: " + reason, FAIL_BANNER_MS);
            }
        });
    }

    /** Main thread. Parse JSON, dispatch the Worker POST on the IO executor. */
    private void onModelText(IntegrationContext ctx, String rawJson) {
        String stripped = stripCodeFences(rawJson);
        JSONObject parsed;
        try {
            parsed = new JSONObject(stripped);
        } catch (Exception e) {
            Log.w(TAG, "wyr JSON parse failed; raw=" + stripped, e);
            ctx.showBanner("Couldn't shape that game — try a clearer prompt", FAIL_BANNER_MS);
            return;
        }
        JSONArray qsArr = parsed.optJSONArray("questions");
        if (qsArr == null || qsArr.length() < 2) {
            ctx.showBanner("Couldn't shape that game — try a clearer prompt", FAIL_BANNER_MS);
            return;
        }
        final List<WyrClient.Question> questions = new ArrayList<>(qsArr.length());
        for (int i = 0; i < qsArr.length(); i++) {
            JSONObject q = qsArr.optJSONObject(i);
            if (q == null) continue;
            String a = q.optString("a", "").trim();
            String b = q.optString("b", "").trim();
            if (a.isEmpty() || b.isEmpty()) continue;
            questions.add(new WyrClient.Question(a, b));
        }
        if (questions.size() < 2) {
            ctx.showBanner("Couldn't shape that game — try a clearer prompt", FAIL_BANNER_MS);
            return;
        }
        io.execute(() -> {
            try {
                WyrClient.CreateResult result = WyrClient.create(questions);
                main.post(() -> ctx.commitText(result.url));
            } catch (IOException e) {
                Log.w(TAG, "WyrClient.create failed", e);
                final String msg = e.getMessage() == null ? "network error" : e.getMessage();
                main.post(() -> ctx.showBanner("Game create failed: " + msg, FAIL_BANNER_MS));
            }
        });
    }

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
