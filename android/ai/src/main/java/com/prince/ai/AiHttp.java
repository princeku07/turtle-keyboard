package com.prince.ai;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP helper shared by {@link AiLlm} and {@link AiImages}. No external deps.
 * Targets any OpenAI-API-compatible endpoint (Mistral, OpenAI, Together, Groq,
 * OpenRouter, LM Studio, …) — provider choice is just a base-URL + model swap.
 */
final class AiHttp {

    private static final String TAG = "Ai";
    static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    interface JsonCallback {
        void onJson(JSONObject body);
        void onError(String reason);
    }

    static void post(String url, String apiKey, JSONObject body, JsonCallback cb) {
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(30_000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                String response = readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
                if (code >= 400) {
                    cb.onError("ai_http_" + code + ": " + truncate(response, 240));
                    return;
                }
                cb.onJson(new JSONObject(response));
            } catch (IOException | JSONException e) {
                Log.w(TAG, "request failed", e);
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private AiHttp() {}
}
