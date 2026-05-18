package com.prince.turtlekeyboard.integration.usermcp;

import androidx.annotation.Nullable;

import com.prince.kbd.core.KeyValueStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * One user-configured MCP binding — a slash-command name bound to a single
 * {@code (endpoint, tool)} pair plus an argument template. Per-binding, not per-server:
 * if a user wants {@code /issue} and {@code /comment} against the same Linear server,
 * they create two bindings.
 *
 * <p>Storage lives in {@code ctx.store("user-mcp")} under key {@code "bindings"} as a JSON
 * array. Bearer tokens are stored separately under {@code "token:<binding_id>"} so
 * removing a binding nukes its credential atomically.
 *
 * <p><b>What this is not:</b> a transport. The keyboard's {@code McpService} is the only
 * thing that talks to MCP servers. This class is pure data + argument hydration +
 * result-string extraction.
 */
public final class McpBinding {

    private static final String STORE_KEY = "bindings";
    private static final String TOKEN_KEY_PREFIX = "token:";

    public final String id;
    public final String command;        // slash-command name, no leading slash
    public final String label;
    public final String emoji;
    public final String endpoint;       // full HTTPS URL
    public final String tool;           // MCP tool name
    public final JSONObject argTemplate;
    /** {@code "${field}"} → look up {@code field} in result.structuredContent. Empty →
     *  concat all {@code result.content[*].text} entries (the v1 default — safest fallback). */
    public final String resultFormat;
    public final long createdAt;

    public McpBinding(String id, String command, String label, String emoji,
                      String endpoint, String tool, JSONObject argTemplate,
                      String resultFormat, long createdAt) {
        this.id = id;
        this.command = command;
        this.label = label;
        this.emoji = emoji;
        this.endpoint = endpoint;
        this.tool = tool;
        this.argTemplate = argTemplate == null ? new JSONObject() : argTemplate;
        this.resultFormat = resultFormat == null ? "" : resultFormat;
        this.createdAt = createdAt;
    }

    // -- template hydration --------------------------------------------------

    /**
     * Substitutes {@code ${prompt}}, {@code ${clipboard}}, {@code ${recipient}} inside every
     * string value of {@link #argTemplate}, recursively. Non-string values pass through
     * unchanged. Returns a fresh JSONObject — never mutates the template.
     */
    public JSONObject hydrateArgs(String prompt, String clipboard, @Nullable String recipient) {
        return (JSONObject) substituteValue(argTemplate, safe(prompt), safe(clipboard), safe(recipient));
    }

    private static Object substituteValue(Object v, String prompt, String clipboard, String recipient) {
        if (v instanceof JSONObject) {
            JSONObject src = (JSONObject) v;
            JSONObject out = new JSONObject();
            Iterator<String> it = src.keys();
            while (it.hasNext()) {
                String k = it.next();
                try {
                    out.put(k, substituteValue(src.opt(k), prompt, clipboard, recipient));
                } catch (JSONException ignored) { /* key collision impossible on fresh object */ }
            }
            return out;
        }
        if (v instanceof JSONArray) {
            JSONArray src = (JSONArray) v;
            JSONArray out = new JSONArray();
            for (int i = 0; i < src.length(); i++) {
                out.put(substituteValue(src.opt(i), prompt, clipboard, recipient));
            }
            return out;
        }
        if (v instanceof String) {
            return ((String) v)
                    .replace("${prompt}", prompt)
                    .replace("${clipboard}", clipboard)
                    .replace("${recipient}", recipient);
        }
        return v;
    }

    // -- result extraction ---------------------------------------------------

    /**
     * Flattens an MCP {@code result} object into a single string to commit into the host
     * editor. v1 rules:
     * <ul>
     *   <li>If {@link #resultFormat} is {@code ${field}}, look up {@code field} in
     *       {@code result.structuredContent} (deep-dotted paths not supported).</li>
     *   <li>Otherwise, concatenate every {@code result.content[*].text} entry with newlines.</li>
     * </ul>
     * Returns empty string if neither shape is present — the caller surfaces that as the
     * raw MCP response so the user can see what went wrong.
     */
    public String extractText(JSONObject mcpResult) {
        if (mcpResult == null) return "";

        if (resultFormat.startsWith("${") && resultFormat.endsWith("}")) {
            String field = resultFormat.substring(2, resultFormat.length() - 1).trim();
            JSONObject sc = mcpResult.optJSONObject("structuredContent");
            if (sc != null) {
                Object v = sc.opt(field);
                if (v != null) return String.valueOf(v);
            }
        }

        JSONArray content = mcpResult.optJSONArray("content");
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null) continue;
            String text = part.optString("text", "");
            if (!text.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(text);
            }
        }
        return sb.toString();
    }

    // -- (de)serialization ---------------------------------------------------

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("command", command)
                .put("label", label)
                .put("emoji", emoji)
                .put("endpoint", endpoint)
                .put("tool", tool)
                .put("arg_template", argTemplate)
                .put("result_format", resultFormat)
                .put("created_at", createdAt);
    }

    private static McpBinding fromJson(JSONObject o) throws JSONException {
        return new McpBinding(
                o.getString("id"),
                o.getString("command"),
                o.optString("label", o.getString("command")),
                o.optString("emoji", "🔌"),
                o.getString("endpoint"),
                o.getString("tool"),
                o.optJSONObject("arg_template"),
                o.optString("result_format", ""),
                o.optLong("created_at", System.currentTimeMillis()));
    }

    // -- store I/O -----------------------------------------------------------

    public static List<McpBinding> loadAll(KeyValueStore store) {
        List<McpBinding> out = new ArrayList<>();
        String raw = store.getString(STORE_KEY, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(fromJson(o));
            }
        } catch (JSONException ignored) { /* corrupt blob — treat as empty */ }
        return out;
    }

    public static void saveAll(KeyValueStore store, List<McpBinding> bindings) {
        JSONArray arr = new JSONArray();
        for (McpBinding b : bindings) {
            try { arr.put(b.toJson()); } catch (JSONException ignored) { }
        }
        store.putString(STORE_KEY, arr.toString());
    }

    public static String tokenKey(String bindingId) {
        return TOKEN_KEY_PREFIX + bindingId;
    }

    private static String safe(@Nullable String s) {
        return s == null ? "" : s;
    }
}
