package com.prince.kbd.core;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads a command's system prompt from {@code assets/prompts/<name>.txt} (mirrored from
 * {@code commands/prompts/<name>.txt} at the repo root by a Gradle copy task). Process-wide
 * in-memory cache — each prompt is read off disk at most once per process lifetime.
 *
 * <p>Used by integrations to load their own system prompts before calling
 * {@link GeminiService}. The same path Android + iOS read from, so prompt edits in
 * {@code commands/prompts/} propagate to both platforms without code changes.
 */
public final class AssetPrompts {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private AssetPrompts() {}

    /** Returns the prompt for {@code name}, or empty string when the file is missing.
     *  Caller decides what to do with empty (error out, fall back to a raw completion,
     *  show "rebuild" hint) — this helper has no opinion on missing prompts. */
    public static String load(Context context, String name) {
        if (context == null || name == null || name.isEmpty()) return "";
        String cached = CACHE.get(name);
        if (cached != null) return cached;
        String loaded = read(context, "prompts/" + name + ".txt");
        if (loaded == null) return "";
        CACHE.put(name, loaded);
        return loaded;
    }

    private static String read(Context ctx, String path) {
        try (InputStream in = ctx.getAssets().open(path);
             BufferedReader br = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString().trim();
        } catch (IOException e) {
            return null;
        }
    }
}
