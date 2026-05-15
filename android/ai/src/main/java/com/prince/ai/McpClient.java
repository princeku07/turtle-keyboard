package com.prince.ai;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.prince.kbd.core.McpService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP transport for {@link McpService}. Posts a JSON-RPC 2.0 {@code tools/call} envelope
 * to the integration-supplied endpoint and surfaces the {@code result} (or {@code error})
 * back on the main thread.
 *
 * <p>Mirrors {@link GeminiClient} exactly: single-thread executor for blocking IO, a
 * main-looper handler for callbacks. One {@code McpClient} instance is shared across
 * every integration via {@code ctx.mcp()}; there is no per-server connection state to
 * manage (each call is a fresh HTTPS POST — same as the Gemini path).
 */
public final class McpClient implements McpService {

    private static final String TAG = "McpClient";

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void call(String endpoint,
                     @Nullable String bearerToken,
                     String tool,
                     JSONObject args,
                     CallCallback cb) {
        io.execute(() -> {
            try {
                JSONObject result = doCall(endpoint, bearerToken, tool, args);
                main.post(() -> cb.onResult(result));
            } catch (McpError e) {
                main.post(() -> cb.onError(e.reason));
            } catch (Exception e) {
                Log.w(TAG, "mcp call failed", e);
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> cb.onError(reason));
            }
        });
    }

    @Override
    public void tools(String endpoint, @Nullable String bearerToken, ToolsCallback cb) {
        io.execute(() -> {
            try {
                JSONArray tools = doTools(endpoint, bearerToken);
                main.post(() -> cb.onTools(tools));
            } catch (McpError e) {
                main.post(() -> cb.onError(e.reason));
            } catch (Exception e) {
                Log.w(TAG, "mcp tools/list failed", e);
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> cb.onError(reason));
            }
        });
    }

    // -- blocking HTTP -------------------------------------------------------

    private JSONObject doCall(String endpoint,
                              @Nullable String bearerToken,
                              String tool,
                              JSONObject args) throws Exception {
        HttpURLConnection conn = openConn(endpoint, bearerToken);
        try {
            JSONObject envelope = new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", UUID.randomUUID().toString())
                    .put("method", "tools/call")
                    .put("params", new JSONObject()
                            .put("name", tool)
                            .put("arguments", args == null ? new JSONObject() : args));
            writeBody(conn, envelope);

            String raw = readResponse(conn);
            JSONObject resp = new JSONObject(raw);

            // JSON-RPC transport error (e.g. method not found, invalid params).
            JSONObject err = resp.optJSONObject("error");
            if (err != null) {
                String msg = err.optString("message", "rpc_error");
                throw new McpError("mcp_" + msg);
            }

            JSONObject result = resp.optJSONObject("result");
            if (result == null) {
                throw new McpError("mcp_no_result");
            }

            // Tool-reported error — content is still present but flagged. Surface as
            // an error so callers don't have to inspect every result.
            if (result.optBoolean("isError", false)) {
                throw new McpError("mcp_tool_error");
            }
            return result;
        } finally {
            conn.disconnect();
        }
    }

    private JSONArray doTools(String endpoint, @Nullable String bearerToken) throws Exception {
        HttpURLConnection conn = openConn(endpoint, bearerToken);
        try {
            JSONObject envelope = new JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", UUID.randomUUID().toString())
                    .put("method", "tools/list")
                    .put("params", new JSONObject());
            writeBody(conn, envelope);

            String raw = readResponse(conn);
            JSONObject resp = new JSONObject(raw);

            JSONObject err = resp.optJSONObject("error");
            if (err != null) {
                throw new McpError("mcp_" + err.optString("message", "rpc_error"));
            }
            JSONObject result = resp.optJSONObject("result");
            if (result == null) throw new McpError("mcp_no_result");
            JSONArray tools = result.optJSONArray("tools");
            return tools == null ? new JSONArray() : tools;
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConn(String url, @Nullable String bearerToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        if (bearerToken != null && !bearerToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        return conn;
    }

    private static void writeBody(HttpURLConnection conn, JSONObject body) throws Exception {
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            String err = readAll(conn.getErrorStream());
            throw new McpError("http_" + code + (err.isEmpty() ? "" : ": " + err));
        }
        return readAll(conn.getInputStream());
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /** Internal sentinel so {@link #doCall} can surface a stable error reason without
     *  losing the IO {@code try/finally}. */
    private static final class McpError extends RuntimeException {
        final String reason;
        McpError(String reason) { super(reason); this.reason = reason; }
    }
}
