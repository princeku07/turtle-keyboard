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

/**
 * AI client routing built-in commands to Gemini. Unhandled commands fall through
 * to the supplied delegate (typically {@link StubAiClient}).
 */
public class TurtleAiClient implements AiClient {

    private static final String TAG = "TurtleAiClient";
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent";
    /** Gemini 2.5 Flash Image ("Nano Banana"); same endpoint, inlineData PNG response. */
    private static final String GEMINI_IMAGE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent";
    private static final String GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 90_000;

    /** Supplies a window-attached ViewGroup the renderer can briefly add a WebView to. */
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
    private final StagingPipeline pipeline;
    private final Map<String, String> promptCache = new HashMap<>();

    public TurtleAiClient(Context context, HostProvider hostProvider,
                          StagingPipeline pipeline, AiClient delegate) {
        this.appContext = context.getApplicationContext();
        this.hostProvider = hostProvider;
        this.pipeline = pipeline;
        this.delegate = delegate;
    }

    public void destroy() {
        io.shutdown();
    }

    /** Simple text-rewrite callback for the in-keyboard AI assist panel. */
    public interface RewriteCallback {
        void onSuccess(String rewritten);
        void onError(String message);
    }

    /**
     * Text-only transform: sends {@code systemPrompt} + {@code userText} to Gemini and
     * delivers the trimmed completion on the main thread. Used by the AI assist panel,
     * which feeds the field's whole text as the user turn.
     */
    public void rewrite(String systemPrompt, String userText, RewriteCallback cb) {
        if (cb == null) return;
        if (userText == null || userText.isEmpty()) {
            main.post(() -> cb.onError("Field is empty"));
            return;
        }
        io.execute(() -> {
            try {
                String result = callGemini(systemPrompt == null ? "" : systemPrompt, userText);
                main.post(() -> cb.onSuccess(result));
            } catch (Exception e) {
                Log.w(TAG, "rewrite failed", e);
                main.post(() -> cb.onError("Gemini unreachable"));
            }
        });
    }

    /** Synthetic command for raw text completions; caller embeds instructions in the user prompt. */
    private static final String RAW_COMPLETION = "_llm";

    @Override
    public void execute(SlashCommand cmd, Callback callback) {
        String name = cmd.name == null ? "" : cmd.name.toLowerCase();
        if (!name.equals("ask") && !name.equals("org") && !name.equals("cap")
                && !name.equals("edit") && !name.equals("style")
                && !name.equals("us")
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
                    // No input image; skip aspect-fix.
                    main.post(() -> saveImageBytes(png, callback, 0f, "cap", prompt));
                } catch (Exception e) {
                    Log.w(TAG, "cap failed", e);
                    main.post(() -> callback.onResult(AiResult.error("Gemini unreachable")));
                }
            });
            return;
        }
        if (name.equals("edit")) {
            io.execute(() -> {
                // Picker is launched when the user enters /edit prompt mode; fall back
                // to the clipboard if the user skipped it.
                ClipImage src = pipeline.consumeEditImage();
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
                ClipImage src = pipeline.consumeEditImage();
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
                List<ReferenceImage> refs = pipeline.consumeUsImages();
                if (refs == null || refs.size() < 2) {
                    main.post(() -> callback.onResult(AiResult.error("Pick two photos first")));
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
        // Raw completions skip the asset-loaded system prompt.
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

    /**
     * Writes PNG bytes to the shared cache and emits the {@code "<uri>|<path>"} payload.
     * Center-crops to {@code targetAspect} if positive (recovers input aspect when the
     * model returns 1:1), then caps the longest side at {@link #MAX_OUTPUT_SIDE_PX}.
     * Main thread.
     */
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
                if (Math.abs(current - targetAspect) > 0.01f) {
                    int cropW, cropH, x, y;
                    if (current > targetAspect) {
                        cropH = sh;
                        cropW = Math.max(1, Math.round(sh * targetAspect));
                        x = (sw - cropW) / 2;
                        y = 0;
                    } else {
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
            ImageHistory.record(appContext, img, command, prompt);
            Uri uri = FileProvider.getUriForFile(appContext,
                    appContext.getPackageName() + ".fileprovider", img);
            callback.onResult(AiResult.image(uri.toString() + "|" + img.getAbsolutePath()));
        } catch (Exception e) {
            Log.w(TAG, "save failed", e);
            callback.onResult(AiResult.error("Save failed: " + e.getMessage()));
        }
    }

    /** Renders the model JSON to a 500x500 bitmap via {@link NativeCardRenderer},
     *  saves as WebP, and emits the {@code "<uri>|<path>"} payload. Main thread. */
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

    /** Loads {@code assets/prompts/<name>.txt}, cached. Falls back to a built-in default. */
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

    /** Cap on the longest side of saved output, preserving aspect. */
    private static final int MAX_OUTPUT_SIDE_PX = 512;

    /** Curated style presets for /style; unknown keys are treated as free-form descriptions. */
    private static final java.util.Map<String, String> STYLE_PRESETS = buildStylePresets();

    private static java.util.Map<String, String> buildStylePresets() {
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

    /** Display order of /style preset keys; lower-case. */
    public static java.util.List<String> stylePresetNames() {
        return new java.util.ArrayList<>(STYLE_PRESETS.keySet());
    }

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

    /** Curated scenario presets for /us; unknown keys are free-form descriptions. */
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

    /** Display order of /us preset keys; lower-case. */
    public static java.util.List<String> usPresetNames() {
        return new java.util.ArrayList<>(US_PRESETS.keySet());
    }

    private static String usPromptFor(String request) {
        String key = request == null ? "" : request.trim().toLowerCase();
        String preset = US_PRESETS.get(key);
        if (preset != null) return preset;
        return request == null ? "" : request;
    }

    // ClipImage, ReferenceImage, and the staging slots live on StagingPipeline now —
    // see com.prince.turtlekeyboard.ai.StagingPipeline (held by TurtleApp).

    /** First image-typed item on the primary clip, or null. */
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

    /** Returns the first {@code inlineData} part as PNG bytes. */
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

    /** Loads locally-cached reference selfies; drops entries whose file is gone. */
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

    /** Sends every reference selfie inline plus the scenario prompt; image parts precede the text part. */
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

    /** Image-first edit request; the trailing text part is the edit instruction. */
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

    /** First inlineData entry (camelCase or snake_case) in {@code candidates[0].content.parts}. */
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

    /** Strips a leading {@code <think>…</think>} reasoning block if present. */
    private static String stripReasoning(String s) {
        int end = s.lastIndexOf("</think>");
        return end >= 0 ? s.substring(end + "</think>".length()) : s;
    }

    /** Strips ```html … ``` fences models often wrap output in. */
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
