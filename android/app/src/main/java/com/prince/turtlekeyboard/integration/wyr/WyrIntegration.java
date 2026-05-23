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
import com.prince.turtlekeyboard.ai.AiErrorMessages;
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
 * {@code /wyr} — generates a would-you-rather game from an optional theme prompt,
 * persists it via {@link GamesFirestoreClient#createGame}, and commits the share URL.
 * The quiz UI is the generic {@link WebGameSheetView}.
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
        return Collections.singletonMap(ROUTE_KEY, WebGameSheetView::new);
    }

    private void handleWyr(String prompt, IntegrationContext ctx) {
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
                Log.w(TAG, "wyr ai.text failed: " + reason);
                ctx.showBanner(AiErrorMessages.userMessage(reason), FAIL_BANNER_MS);
            }
        });
    }

    /** Parses model output and writes the artifact. Runs on the main thread. */
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
        // List<Map> so Firestore serializes as an array of nested maps.
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
