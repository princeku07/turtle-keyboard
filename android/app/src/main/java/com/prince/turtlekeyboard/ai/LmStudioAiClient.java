package com.prince.turtlekeyboard.ai;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.prince.kbd.core.KeyValueStore;
import com.prince.kbd.core.SharedPrefsKeyValueStore;
import com.prince.turtlekeyboard.BuildConfig;
import com.prince.turtlekeyboard.command.SlashCommand;
import com.prince.turtlekeyboard.integration.drive.DriveKeys;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Routes {@code /ask} to a locally running LM Studio server that exposes the
 * OpenAI-compatible {@code /v1/chat/completions} endpoint. Every other command falls
 * through to the supplied delegate (typically {@link StubAiClient}) so the rest of
 * the pipeline keeps its current behavior.
 */
public class LmStudioAiClient implements AiClient {

    private static final String TAG = "LmStudioAiClient";
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent";
    /** "Nano Banana" — Gemini 2.5 Flash Image. Same generateContent endpoint, different
     *  model id; response carries inlineData PNG bytes instead of text. */
    private static final String GEMINI_IMAGE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent";
    private static final String GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 90_000;

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

    /** Synthetic command name for raw text completions — no built-in system prompt, the
     *  caller embeds its own instructions in the user prompt. Routed straight to Gemini,
     *  never to the stub. Legacy from when {@code AiClientLlmService} bridged an
     *  {@code LlmService} consumer to this client; the bridge is gone but the sentinel
     *  is preserved in case a future caller needs the same "raw prompt" door. */
    private static final String RAW_COMPLETION = "_llm";

    @Override
    public void execute(SlashCommand cmd, Callback callback) {
        String name = cmd.name == null ? "" : cmd.name.toLowerCase();
        if (!name.equals("ask") && !name.equals("org") && !name.equals("cap")
                && !name.equals("edit") && !name.equals("style")
                && !name.equals("sticker") && !name.equals("us")
                && !name.equals(RAW_COMPLETION)) {
            delegate.execute(cmd, callback);
            return;
        }
        String prompt = cmd.prompt == null ? "" : cmd.prompt.trim();
        if (prompt.isEmpty()) {
            String msg = name.equals("org") ? "Organize what?"
                    : name.equals("cap") ? "Describe the image…"
                    : name.equals("edit") ? "Describe the edit…"
                    : name.equals("style") ? "Pick a style (ghibli, anime, pixar…)"
                    : name.equals("sticker") ? "Describe the sticker…"
                    : name.equals("us") ? "Try /us as astronauts"
                    : name.equals(RAW_COMPLETION) ? "Empty prompt"
                    : "Ask what?";
            main.post(() -> callback.onResult(AiResult.error(msg)));
            return;
        }
        if (name.equals("cap")) {
            io.execute(() -> {
                try {
                    byte[] png = callGeminiImage(prompt, systemPromptFor("cap"));
                    // /cap has no input image — skip aspect-fix, keep model output's shape.
                    main.post(() -> saveImageBytes(png, callback, 0f, "cap", prompt));
                } catch (Exception e) {
                    Log.w(TAG, "cap failed", e);
                    main.post(() -> callback.onResult(AiResult.error("Gemini unreachable")));
                }
            });
            return;
        }
        if (name.equals("sticker")) {
            io.execute(() -> {
                try {
                    byte[] png = callGeminiImage(prompt, systemPromptFor("sticker"));
                    main.post(() -> saveImageBytes(png, callback, 0f, "sticker", prompt));
                } catch (Exception e) {
                    Log.w(TAG, "sticker failed", e);
                    main.post(() -> callback.onResult(AiResult.error("Gemini unreachable")));
                }
            });
            return;
        }
        if (name.equals("edit")) {
            io.execute(() -> {
                // Picker is launched proactively when the user enters /edit prompt mode,
                // so by the time we get here the image has typically been staged. Fall
                // back to the clipboard if the user skipped the picker (e.g. cancelled).
                ClipImage src = stagedEditImage.getAndSet(null);
                if (src == null) src = readClipboardImage();
                if (src == null) {
                    main.post(() -> callback.onResult(AiResult.error("Pick an image first")));
                    return;
                }
                try {
                    byte[] png = callGeminiImageEdit(src.bytes, src.mime, prompt);
                    float aspect = src.aspect();
                    main.post(() -> saveImageBytes(png, callback, aspect, "edit", prompt));
                } catch (Exception e) {
                    Log.w(TAG, "edit failed", e);
                    main.post(() -> callback.onResult(AiResult.error("Gemini unreachable")));
                }
            });
            return;
        }
        if (name.equals("style")) {
            io.execute(() -> {
                ClipImage src = stagedEditImage.getAndSet(null);
                if (src == null) src = readClipboardImage();
                if (src == null) {
                    main.post(() -> callback.onResult(AiResult.error("Pick an image first")));
                    return;
                }
                try {
                    String userPrompt = stylePromptFor(prompt);
                    byte[] png = callGeminiImageEdit(src.bytes, src.mime, userPrompt);
                    float aspect = src.aspect();
                    main.post(() -> saveImageBytes(
                            png, callback, aspect, "style", prompt));
                } catch (Exception e) {
                    Log.w(TAG, "style failed", e);
                    main.post(() -> callback.onResult(AiResult.error("Gemini unreachable")));
                }
            });
            return;
        }
        if (name.equals("us")) {
            io.execute(() -> {
                List<ReferenceImage> refs = readDriveReferencePhotos();
                if (refs.isEmpty()) {
                    main.post(() -> callback.onResult(AiResult.error(
                            "Add reference photos in Settings → Connect Google Drive")));
                    return;
                }
                try {
                    String userPrompt = usPromptFor(prompt);
                    byte[] png = callGeminiImageUs(userPrompt, refs, systemPromptFor("us"));
                    main.post(() -> saveImageBytes(png, callback, 0f, "us", prompt));
                } catch (Exception e) {
                    Log.w(TAG, "us failed", e);
                    main.post(() -> callback.onResult(AiResult.error("Gemini unreachable")));
                }
            });
            return;
        }
        boolean isOrg = name.equals("org");
        // Raw completions skip the asset-loaded system prompt; the integration that
        // requested the call has already prefixed its own instructions in `prompt`.
        String systemPrompt = name.equals(RAW_COMPLETION) ? "" : systemPromptFor(name);
        io.execute(() -> {
            try {
                String content = callGemini(systemPrompt, prompt);
                if (isOrg) {
                    String json = stripCodeFences(content);
                    main.post(() -> renderJsonToImage(json, callback));
                } else {
                    AiResult done = AiResult.text(content);
                    main.post(() -> callback.onResult(done));
                }
            } catch (Exception e) {
                Log.w(TAG, name + " failed", e);
                main.post(() -> callback.onResult(AiResult.error("Gemini unreachable")));
            }
        });
    }

    /** Writes the PNG bytes returned by Nano Banana to the shared cache and emits the
     *  {@code "<uri>|<path>"} payload {@code showImage} expects.
     *  <p>If {@code targetAspect > 0}, center-crops the model output to that aspect
     *  ratio first — Nano Banana frequently returns 1:1 even when {@code /edit} is
     *  given a portrait/landscape input, and {@code /edit} should give the user
     *  back an image with the same shape they handed in. Then caps the longest
     *  side at {@link #MAX_OUTPUT_SIDE_PX}, preserving aspect.
     *  <p>{@code /cap} passes 0 to skip the crop (no input image to match). Main thread. */
    private void saveImageBytes(byte[] png, Callback callback, float targetAspect,
                                String command, String prompt) {
        try {
            android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeByteArray(
                    png, 0, png.length);
            if (src == null) throw new Exception("decode failed");

            android.graphics.Bitmap aspectFixed = src;
            if (targetAspect > 0f) {
                int sw = src.getWidth();
                int sh = src.getHeight();
                float current = (float) sw / sh;
                // Skip a no-op crop when the model already matched the input aspect.
                if (Math.abs(current - targetAspect) > 0.01f) {
                    int cropW, cropH, x, y;
                    if (current > targetAspect) {
                        // Output too wide — trim left/right to match input ratio.
                        cropH = sh;
                        cropW = Math.max(1, Math.round(sh * targetAspect));
                        x = (sw - cropW) / 2;
                        y = 0;
                    } else {
                        // Output too tall — trim top/bottom.
                        cropW = sw;
                        cropH = Math.max(1, Math.round(sw / targetAspect));
                        x = 0;
                        y = (sh - cropH) / 2;
                    }
                    aspectFixed = android.graphics.Bitmap.createBitmap(src, x, y, cropW, cropH);
                    if (aspectFixed != src) src.recycle();
                }
            }

            int sw = aspectFixed.getWidth();
            int sh = aspectFixed.getHeight();
            int maxSide = Math.max(sw, sh);
            android.graphics.Bitmap scaled;
            if (maxSide <= MAX_OUTPUT_SIDE_PX) {
                scaled = aspectFixed;
            } else {
                float r = (float) MAX_OUTPUT_SIDE_PX / maxSide;
                int tw = Math.max(1, Math.round(sw * r));
                int th = Math.max(1, Math.round(sh * r));
                scaled = android.graphics.Bitmap.createScaledBitmap(aspectFixed, tw, th, true);
                if (scaled != aspectFixed) aspectFixed.recycle();
            }

            File dir = new File(appContext.getCacheDir(), "shared_images");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache dir unavailable");
            File img = new File(dir, "cap_" + System.currentTimeMillis() + ".png");
            try (OutputStream os = new FileOutputStream(img)) {
                scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os);
            }
            scaled.recycle();
            // Persist a copy in the long-lived history dir before exposing the cache
            // file to the host. Best-effort; doesn't block the user-visible result.
            ImageHistory.record(appContext, img, command, prompt);
            Uri uri = FileProvider.getUriForFile(appContext,
                    appContext.getPackageName() + ".fileprovider", img);
            callback.onResult(AiResult.image(uri.toString() + "|" + img.getAbsolutePath()));
        } catch (Exception e) {
            Log.w(TAG, "save failed", e);
            callback.onResult(AiResult.error("Save failed: " + e.getMessage()));
        }
    }

    /** Renders the Mistral-produced JSON document to a 500×500 bitmap with
     *  {@link NativeCardRenderer}, saves it as a WebP, and emits the
     *  {@code "<uri>|<path>"} payload the share pipeline already expects.
     *  No WebView, no {@code hostProvider} dependency — runs in ~10 ms.
     *  Must be called on the main thread (Bitmap save is fast but the
     *  callback contract upstream is main-thread). */
    private void renderJsonToImage(String jsonText, Callback callback) {
        Bitmap bitmap;
        try {
            JSONObject doc = new JSONObject(jsonText);
            bitmap = NativeCardRenderer.render(doc);
        } catch (Exception e) {
            Log.w(TAG, "JSON render failed: " + e.getMessage() + " | raw=" + jsonText);
            callback.onResult(AiResult.error("Bad JSON from model"));
            return;
        }
        try {
            File dir = new File(appContext.getCacheDir(), "shared_images");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache dir unavailable");
            File img = new File(dir, "org_" + System.currentTimeMillis() + ".webp");
            try (OutputStream os = new FileOutputStream(img)) {
                Bitmap.CompressFormat fmt =
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                                ? Bitmap.CompressFormat.WEBP_LOSSY
                                : Bitmap.CompressFormat.WEBP;
                bitmap.compress(fmt, 90, os);
            }
            bitmap.recycle();
            Uri uri = FileProvider.getUriForFile(appContext,
                    appContext.getPackageName() + ".fileprovider", img);
            String payload = uri.toString() + "|" + img.getAbsolutePath();
            callback.onResult(AiResult.image(payload));
        } catch (Exception e) {
            Log.w(TAG, "save failed", e);
            callback.onResult(AiResult.error("Save failed: " + e.getMessage()));
        }
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

    private String callGemini(String systemPrompt, String prompt) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(GEMINI_URL).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-goog-api-key", GEMINI_API_KEY);
        conn.setDoOutput(true);

        JSONObject body = new JSONObject();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JSONObject sysPart = new JSONObject().put("text", systemPrompt);
            JSONObject sysInstruction = new JSONObject()
                    .put("parts", new JSONArray().put(sysPart));
            body.put("systemInstruction", sysInstruction);
        }
        JSONObject userPart = new JSONObject().put("text", prompt);
        JSONObject userTurn = new JSONObject()
                .put("parts", new JSONArray().put(userPart));
        body.put("contents", new JSONArray().put(userTurn));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            String err = readStream(conn.getErrorStream());
            throw new RuntimeException("HTTP " + code + (err.isEmpty() ? "" : ": " + err));
        }

        String raw = readStream(conn.getInputStream());
        conn.disconnect();

        JSONObject resp = new JSONObject(raw);
        // Gemini: { "candidates": [ { "content": { "parts": [ { "text": "..." } ] } } ] }
        JSONArray candidates = resp.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new RuntimeException("no candidates: " + raw);
        }
        JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
        if (content == null) throw new RuntimeException("no content: " + raw);
        JSONArray parts = content.optJSONArray("parts");
        StringBuilder contentBuf = new StringBuilder();
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (part.has("text")) contentBuf.append(part.getString("text"));
            }
        }
        String text = contentBuf.toString();
        Log.d(TAG, "raw Gemini content (" + text.length() + " chars): " + text);
        return stripReasoning(text).trim();
    }

    /** Maximum length of the longest side after downscale. The shorter side scales
     *  proportionally so a non-square model output (or {@code /edit} on a portrait
     *  input that the model returns at a different aspect) keeps its ratio. 512 is
     *  Nano Banana's smallest native square dimension, so square outputs stay
     *  square at 512×512 with no redundant resample. */
    private static final int MAX_OUTPUT_SIDE_PX = 512;

    // System prompts for /cap, /edit, /sticker, /us live in commands/prompts/<name>.txt
    // — copied into assets/prompts/ at build time and loaded via {@link #systemPromptFor}.
    // Same path /ask and /org already use; gives iOS prompt parity for free. Integrations
    // migrated off this class (e.g. PollIntegration) load their prompts directly via
    // {@code AssetPrompts.load(ctx.appContext(), name)} from :core.

    /** Curated style presets for /style. The user types a preset name (e.g. "ghibli")
     *  and the matching value is sent as the user prompt to the same image-edit
     *  endpoint /edit uses. Anything not in the map is treated as a free-form style
     *  description, so power users can write their own.
     *
     *  <p>Built once and never mutated; values are tuned to bias Nano Banana toward
     *  visually distinct, recognizable looks (the kind of thing that goes viral when
     *  applied to a selfie). */
    private static final java.util.Map<String, String> STYLE_PRESETS = buildStylePresets();

    private static java.util.Map<String, String> buildStylePresets() {
        // Insertion-ordered so the UI chip strip renders in the same order every time.
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("ghibli",     "Studio Ghibli watercolor anime style. Soft pastel palette, hand-drawn feel, dreamy atmosphere, gentle natural lighting, painterly textures.");
        m.put("anime",      "Modern Japanese anime style. Crisp lineart, vibrant cel-shaded colors, expressive eyes, stylized features.");
        m.put("pixar",      "3D Pixar animation style. Soft volumetric lighting, slightly exaggerated proportions, warm cinematic colors, smooth surfaces.");
        m.put("disney",     "Classic 2D Disney animation. Clean inked lineart, expressive eyes, vibrant flat colors, smooth shading.");
        m.put("lego",       "LEGO minifigure style. Plastic blocky figure, signature LEGO yellow skin if a person, simple geometric shapes, studded surfaces.");
        m.put("clay",       "Stop-motion claymation in the style of Aardman Studios. Visible clay texture, fingerprints, slightly imperfect lopsided features, warm light.");
        m.put("pixel",      "16-bit pixel art. Limited retro palette, visible pixels, classic JRPG aesthetic.");
        m.put("watercolor", "Watercolor painting. Soft bleeding edges, visible paper texture, transparent washes of color, loose brushstrokes.");
        m.put("oil",        "Renaissance oil painting. Rich textures, dramatic chiaroscuro lighting, refined brushwork, museum-quality feel.");
        m.put("comic",      "Western comic book style. Bold black ink outlines, halftone dot shading, vivid flat colors, dynamic posing.");
        m.put("manga",      "Black and white manga. Detailed linework, screen tones for shading, expressive ink strokes, no color.");
        m.put("cyberpunk",  "Cyberpunk neon. Saturated pinks and cyans, rain-slick streets, holographic signage, dystopian future vibe.");
        m.put("vintage",    "Vintage 1970s polaroid. Faded warm colors, light leaks, fine grain, nostalgic feel, soft edges.");
        m.put("noir",       "Film noir black and white. High contrast, dramatic shadows, atmospheric mood, cinematic framing.");
        m.put("vaporwave",  "Vaporwave aesthetic. Pastel pinks and purples, retro 80s elements, glitch art, palm trees, sunset gradient.");
        m.put("lineart",    "Clean black-ink line drawing on white. No color, no shading, confident contour lines.");
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Display order of style preset keys, used by the IME to render the chip strip
     *  shown when the user enters {@code /style} prompt mode. Lower-case keys; the
     *  caller is responsible for any title-casing in the UI. */
    public static java.util.List<String> stylePresetNames() {
        return new java.util.ArrayList<>(STYLE_PRESETS.keySet());
    }

    /** Maps the user's /style prompt to the image-edit user prompt sent to Nano
     *  Banana. Recognized preset → curated description; otherwise the free-form
     *  text is treated as a custom style instruction. */
    private static String stylePromptFor(String request) {
        String key = request == null ? "" : request.trim().toLowerCase();
        String preset = STYLE_PRESETS.get(key);
        if (preset != null) {
            return "Restyle this image as: " + preset
                    + " Preserve the subject's identity and composition.";
        }
        return "Restyle this image: " + request
                + ". Preserve the subject's identity and composition.";
    }

    /** Curated scenario presets for /us. The user types or taps a key (e.g. "astronauts")
     *  and the matching value is sent as the prompt to Nano Banana with the user's
     *  reference selfies as inline image parts. Anything not in the map is treated as a
     *  free-form scenario description, so power users can write their own.
     *
     *  <p>Tuned toward couple-shaped / shareable scenarios for the launch positioning —
     *  every entry should look great as a /us image dropped into a chat. Insertion-ordered
     *  so the chip strip renders the same way every time. */
    private static final java.util.Map<String, String> US_PRESETS = buildUsPresets();

    private static java.util.Map<String, String> buildUsPresets() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("astronauts", "as astronauts on Mars in space suits, helmets reflecting the distant Earth, cinematic lighting");
        m.put("ghibli",     "as Studio Ghibli characters in a hand-drawn watercolor anime scene, soft pastel palette, dreamy atmosphere");
        m.put("anime",      "as modern Japanese anime characters with crisp lineart, vibrant cel-shaded colors, expressive eyes");
        m.put("pixar",      "as Pixar 3D animated characters with soft volumetric lighting, warm cinematic colors, slightly stylized features");
        m.put("vintage",    "in a 1970s Bollywood film poster, dramatic warm color grading, soft film grain, hand-painted feel");
        m.put("polaroid",   "in a vintage polaroid photograph, faded warm colors, light leaks, soft grain, candid moment");
        m.put("renaissance","as figures in a Renaissance oil painting, rich textures, dramatic chiaroscuro lighting, museum-quality feel");
        m.put("cyberpunk",  "in a cyberpunk neon-lit street, saturated pinks and cyans, holographic signage, dystopian future vibe");
        m.put("fantasy",    "as fantasy adventurers in a misty enchanted forest, leather armor and cloaks, atmospheric lighting");
        m.put("paris",      "in front of the Eiffel Tower at golden hour, Parisian streets, romantic warm light");
        m.put("beach",      "on a tropical beach at sunset, palm trees, warm light, vacation polaroid feel");
        m.put("noir",       "in a 1940s film noir scene, black and white, high contrast, dramatic shadows, cigarette smoke and rain");
        m.put("lego",       "as LEGO minifigures with the signature blocky look, studded plastic surfaces, plain bright background");
        m.put("pixel",      "as 16-bit pixel art characters, limited retro palette, classic JRPG aesthetic");
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Display order of /us preset keys. The IME renders a horizontal chip strip from
     *  this list whenever the user enters {@code /us} prompt mode, mirroring the /style
     *  chip strip. Lower-case keys; the chip view title-cases for display. */
    public static java.util.List<String> usPresetNames() {
        return new java.util.ArrayList<>(US_PRESETS.keySet());
    }

    /** Maps the user's /us prompt to the user-prompt text sent alongside reference photos
     *  in the Nano Banana request. Recognized preset → curated description; otherwise the
     *  free-form text is treated as a custom scenario description. The system prompt
     *  (loaded from {@code commands/prompts/us.txt}) instructs the model to preserve identity and place
     *  the reference faces into whatever scenario this returns. */
    private static String usPromptFor(String request) {
        String key = request == null ? "" : request.trim().toLowerCase();
        String preset = US_PRESETS.get(key);
        if (preset != null) return preset;
        return request == null ? "" : request;
    }

    /** Bytes + mime type + decoded pixel dimensions of an image read from the system
     *  clipboard or picked by the user. Dimensions let us recover the input's aspect
     *  ratio after the model returns (Nano Banana frequently returns 1:1 even when
     *  the input is portrait/landscape) so we can center-crop back. */
    private static class ClipImage {
        final byte[] bytes;
        final String mime;
        final int width;
        final int height;
        ClipImage(byte[] b, String m, int w, int h) {
            this.bytes = b; this.mime = m; this.width = w; this.height = h;
        }
        /** Aspect ratio of the input image, or 0 if the bounds couldn't be decoded. */
        float aspect() {
            return (width > 0 && height > 0) ? (float) width / height : 0f;
        }
    }

    /** Bounds-only decode of the image bytes — much cheaper than a full decode and
     *  enough to pull width/height for aspect-ratio recovery. */
    private static int[] decodeBounds(byte[] bytes) {
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        return new int[]{opts.outWidth, opts.outHeight};
    }

    /** Image staged by {@link ImagePickerActivity} when the user enters {@code /edit}
     *  prompt mode. Consumed by the next {@code /edit} dispatch. Null until the picker
     *  delivers; reset to null on consume. */
    private static final AtomicReference<ClipImage> stagedEditImage = new AtomicReference<>();

    /** Notified whenever {@link #stageEditImage} updates the staged slot — the IME
     *  uses this to refresh its prompt panel preview and bring itself back to the
     *  foreground after the picker activity tore down focus. */
    public interface OnImageStagedListener {
        /** {@code bytes} null means the staged image was cleared. */
        void onImageStaged(@Nullable byte[] bytes, @Nullable String mime);
    }

    private static final AtomicReference<OnImageStagedListener> stageListener =
            new AtomicReference<>();

    public static void setOnImageStagedListener(@Nullable OnImageStagedListener l) {
        stageListener.set(l);
    }

    /** Called by {@link ImagePickerActivity} after the user picks. Null bytes (cancel
     *  or read failure) leave the staged image cleared; the next {@code /edit} dispatch
     *  will then fall back to the clipboard or surface "Pick an image first". Also
     *  fires the {@link OnImageStagedListener} so the IME can refresh its UI. */
    public static void stageEditImage(byte[] bytes, String mime) {
        if (bytes == null) {
            stagedEditImage.set(null);
        } else {
            int[] dims = decodeBounds(bytes);
            stagedEditImage.set(new ClipImage(
                    bytes, mime != null ? mime : "image/png", dims[0], dims[1]));
        }
        OnImageStagedListener l = stageListener.get();
        if (l != null) l.onImageStaged(bytes, mime);
    }

    /** Looks for the first image-typed item on the primary clip and reads its bytes
     *  via {@link android.content.ContentResolver}. Returns null if the clipboard is
     *  empty, holds only text, or the source app revoked URI access. */
    private ClipImage readClipboardImage() {
        ClipboardManager cm = (ClipboardManager)
                appContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null) return null;
        for (int i = 0; i < clip.getItemCount(); i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri == null) continue;
            try {
                String mime = appContext.getContentResolver().getType(uri);
                if (mime == null || !mime.startsWith("image/")) continue;
                try (InputStream in = appContext.getContentResolver().openInputStream(uri)) {
                    if (in == null) continue;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    byte[] bytes = out.toByteArray();
                    int[] dims = decodeBounds(bytes);
                    return new ClipImage(bytes, mime, dims[0], dims[1]);
                }
            } catch (Exception e) {
                Log.w(TAG, "clip read failed for " + uri, e);
            }
        }
        return null;
    }

    /** Calls Nano Banana ({@code gemini-2.5-flash-image}) and returns the first
     *  {@code inlineData} part as raw image bytes (PNG). The model occasionally also
     *  returns a text part; we ignore it. */
    private byte[] callGeminiImage(String prompt, String systemPrompt) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(GEMINI_IMAGE_URL).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-goog-api-key", GEMINI_API_KEY);
        conn.setDoOutput(true);

        JSONObject sysInstruction = new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", systemPrompt)));
        JSONObject userPart = new JSONObject().put("text", prompt);
        JSONObject userTurn = new JSONObject()
                .put("parts", new JSONArray().put(userPart));
        JSONObject body = new JSONObject()
                .put("systemInstruction", sysInstruction)
                .put("contents", new JSONArray().put(userTurn));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            String err = readStream(conn.getErrorStream());
            throw new RuntimeException("HTTP " + code + (err.isEmpty() ? "" : ": " + err));
        }

        String raw = readStream(conn.getInputStream());
        conn.disconnect();
        return decodeImagePart(raw);
    }

    /** Reference photo for /us — bytes + mime type. Bytes are read from the local cache
     *  populated by {@code DriveLinkActivity}; the same files are also synced to the user's
     *  own Drive via {@code DriveFilesClient}, but for the gen call we use the local copy
     *  to skip the Drive download round-trip. */
    private static final class ReferenceImage {
        final byte[] bytes;
        final String mime;
        ReferenceImage(byte[] b, String m) { bytes = b; mime = m; }
    }

    /** Loads the locally-cached reference selfies the user picked in {@code DriveLinkActivity}.
     *  Reads {@link DriveKeys#REFERENCE_PHOTOS} (newline-separated {@code <path>|<fileId>}
     *  entries), drops entries whose local file is gone, returns bytes + mime for each. */
    private List<ReferenceImage> readDriveReferencePhotos() {
        KeyValueStore store = new SharedPrefsKeyValueStore(
                appContext, SharedPrefsKeyValueStore.DEFAULT_FILE).scoped("drive");
        String raw = store.getString(DriveKeys.REFERENCE_PHOTOS, "");
        List<ReferenceImage> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String path = line.split("\\|", -1)[0];
            File f = new File(path);
            if (!f.exists()) continue;
            try (FileInputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[8192];
                int n;
                while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
                out.add(new ReferenceImage(buf.toByteArray(), mimeFromPath(path)));
            } catch (IOException e) {
                Log.w(TAG, "ref read failed for " + path, e);
            }
        }
        return out;
    }

    private static String mimeFromPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return "image/jpeg";
        String ext = path.substring(dot + 1).toLowerCase();
        switch (ext) {
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "png":  return "image/png";
            case "webp": return "image/webp";
            case "heic": return "image/heic";
            default:     return "image/jpeg";
        }
    }

    /** /us — sends every reference selfie inline plus the user's scenario prompt to Nano
     *  Banana. The model treats the inline images as identity references and places them
     *  into the prompted scene. Same endpoint as /cap and /edit; difference is multiple
     *  image parts in {@code contents.parts} ahead of the text part. */
    private byte[] callGeminiImageUs(String prompt, List<ReferenceImage> refs, String systemPrompt) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(GEMINI_IMAGE_URL).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-goog-api-key", GEMINI_API_KEY);
        conn.setDoOutput(true);

        JSONArray parts = new JSONArray();
        for (ReferenceImage ref : refs) {
            String b64 = android.util.Base64.encodeToString(ref.bytes, android.util.Base64.NO_WRAP);
            JSONObject inline = new JSONObject().put("mimeType", ref.mime).put("data", b64);
            parts.put(new JSONObject().put("inlineData", inline));
        }
        parts.put(new JSONObject().put("text", prompt));

        JSONObject userTurn = new JSONObject().put("parts", parts);
        JSONObject sysInstruction = new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", systemPrompt)));
        JSONObject body = new JSONObject()
                .put("systemInstruction", sysInstruction)
                .put("contents", new JSONArray().put(userTurn));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            String err = readStream(conn.getErrorStream());
            throw new RuntimeException("HTTP " + code + (err.isEmpty() ? "" : ": " + err));
        }
        String raw = readStream(conn.getInputStream());
        conn.disconnect();
        return decodeImagePart(raw);
    }

    /** Sends the user's clipboard image plus a text instruction to Nano Banana for
     *  in-place editing. The image goes first in {@code parts} so the model treats it
     *  as the primary input; the text part is the edit instruction. */
    private byte[] callGeminiImageEdit(byte[] imageBytes, String mime, String prompt)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(GEMINI_IMAGE_URL).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-goog-api-key", GEMINI_API_KEY);
        conn.setDoOutput(true);

        String b64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP);
        JSONObject inline = new JSONObject().put("mimeType", mime).put("data", b64);
        JSONObject imagePart = new JSONObject().put("inlineData", inline);
        JSONObject textPart = new JSONObject().put("text", prompt);
        JSONObject userTurn = new JSONObject()
                .put("parts", new JSONArray().put(imagePart).put(textPart));
        JSONObject sysInstruction = new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", systemPromptFor("edit"))));
        JSONObject body = new JSONObject()
                .put("systemInstruction", sysInstruction)
                .put("contents", new JSONArray().put(userTurn));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            String err = readStream(conn.getErrorStream());
            throw new RuntimeException("HTTP " + code + (err.isEmpty() ? "" : ": " + err));
        }
        String raw = readStream(conn.getInputStream());
        conn.disconnect();
        return decodeImagePart(raw);
    }

    /** Walks {@code candidates[0].content.parts} for the first inlineData entry
     *  (camelCase or snake_case) and Base64-decodes it. */
    private static byte[] decodeImagePart(String raw) throws Exception {
        JSONObject resp = new JSONObject(raw);
        JSONArray candidates = resp.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new RuntimeException("no candidates: " + raw);
        }
        JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
        if (content == null) throw new RuntimeException("no content: " + raw);
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) throw new RuntimeException("no parts: " + raw);
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            JSONObject inline = part.optJSONObject("inlineData");
            if (inline == null) inline = part.optJSONObject("inline_data");
            if (inline != null && inline.has("data")) {
                return android.util.Base64.decode(
                        inline.getString("data"), android.util.Base64.DEFAULT);
            }
        }
        throw new RuntimeException("no image part in response");
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
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
