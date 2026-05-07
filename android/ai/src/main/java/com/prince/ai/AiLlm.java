package com.prince.ai;

import com.prince.kbd.core.LlmService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * {@link LlmService} backed by an OpenAI-API-compatible chat completions endpoint.
 * Defaults to Mistral via {@link BuildConfig}; override base URL / model / key in
 * {@code .env} to point at any other OpenAI-compatible provider.
 */
public final class AiLlm implements LlmService {

    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public AiLlm(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model;
    }

    /** Build-time defaults from gradle / .env. */
    public AiLlm() {
        this(BuildConfig.API_KEY, BuildConfig.BASE_URL, BuildConfig.TEXT_MODEL);
    }

    @Override
    public void complete(String prompt, Callback callback) {
        if (apiKey.isEmpty()) { callback.onError("ai_no_api_key"); return; }
        try {
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "user").put("content", prompt)));

            AiHttp.post(baseUrl + "/chat/completions", apiKey, body, new AiHttp.JsonCallback() {
                @Override public void onJson(JSONObject json) {
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        callback.onError("ai_empty_choices"); return;
                    }
                    JSONObject msg = choices.optJSONObject(0).optJSONObject("message");
                    String text = msg == null ? "" : msg.optString("content", "");
                    callback.onText(text);
                }
                @Override public void onError(String reason) { callback.onError(reason); }
            });
        } catch (Exception e) {
            callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
