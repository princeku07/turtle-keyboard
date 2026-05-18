package com.prince.turtlekeyboard.integration.poll;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Poll integration — owns the full {@code /poll} flow end-to-end. The local handler:
 *
 * <ol>
 *   <li>Loads {@code assets/prompts/poll.txt} via {@link AssetPrompts}.</li>
 *   <li>Calls {@link GeminiService#text} to shape the user's terse prompt into a
 *       {@code {question, options}} JSON object.</li>
 *   <li>Parses + validates, then writes the poll doc via
 *       {@link RealtimePollClient#createPoll}.</li>
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

    private static final long BUSY_BANNER_MS = 30_000L; // long enough to last through gen + Firestore write
    private static final long FAIL_BANNER_MS = 2_500L;
    private static final long EMPTY_BANNER_MS = 2_200L;

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

    /** Main thread. Parses Gemini's output, dispatches the Firestore write. Firestore
     *  callbacks fire on the main thread by default, so no Handler hop on either side. */
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
        RealtimePollClient.createPoll(question, options, new RealtimePollClient.CreateCallback() {
            @Override public void onSuccess(RealtimePollClient.CreateResult result) {
                ctx.commitText(result.url);
            }
            @Override public void onError(String reason) {
                Log.w(TAG, "RealtimePollClient.createPoll failed: " + reason);
                ctx.showBanner(bannerForError(reason), FAIL_BANNER_MS);
            }
        });
    }

    /** Maps {@link RealtimePollClient} error codes to user-facing banner copy. The
     *  {@code not_signed_in} path is the most likely first-time failure — the IME can't
     *  host the One Tap UI itself, so we bounce the user toward MainActivity. */
    private static String bannerForError(String code) {
        switch (code) {
            case "not_signed_in":
                return "Open Turtle and sign in to create polls";
            case "invalid_payload":
                return "Couldn't shape that into a poll — try a clearer prompt";
            case "network":
                return "Poll create failed — check your connection";
            case "permission_denied":
                return "Poll create blocked — try signing out and back in";
            default:
                return "Poll create failed: " + code;
        }
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
