package com.prince.kbd.core;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MCP (Model Context Protocol) primitive port. Modules call this to invoke a JSON-RPC
 * {@code tools/call} against any MCP-over-HTTP server. The integration owns the endpoint
 * URL, auth token, and tool / argument choice. Host app composes the concrete
 * implementation and exposes it via {@link IntegrationContext#mcp()}. Each method
 * returns immediately; the callback fires once on the main thread.
 */
public interface McpService {

    interface ToolsCallback {
        /** Raw {@code tools/list} result — array of {@code {name, description, inputSchema}}. */
        void onTools(JSONArray tools);
        void onError(String reason);
    }

    interface CallCallback {
        /** {@code result} is the JSON-RPC {@code result} object from the MCP server.
         *  Schema is per-tool. Never null. */
        void onResult(JSONObject result);

        /** {@code reason} is a short stable string: HTTP code, JSON-RPC error message,
         *  or {@code mcp_tool_error} when the server returned {@code isError: true}. */
        void onError(String reason);
    }

    /**
     * JSON-RPC 2.0 {@code tools/call} against an MCP-over-HTTP endpoint.
     *
     * @param endpoint    Full HTTPS URL of the MCP server.
     * @param bearerToken Per-user OAuth / API token, or {@code null} for unauthenticated servers.
     * @param tool        MCP tool name as declared by the server.
     * @param args        Arguments object matching the tool's input schema. May be empty
     *                    but must not be null.
     * @param cb          Result / error callback, invoked once on the main thread.
     */
    void call(String endpoint,
              @Nullable String bearerToken,
              String tool,
              JSONObject args,
              CallCallback cb);

    /** JSON-RPC 2.0 {@code tools/list} against the same endpoint. */
    void tools(String endpoint, @Nullable String bearerToken, ToolsCallback cb);
}
