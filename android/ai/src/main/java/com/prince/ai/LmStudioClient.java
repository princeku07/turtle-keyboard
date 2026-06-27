package com.prince.ai;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Blocking text completion against a local LM Studio server's OpenAI-compatible
 * {@code /v1/chat/completions} endpoint. Stateless helper — callers run it on their
 * own background executor and post the result the way they already do for Gemini.
 *
 * <p>Text only; LM Studio doesn't generate images. Intended for local testing — the
 * server runs over cleartext HTTP, so the target host must be whitelisted in
 * {@code network_security_config.xml}.
 */
public final class LmStudioClient {

    private static final String TAG = "LmStudioClient";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 90_000;

    /** Sent when no model id is configured; LM Studio routes to its loaded model regardless. */
    private static final String DEFAULT_MODEL = "local-model";

    private LmStudioClient() {}

    /**
     * One-shot chat completion.
     *
     * @param baseUrl      server base, e.g. {@code http://10.0.2.2:1234} or {@code .../v1}; the
     *                     {@code /chat/completions} path is appended as needed.
     * @param model        model id, or empty to let the server pick its loaded model.
     * @param systemPrompt optional system instruction; skipped when null/empty.
     * @param userPrompt   the user turn.
     * @return the assistant message content, trimmed.
     */
    public static String complete(String baseUrl, String model,
                                  String systemPrompt, String userPrompt) throws Exception {
        String endpoint = endpoint(baseUrl);
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            JSONArray messages = new JSONArray();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            }
            messages.put(new JSONObject().put("role", "user").put("content", userPrompt));

            JSONObject body = new JSONObject()
                    .put("model", model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model.trim())
                    .put("messages", messages)
                    .put("stream", false)
                    // Ask the server to skip chain-of-thought and answer directly.
                    // Reasoning models (Qwen3, etc.) read enable_thinking=false from their
                    // chat template, so we don't pay the latency of generating a <think>
                    // block we'd only strip. No-op for templates that ignore it; callers
                    // keep stripReasoning() as a fallback for models that reason anyway.
                    .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readAll(conn.getErrorStream());
                throw new RuntimeException("HTTP " + code + (err.isEmpty() ? "" : ": " + err));
            }

            String raw = readAll(conn.getInputStream());
            JSONObject resp = new JSONObject(raw);
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new RuntimeException("no choices: " + raw);
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) throw new RuntimeException("no message: " + raw);
            String content = message.optString("content", "");
            Log.d(TAG, "LM Studio content (" + content.length() + " chars)");
            return content.trim();
        } finally {
            conn.disconnect();
        }
    }

    /** Normalizes a user-entered base URL to the full chat-completions endpoint. */
    private static String endpoint(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (b.isEmpty()) b = "http://10.0.2.2:1234/v1";
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) return b;
        if (b.endsWith("/v1")) return b + "/chat/completions";
        return b + "/v1/chat/completions";
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
}
