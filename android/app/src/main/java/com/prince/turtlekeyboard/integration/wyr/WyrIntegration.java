package com.prince.turtlekeyboard.integration.wyr;

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
import com.prince.turtlekeyboard.integration.web.GamesFirestoreClient;
import com.prince.turtlekeyboard.integration.web.WebGameSheetView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Would-you-rather integration. Local handler owns the full flow:
 *
 * <ol>
 *   <li>Loads {@code assets/prompts/wyr.txt}.</li>
 *   <li>Calls {@link GeminiService#text} to shape the user's (optional) theme prompt
 *       into dilemma pairs as JSON.</li>
 *   <li>Writes the artifact via {@link GamesFirestoreClient#createGame} with
 *       {@code type:"wyr"} — the actual quiz UI lives in the WebView at
 *       {@code games.turtlekeyboard.com/wyr/} (see {@link WebGameSheetView}).</li>
 *   <li>Commits the returned shareable App Link URL into the host field.</li>
 * </ol>
 *
 * <p>Unlike {@code PollIntegration} (native sheet), the wyr sheet view is generic
 * {@link WebGameSheetView} — every future WebView-based game registers the same factory
 * keyed by its route key. Empty user prompt is allowed; system prompt covers the
 * no-theme case.
 */
public class WyrIntegration implements KeyboardIntegration {

    private static final String TAG = "WyrIntegration";

    public static final String ROUTE_KEY = "wyr";

    private static final long BUSY_BANNER_MS = 30_000L;
    private static final long FAIL_BANNER_MS = 2_500L;

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
        // WebView shell. Reads game type from SheetContext.routeKey() so one factory
        // serves every WebView game — wyr's UI lives in JS at
        // games.turtlekeyboard.com/wyr/, not in Java.
        return Collections.singletonMap(ROUTE_KEY, WebGameSheetView::new);
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

    /** Main thread. Parses Gemini's output and writes the artifact via Firestore.
     *  Firestore callbacks fire on the main thread by default — no Handler hop. */
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
        // Build questions as a List<Map> so Firestore serializes them as document arrays
        // of nested maps. The JS game reads {a, b} directly off each element.
        final List<Map<String, Object>> questions = new ArrayList<>(qsArr.length());
        for (int i = 0; i < qsArr.length(); i++) {
            JSONObject q = qsArr.optJSONObject(i);
            if (q == null) continue;
            String a = q.optString("a", "").trim();
            String b = q.optString("b", "").trim();
            if (a.isEmpty() || b.isEmpty()) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("a", a);
            m.put("b", b);
            questions.add(m);
        }
        if (questions.size() < 2) {
            ctx.showBanner("Couldn't shape that game — try a clearer prompt", FAIL_BANNER_MS);
            return;
        }

        Map<String, Object> state = new HashMap<>();
        state.put("questions", questions);

        GamesFirestoreClient.createGame(ROUTE_KEY, state, new GamesFirestoreClient.CreateCallback() {
            @Override public void onSuccess(GamesFirestoreClient.CreateResult result) {
                ctx.commitText(result.url);
            }
            @Override public void onError(String reason) {
                Log.w(TAG, "GamesFirestoreClient.createGame(wyr) failed: " + reason);
                ctx.showBanner(bannerForError(reason), FAIL_BANNER_MS);
            }
        });
    }

    private static String bannerForError(String code) {
        switch (code) {
            case "not_signed_in":
                return "Open Turtle and sign in to create games";
            case "network":
                return "Game create failed — check your connection";
            case "permission_denied":
                return "Game create blocked — try signing out and back in";
            default:
                return "Game create failed: " + code;
        }
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
