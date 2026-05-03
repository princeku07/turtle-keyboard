package com.prince.slack;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;

import com.prince.split.SplitStore;
import com.prince.split.kbd.CommandSpec;
import com.prince.split.kbd.IntegrationContext;
import com.prince.split.kbd.IntegrationSession;
import com.prince.split.kbd.KeyboardIntegration;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pluggable Slack integration. Contributes {@code /slack} (and {@code /msg} as alias)
 * with the same fire-and-forget shape as Notion:
 *
 * <ol>
 *   <li>User taps Go on {@code /slack <message>}.</li>
 *   <li>Composer closes, banner flashes "Sending to Slack…" for ~1s.</li>
 *   <li>Background: POST chat.postMessage to the user's default channel.</li>
 *   <li>System notification surfaces the result with permalink.</li>
 * </ol>
 *
 * <p>No LLM call — Slack messages post as the user typed them. Polish is a separate
 * concern handled by existing {@code /fix} or {@code /tone} commands.
 *
 * <p>Channel override syntax: a leading {@code #channel-name} or {@code <#CHANNEL_ID>}
 * token routes the message to that channel instead of the default. Anything else after
 * the token is the message body.
 */
public final class SlackIntegration implements KeyboardIntegration {

    /** Apps where "send this to Slack" feels most natural. Drives Quick Panel ranking. */
    private static final Set<String> AFFINITY = new HashSet<>(Arrays.asList(
            "com.notion.id",
            "com.google.android.gm",
            "com.android.chrome",
            "com.linkedin.android",
            "org.telegram.messenger",
            "com.whatsapp"));

    @Override public String id() { return "slack"; }

    @Override
    @Nullable
    public IntegrationSession activate(EditorInfo info, IntegrationContext ctx) {
        return null;
    }

    @Override
    public List<CommandSpec> commands() {
        return Arrays.asList(
                new CommandSpec("slack", "Slack", "💬", true, this::handleSlack, AFFINITY),
                new CommandSpec("msg",   "Slack message", "💬", true, this::handleSlack, AFFINITY));
    }

    private void handleSlack(String prompt, IntegrationContext ctx) {
        SplitStore store = ctx.store();
        SlackAuth auth = new SlackAuth(store);
        if (!auth.isSignedIn()) {
            ctx.showBanner("Connect Slack in the Turtle app", 1800L);
            ctx.openScreen("slack-connect");
            return;
        }

        // Channel override: "#general hello team" → #general; otherwise default channel.
        Resolved r = resolveChannel(prompt, store);
        if (r.channelId == null || r.channelId.isEmpty()) {
            ctx.showBanner("Pick a Slack channel in the Turtle app", 1800L);
            ctx.openScreen("slack-connect");
            return;
        }
        if (r.text == null || r.text.trim().isEmpty()) {
            ctx.showBanner("Type a message after /slack", 1500L);
            return;
        }

        ctx.showBanner("💬 Sending to #" + r.channelName + "…", 1000L);

        final Context appContext = ctx.appContext();
        final String token = auth.accessToken();
        final String userPrompt = r.text;
        final String channelName = r.channelName;

        new SlackClient(token).postMessage(r.channelId, r.text, new SlackClient.PostCallback() {
            @Override public void onSuccess(String channelId, String ts, String permalink) {
                post(() -> SlackResultNotifier.notifySuccess(appContext, channelName, permalink));
            }
            @Override public void onError(String reason) {
                post(() -> SlackResultNotifier.notifyError(appContext, userPrompt, reason));
            }
        });
    }

    private static final class Resolved {
        final String channelId;
        final String channelName;
        final String text;
        Resolved(String id, String name, String text) {
            this.channelId = id; this.channelName = name; this.text = text;
        }
    }

    /** Pull a leading {@code #name} or {@code <#CID>} off {@code prompt}; if the name
     *  matches a known channel, route there. Otherwise use the stored default. */
    private static Resolved resolveChannel(String prompt, SplitStore store) {
        String defaultId = store.getString(SlackKeys.DEFAULT_CHANNEL, "");
        String defaultName = store.getString(SlackKeys.DEFAULT_CHANNEL_NAME, "");
        if (prompt == null) return new Resolved(defaultId, defaultName, "");
        String trimmed = prompt.trim();
        // <#CID|name> form (used when Slack's UI inserts a channel mention)
        if (trimmed.startsWith("<#")) {
            int gt = trimmed.indexOf('>');
            if (gt > 2) {
                String inside = trimmed.substring(2, gt);
                int pipe = inside.indexOf('|');
                String cid = pipe >= 0 ? inside.substring(0, pipe) : inside;
                String name = pipe >= 0 ? inside.substring(pipe + 1) : cid;
                String body = trimmed.substring(gt + 1).trim();
                return new Resolved(cid, name, body);
            }
        }
        // #channel-name form — only honored if we know the channel id from the cached map.
        if (trimmed.startsWith("#")) {
            int sp = indexOfWhitespace(trimmed);
            String name = (sp > 0 ? trimmed.substring(1, sp) : trimmed.substring(1)).toLowerCase();
            String body = sp > 0 ? trimmed.substring(sp + 1).trim() : "";
            String id = lookupChannelId(store, name);
            if (id != null) return new Resolved(id, name, body);
            // Unknown channel name — fall through to default and keep the prefix as text
            // so the user sees their typo round-trip into Slack.
        }
        return new Resolved(defaultId, defaultName, trimmed);
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /** Look up a channel id by name from the cached map written by SlackConnectActivity
     *  ({@code slack.channel_map.<name> -> id}). Returns null if not found. */
    @Nullable
    private static String lookupChannelId(SplitStore store, String name) {
        String id = store.getString("slack.channel_map." + name, "");
        return id == null || id.isEmpty() ? null : id;
    }

    private static void post(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
