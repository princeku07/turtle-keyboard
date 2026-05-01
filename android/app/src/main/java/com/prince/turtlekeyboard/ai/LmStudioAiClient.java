package com.prince.turtlekeyboard.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;

import androidx.core.content.FileProvider;

import com.prince.turtlekeyboard.command.SlashCommand;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Routes {@code /ask} to a locally running LM Studio server that exposes the
 * OpenAI-compatible {@code /v1/chat/completions} endpoint. Every other command falls
 * through to the supplied delegate (typically {@link StubAiClient}) so the rest of
 * the pipeline keeps its current behavior.
 */
public class LmStudioAiClient implements AiClient {

    private static final String TAG = "LmStudioAiClient";
    private static final String BASE_URL = "http://192.168.1.10:1234";
    private static final String MODEL = "mistralai/ministral-3-3b-reasoning";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /** Returns a window-attached ViewGroup the renderer can briefly add a
     *  WebView to. In the IME this is the SoftInputWindow's decor view. */
    public interface HostProvider {
        ViewGroup getRenderHost();
    }

    private static final String FALLBACK_ASK_PROMPT =
            "Answer concisely. No preface, no markdown headings.";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AiClient delegate;
    private final Context appContext;
    private final HostProvider hostProvider;
    /** Cached system prompts loaded from assets. Keyed by command name. */
    private final Map<String, String> promptCache = new HashMap<>();

    public LmStudioAiClient(Context context, HostProvider hostProvider, AiClient delegate) {
        this.appContext = context.getApplicationContext();
        this.hostProvider = hostProvider;
        this.delegate = delegate;
    }

    @Override
    public void execute(SlashCommand cmd, Callback callback) {
        String name = cmd.name == null ? "" : cmd.name.toLowerCase();
        if (!name.equals("ask") && !name.equals("org")) {
            delegate.execute(cmd, callback);
            return;
        }
        String prompt = cmd.prompt == null ? "" : cmd.prompt.trim();
        if (prompt.isEmpty()) {
            String msg = name.equals("org") ? "Organize what?" : "Ask what?";
            main.post(() -> callback.onResult(AiResult.error(msg)));
            return;
        }
        boolean isOrg = name.equals("org");
        String systemPrompt = systemPromptFor(name);
        io.execute(() -> {
            try {
                String content = callMistral(systemPrompt, prompt);
                if (isOrg) {
                    String html = stripCodeFences(content);
                    main.post(() -> renderHtmlToImage(html, callback));
                } else {
                    AiResult done = AiResult.text(content);
                    main.post(() -> callback.onResult(done));
                }
            } catch (Exception e) {
                Log.w(TAG, name + " failed", e);
                main.post(() -> callback.onResult(AiResult.error("Mistral unreachable")));
            }
        });
    }

    /** Hands the Mistral-produced HTML fragment to {@link AttachedHtmlRenderer}
     *  using the IME's window decor as the brief render host, then saves the
     *  resulting bitmap as a PNG and emits the {@code "<uri>|<path>"} payload
     *  the share pipeline already expects. Must run on the main thread. */
    private void renderHtmlToImage(String htmlFragment, Callback callback) {
        ViewGroup host = hostProvider == null ? null : hostProvider.getRenderHost();
        if (host == null) {
            callback.onResult(AiResult.error("No render host"));
            return;
        }
        AttachedHtmlRenderer.render(appContext, host, htmlFragment, new AttachedHtmlRenderer.Callback() {
            @Override public void onRendered(Bitmap bitmap) {
                try {
                    File dir = new File(appContext.getCacheDir(), "shared_images");
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache dir unavailable");
                    File png = new File(dir, "org_" + System.currentTimeMillis() + ".png");
                    try (OutputStream os = new FileOutputStream(png)) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                    bitmap.recycle();
                    Uri uri = FileProvider.getUriForFile(appContext,
                            appContext.getPackageName() + ".fileprovider", png);
                    String payload = uri.toString() + "|" + png.getAbsolutePath();
                    callback.onResult(AiResult.image(payload));
                } catch (Exception e) {
                    Log.w(TAG, "save failed", e);
                    callback.onResult(AiResult.error("Save failed: " + e.getMessage()));
                }
            }
            @Override public void onError(String message) {
                callback.onResult(AiResult.error("Render failed: " + message));
            }
        });
    }

    /** Loads the system prompt for {@code name} from
     *  {@code assets/prompts/<name>.txt}. The same files live at the repo root
     *  under {@code commands/prompts/} and are copied in by Gradle, so both
     *  Android and iOS read the exact same prompt text. Cached after first
     *  read; falls back to a built-in default for {@code /ask} if the asset
     *  is missing (e.g., during a partial build). */
    private String systemPromptFor(String name) {
        String cached = promptCache.get(name);
        if (cached != null) return cached;
        String loaded = loadPromptAsset(name);
        if (loaded == null) {
            Log.w(TAG, "prompt asset missing for " + name + ", using fallback");
            loaded = FALLBACK_ASK_PROMPT;
        }
        promptCache.put(name, loaded);
        return loaded;
    }

    private String loadPromptAsset(String name) {
        String path = "prompts/" + name + ".txt";
        try (InputStream in = appContext.getAssets().open(path);
             BufferedReader br = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return null;
        }
    }

    private String callMistral(String systemPrompt, String prompt) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/v1/chat/completions").openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);

        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("stream", false);
        body.put("temperature", 0.4);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        messages.put(new JSONObject().put("role", "user").put("content", prompt));
        body.put("messages", messages);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code);
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            conn.disconnect();
        }

        String content = new JSONObject(sb.toString())
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
        Log.d(TAG, "raw Mistral content (" + content.length() + " chars): " + content);
        return stripReasoning(content).trim();
    }

    /** ministral-3b-reasoning emits a {@code <think>…</think>} block before the answer. */
    private static String stripReasoning(String s) {
        int end = s.lastIndexOf("</think>");
        return end >= 0 ? s.substring(end + "</think>".length()) : s;
    }

    /** Even when told not to, models often wrap output in ```html … ``` fences. */
    private static String stripCodeFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
            int closing = t.lastIndexOf("```");
            if (closing >= 0) t = t.substring(0, closing);
        }
        return t.trim();
    }
}
