package com.prince.kbd.core;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MCP (Model Context Protocol) primitive port. Modules call this to invoke a JSON-RPC
 * {@code tools/call} against any MCP-over-HTTP server. The transport is intentionally
 * thin — the integration owns the endpoint URL, the auth token, and the choice of
 * tool / arguments. Same opt-in pattern as {@link GeminiService}: modules that don't
 * talk to MCP servers simply don't call this.
 *
 * <p>Why endpoint + auth are passed per call (instead of a server registry):
 * <ul>
 *   <li>Mirrors how {@code DriveIntegration} owns its OAuth scopes and base URL today —
 *       per-integration dispatch, no shared god-class.</li>
 *   <li>Per-user tokens live in {@code ctx.store(integrationId)}, scoped to the
 *       integration that owns the connection.</li>
 *   <li>No need to ship a central server config in the open-source APK.</li>
 * </ul>
 *
 * <p>The host app composes the concrete implementation ({@code :ai/McpClient}) and
 * exposes it via {@link IntegrationContext#mcp()}. Each method returns immediately;
 * the callback fires once on the main thread.
 */
public interface McpService {

    interface ToolsCallback {
        /** Raw {@code tools/list} result — a JSON array of {@code {name, description,
         *  inputSchema}} entries. The host app's "add binding" flow renders this verbatim;
         *  argument templating is per-binding, not per-server. */
        void onTools(JSONArray tools);
        void onError(String reason);
    }

    interface CallCallback {
        /** {@code result} is the JSON-RPC {@code result} object from the MCP server.
         *  Typically contains a {@code content} array (text/image parts) and / or a
         *  {@code structuredContent} object — schema is per-tool, so integrations parse
         *  whatever shape they expect. Never null. */
        void onResult(JSONObject result);

        /** {@code reason} is a short stable string: HTTP code, JSON-RPC error message,
         *  or {@code mcp_tool_error} when the server returned {@code isError: true}.
         *  Mirrors the error convention used by {@link GeminiService}. */
        void onError(String reason);
    }

    /**
     * JSON-RPC 2.0 {@code tools/call} against an MCP-over-HTTP endpoint.
     *
     * @param endpoint    Full HTTPS URL of the MCP server (e.g. {@code https://mcp.linear.app/mcp}).
     *                    The integration owns this — typically a constant in the integration class.
     * @param bearerToken Per-user OAuth / API token. The integration is responsible for
     *                    obtaining it (deep-screen OAuth flow, same pattern as Drive /
     *                    Notion / Slack) and storing it in {@code ctx.store(integrationId)}.
     *                    Pass {@code null} for unauthenticated / public servers.
     * @param tool        MCP tool name as declared by the server (e.g. {@code create_issue}).
     * @param args        Arguments object matching the tool's input schema. May be empty
     *                    but must not be null.
     * @param cb          Result / error callback, invoked once on the main thread.
     */
    void call(String endpoint,
              @Nullable String bearerToken,
              String tool,
              JSONObject args,
              CallCallback cb);

    /** JSON-RPC 2.0 {@code tools/list} against the same endpoint. Used by the host app's
     *  "add binding" flow to enumerate available tools — the keyboard itself never calls
     *  this on the critical path. */
    void tools(String endpoint, @Nullable String bearerToken, ToolsCallback cb);
}
