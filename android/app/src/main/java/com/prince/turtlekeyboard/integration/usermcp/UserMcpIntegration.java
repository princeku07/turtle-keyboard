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
 * Generic MCP integration. Unlike the per-tool integrations (Notion, Slack, Drive, etc.),
 * this one registers N slash commands at construction time, one per stored
 * {@link McpBinding}. The user adds bindings from the host app's "MCP Servers" screen;
 * each binding is a {@code (command name, endpoint, tool, auth token, arg template)}
 * tuple.
 *
 * <p>Runtime: when {@code /<command>} fires, the handler hydrates the binding's argument
 * template with {@code ${prompt}} / {@code ${clipboard}} / {@code ${recipient}}, posts a
 * single {@code tools/call} via {@link McpService}, and commits {@link McpBinding#extractText}
 * of the result into the host editor.
 *
 * <p><b>Refresh semantics:</b> commands are registered when this integration is constructed
 * (i.e. on each {@code onCreateInputView} — the IME service rebuilds the integration list
 * every time a text field gains focus). New bindings added from the host app become live
 * the next time the keyboard mounts. This is acceptable for v1; a live-refresh path can
 * be added later if it becomes a friction point.
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

    // -- handler -------------------------------------------------------------

    private void dispatch(McpBinding b, String prompt, IntegrationContext c) {
        KeyValueStore store = c.store(id());
        String token = store.getString(McpBinding.tokenKey(b.id), null);
        // null token is fine — McpClient just omits the Authorization header. The server
        // will 401 if it required auth, and the user sees the raw error (per design).

        String clipboard = readClipboard(c);
        JSONObject args = b.hydrateArgs(prompt == null ? "" : prompt.trim(),
                clipboard, /* recipient */ null);

        String busy = "Calling " + b.label + "…";
        c.showBanner(busy, 30_000L);

        c.mcp().call(b.endpoint, token, b.tool, args, new McpService.CallCallback() {
            @Override public void onResult(JSONObject result) {
                String text = b.extractText(result);
                if (text.isEmpty()) {
                    // Per design: surface raw error so the user can debug their binding
                    // (typically a stale arg template or wrong result_format).
                    c.showBanner("/" + b.command + " returned no text — check binding",
                            FAIL_BANNER_MS);
                    Log.d(TAG, "empty extract; raw=" + result);
                    return;
                }
                c.commitText(text);
            }
            @Override public void onError(String reason) {
                c.showBanner("/" + b.command + " failed: " + reason, FAIL_BANNER_MS);
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
