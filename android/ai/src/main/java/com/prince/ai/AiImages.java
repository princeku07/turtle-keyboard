package com.prince.ai;

import com.prince.kbd.core.ImageService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * {@link ImageService} backed by an OpenAI-compatible {@code /images/generations}
 * endpoint. Mistral doesn't currently expose image generation, so the default
 * {@link BuildConfig#IMAGE_MODEL} is empty and this returns {@code ai_no_image_model}
 * until the user wires an image-capable provider via {@code .env}.
 */
public final class AiImages implements ImageService {

    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public AiImages(String apiKey, String baseUrl, String model) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model == null ? "" : model;
    }

    public AiImages() {
        this(BuildConfig.API_KEY, BuildConfig.BASE_URL, BuildConfig.IMAGE_MODEL);
    }

    @Override
    public void generate(Request req, Callback callback) {
        if (apiKey.isEmpty()) { callback.onError("ai_no_api_key"); return; }
        if (model.isEmpty())  { callback.onError("ai_no_image_model"); return; }
        try {
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("prompt", styledPrompt(req))
                    .put("size", sizeFor(req.size))
                    .put("n", 1);

            AiHttp.post(baseUrl + "/images/generations", apiKey, body, new AiHttp.JsonCallback() {
                @Override public void onJson(JSONObject json) {
                    JSONArray data = json.optJSONArray("data");
                    if (data == null || data.length() == 0) {
                        callback.onError("ai_empty_data"); return;
                    }
                    JSONObject first = data.optJSONObject(0);
                    String url = first == null ? null : first.optString("url", null);
                    if (url == null || url.isEmpty()) {
                        callback.onError("ai_no_image_url"); return;
                    }
                    callback.onImage(url);
                }
                @Override public void onError(String reason) { callback.onError(reason); }
            });
        } catch (Exception e) {
            callback.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String sizeFor(Size size) {
        switch (size) {
            case PORTRAIT:  return "1024x1536";
            case LANDSCAPE: return "1536x1024";
            case SQUARE:
            default:        return "1024x1024";
        }
    }

    /** Style hint inlined as a prompt suffix — most provider APIs accept a free-text
     *  style nudge but don't expose a structured "style" parameter. */
    private static String styledPrompt(Request req) {
        switch (req.style) {
            case STICKER:      return req.prompt + ", sticker style, bold outline, transparent background";
            case ILLUSTRATION: return req.prompt + ", illustration style, vibrant colors";
            case PHOTO:
            default:           return req.prompt;
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
