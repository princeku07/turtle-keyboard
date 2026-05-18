package com.prince.ai;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.prince.kbd.core.GeminiService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gemini-backed implementation of {@link GeminiService}. Talks to Google's
 * {@code generateContent} endpoint directly — text via {@code gemini-flash-latest},
 * images via {@code gemini-2.5-flash-image} ("Nano Banana"). Single instance shared
 * across the keyboard and any module that calls {@code ctx.ai()}.
 *
 * <p>All public methods return immediately. HTTP runs on a background executor;
 * callbacks fire on the main thread.
 */
public final class GeminiClient implements GeminiService {

    private static final String TAG = "GeminiClient";

    // gemini-2.5-flash-lite is ~2-3x faster than gemini-flash-latest for short
    // structured outputs (poll JSON, Notion blocks). Swap to gemini-flash-latest if a
    // specific feature needs more reasoning depth — but for current callers, lite is
    // the right speed / quality trade.
    private static final String GEMINI_TEXT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent";
    private static final String GEMINI_IMAGE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent";

    /** Nano Banana Pro — Google's higher-quality image model, ~3× per-image cost
     *  but dramatically better at compositional tasks (sprite sheets, grids,
     *  comics, multi-frame layouts). Used by {@code /gif} where Flash reliably
     *  fragments grids into multiple separate image outputs. */
    private static final String GEMINI_IMAGE_PRO_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-pro-image-preview:generateContent";

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 90_000;

    private final String apiKey;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** @param apiKey Gemini API key, typically wired from {@code BuildConfig.GEMINI_API_KEY}
     *               by the host. Empty/null short-circuits every call to
     *               {@code ai_no_api_key}, which surfaces to the user as a clear error. */
    public GeminiClient(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    @Override
    public void text(String systemPrompt, String userPrompt, TextCallback cb) {
        if (apiKey.isEmpty()) {
            main.post(() -> cb.onError("ai_no_api_key"));
            return;
        }
        io.execute(() -> {
            try {
                String text = doText(systemPrompt, userPrompt);
                main.post(() -> cb.onText(text));
            } catch (Exception e) {
                Log.w(TAG, "text failed", e);
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> cb.onError(reason));
            }
        });
    }

    @Override
    public void image(String systemPrompt, String userPrompt, ImageCallback cb) {
        if (apiKey.isEmpty()) {
            main.post(() -> cb.onError("ai_no_api_key"));
            return;
        }
        io.execute(() -> {
            try {
                byte[] png = doImage(systemPrompt, userPrompt);
                main.post(() -> cb.onImage(png));
            } catch (Exception e) {
                Log.w(TAG, "image failed", e);
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> cb.onError(reason));
            }
        });
    }

    @Override
    public void imageEdit(String systemPrompt, String userPrompt,
                          List<InlineImage> references, ImageCallback cb) {
        runImageEdit(GEMINI_IMAGE_URL, "imageEdit",
                systemPrompt, userPrompt, references, cb);
    }

    @Override
    public void imageEditPro(String systemPrompt, String userPrompt,
                             List<InlineImage> references, ImageCallback cb) {
        runImageEdit(GEMINI_IMAGE_PRO_URL, "imageEditPro",
                systemPrompt, userPrompt, references, cb);
    }

    /** Shared async wrapper for {@link #imageEdit} and {@link #imageEditPro} —
     *  same request shape, different target URL (Flash vs Pro model). */
    private void runImageEdit(String url, String tag, String systemPrompt,
                              String userPrompt, List<InlineImage> references,
                              ImageCallback cb) {
        if (apiKey.isEmpty()) {
            main.post(() -> cb.onError("ai_no_api_key"));
            return;
        }
        io.execute(() -> {
            try {
                byte[] png = doImageEdit(url, systemPrompt, userPrompt, references);
                main.post(() -> cb.onImage(png));
            } catch (Exception e) {
                Log.w(TAG, tag + " failed", e);
                String reason = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> cb.onError(reason));
            }
        });
    }

    // -- blocking HTTP -------------------------------------------------------

    private String doText(String systemPrompt, String userPrompt) throws Exception {
        HttpURLConnection conn = openConn(GEMINI_TEXT_URL);
        try {
            JSONObject body = new JSONObject();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                body.put("systemInstruction", new JSONObject().put("parts",
                        new JSONArray().put(new JSONObject().put("text", systemPrompt))));
            }
            JSONObject userTurn = new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", userPrompt)));
            body.put("contents", new JSONArray().put(userTurn));
            writeBody(conn, body);

            String raw = readResponse(conn);
            JSONObject resp = new JSONObject(raw);
            JSONArray candidates = resp.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                throw new RuntimeException("no candidates: " + raw);
            }
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            if (content == null) throw new RuntimeException("no content: " + raw);
            JSONArray parts = content.optJSONArray("parts");
            StringBuilder out = new StringBuilder();
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.getJSONObject(i);
                    if (part.has("text")) out.append(part.getString("text"));
                }
            }
            return stripReasoning(out.toString()).trim();
        } finally {
            conn.disconnect();
        }
    }

    private byte[] doImage(String systemPrompt, String userPrompt) throws Exception {
        HttpURLConnection conn = openConn(GEMINI_IMAGE_URL);
        try {
            JSONObject body = new JSONObject();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                body.put("systemInstruction", new JSONObject().put("parts",
                        new JSONArray().put(new JSONObject().put("text", systemPrompt))));
            }
            JSONObject userTurn = new JSONObject().put("parts",
                    new JSONArray().put(new JSONObject().put("text", userPrompt)));
            body.put("contents", new JSONArray().put(userTurn));
            writeBody(conn, body);
            return decodeImagePart(readResponse(conn));
        } finally {
            conn.disconnect();
        }
    }

    private byte[] doImageEdit(String url, String systemPrompt, String userPrompt,
                               List<InlineImage> refs) throws Exception {
        HttpURLConnection conn = openConn(url);
        try {
            JSONArray parts = new JSONArray();
            if (refs != null) {
                for (InlineImage ref : refs) {
                    if (ref == null || ref.bytes == null) continue;
                    String b64 = Base64.encodeToString(ref.bytes, Base64.NO_WRAP);
                    JSONObject inline = new JSONObject()
                            .put("mimeType", ref.mime == null ? "image/png" : ref.mime)
                            .put("data", b64);
                    parts.put(new JSONObject().put("inlineData", inline));
                }
            }
            parts.put(new JSONObject().put("text", userPrompt));

            JSONObject body = new JSONObject();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                body.put("systemInstruction", new JSONObject().put("parts",
                        new JSONArray().put(new JSONObject().put("text", systemPrompt))));
            }
            body.put("contents", new JSONArray().put(new JSONObject().put("parts", parts)));
            writeBody(conn, body);
            return decodeImagePart(readResponse(conn));
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection openConn(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-goog-api-key", apiKey);
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
            throw new RuntimeException("HTTP " + code + (err.isEmpty() ? "" : ": " + err));
        }
        return readAll(conn.getInputStream());
    }

    /** Walks {@code candidates[0].content.parts} for {@code inlineData} entries
     *  (camelCase or snake_case) and Base64-decodes the first one. Counts the
     *  total number of image parts and logs it — when nano banana is asked for
     *  a sprite sheet it sometimes returns each frame as a separate image part
     *  instead of compositing them into one tile, and the count tells us
     *  whether the prompt successfully forced a single-image response. */
    private static byte[] decodeImagePart(String raw) throws Exception {
        JSONObject resp = new JSONObject(raw);
        JSONArray candidates = resp.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new RuntimeException("no candidates: " + raw);
        }
        JSONObject cand0 = candidates.getJSONObject(0);
        JSONObject content = cand0.optJSONObject("content");
        if (content == null) {
            // Model returned a finish reason without any content block —
            // this is a refusal / safety-filter trip (e.g. IMAGE_OTHER,
            // IMAGE_SAFETY, PROHIBITED_CONTENT). Pull out the reason and
            // human-readable message so the caller can show "try
            // rephrasing" instead of a generic "Gemini unreachable".
            throw new RuntimeException(describeRefusal(cand0));
        }
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) throw new RuntimeException("no parts: " + raw);

        byte[] first = null;
        int imagePartCount = 0;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            JSONObject inline = part.optJSONObject("inlineData");
            if (inline == null) inline = part.optJSONObject("inline_data");
            if (inline != null && inline.has("data")) {
                imagePartCount++;
                if (first == null) {
                    first = Base64.decode(inline.getString("data"), Base64.DEFAULT);
                }
            }
        }
        if (imagePartCount > 1) {
            Log.w(TAG, "response contained " + imagePartCount + " image parts — "
                    + "model fragmented the output instead of returning a single "
                    + "composite. Using the first part; the rest are discarded.");
        } else {
            Log.d(TAG, "response contained 1 image part (" + (first == null ? 0
                    : first.length) + " bytes)");
        }
        if (first == null) throw new RuntimeException("no image part in response");
        return first;
    }

    /** Build a short user-facing message from a refusal candidate. The Gemini
     *  image API returns {@code finishReason} (e.g. {@code IMAGE_OTHER},
     *  {@code IMAGE_SAFETY}, {@code PROHIBITED_CONTENT}) and an optional
     *  human-readable {@code finishMessage} that often contains a markdown
     *  link to docs — we strip the link syntax and trim length so the
     *  banner can show the text without wrapping into a wall. */
    private static String describeRefusal(JSONObject candidate) {
        String reason = candidate.optString("finishReason", "");
        String msg    = candidate.optString("finishMessage", "");
        StringBuilder out = new StringBuilder("model refused");
        if (!reason.isEmpty()) out.append(" (").append(reason).append(")");
        if (!msg.isEmpty()) {
            // [text](url) → text. The doc link in IMAGE_OTHER refusals
            // is useful in the API console but unhelpful in a 2.5-second
            // toast banner.
            String clean = msg.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
            if (clean.length() > 120) clean = clean.substring(0, 117) + "…";
            out.append(": ").append(clean);
        } else {
            // Common case: no message, just a reason. Steer the user
            // toward the most likely fix.
            out.append(" — try rephrasing");
        }
        return out.toString();
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

    /** Reasoning models emit a {@code <think>…</think>} block before the real answer. */
    private static String stripReasoning(String s) {
        int end = s.lastIndexOf("</think>");
        return end >= 0 ? s.substring(end + "</think>".length()) : s;
    }
}
