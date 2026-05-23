package com.prince.turtlekeyboard.integration.usermcp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;

import com.prince.kbd.core.CommandSpec;
import com.prince.kbd.core.IntegrationContext;
import com.prince.kbd.core.IntegrationSession;
import com.prince.kbd.core.KeyValueStore;
import com.prince.kbd.core.KeyboardIntegration;
import com.prince.kbd.core.McpService;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * User-pluggable MCP integration. Registers one slash command per stored
 * {@link McpBinding}; each binding maps a command name to a {@code (endpoint, tool,
 * arg template, auth token)} tuple managed from the host app.
 *
 * <p>On dispatch, the handler hydrates the binding's arg template with
 * {@code ${prompt}} / {@code ${clipboard}} / {@code ${recipient}}, posts a single
 * {@code tools/call} via {@link McpService}, and commits {@link McpBinding#extractText}
 * into the host editor. Bindings refresh on keyboard mount, not live.
 */
public class UserMcpIntegration implements KeyboardIntegration {

    private static final String TAG = "UserMcpIntegration";
    private static final long FAIL_BANNER_MS = 2_500L;

    private final IntegrationContext ctx;
    private final List<McpBinding> bindings;

    public UserMcpIntegration(IntegrationContext ctx) {
        this.ctx = ctx;
        this.bindings = McpBinding.loadAll(ctx.store(id()));
    }

    @Override public String id() { return "user-mcp"; }

    @Override
    @Nullable
    public IntegrationSession activate(EditorInfo info, IntegrationContext ctx) {
        return null;
    }

    @Override
    public List<CommandSpec> commands() {
        List<CommandSpec> out = new ArrayList<>(bindings.size());
        for (McpBinding b : bindings) {
            out.add(new CommandSpec(b.command, b.label, b.emoji, true,
                    (prompt, c) -> dispatch(b, prompt, c)));
        }
        return out;
    }

    private void dispatch(McpBinding b, String prompt, IntegrationContext c) {
        KeyValueStore store = c.store(id());
        String token = store.getString(McpBinding.tokenKey(b.id), null);
        // Null token omits the Authorization header; server-side 401 surfaces via McpErrorMessages.

        String clipboard = readClipboard(c);
        JSONObject args = b.hydrateArgs(prompt == null ? "" : prompt.trim(),
                clipboard, /* recipient */ null);

        String busy = "Calling " + b.label + "…";
        c.showBanner(busy, 30_000L);

        c.mcp().call(b.endpoint, token, b.tool, args, new McpService.CallCallback() {
            @Override public void onResult(JSONObject result) {
                String text = b.extractText(result);
                if (text.isEmpty()) {
                    c.showBanner("/" + b.command + " returned no text — check binding",
                            FAIL_BANNER_MS);
                    Log.d(TAG, "empty extract; raw=" + result);
                    return;
                }
                c.commitText(text);
            }
            @Override public void onError(String reason) {
                Log.w(TAG, "mcp call failed (" + b.command + "): " + reason);
                c.showBanner(McpErrorMessages.userMessage(reason, "/" + b.command),
                        FAIL_BANNER_MS);
            }
        });
    }

    private static String readClipboard(IntegrationContext c) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    c.appContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence t = clip.getItemAt(0).coerceToText(c.appContext());
            return t == null ? "" : t.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
