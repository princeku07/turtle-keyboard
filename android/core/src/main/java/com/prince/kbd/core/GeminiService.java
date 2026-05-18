package com.prince.kbd.core;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * AI primitive port. Modules call this to invoke the keyboard's LLM directly with their
 * own system prompt — replacing the previous {@code LlmService} / {@code ImageService}
 * pair, which abstracted over provider responses and forced every command's logic to
 * live inside the AI client. The host app composes the concrete implementation
 * ({@code :ai/GeminiClient}) and exposes it via {@link IntegrationContext#ai()}.
 *
 * <p>Each method returns immediately; the callback fires once on the main thread.
 * Implementations must not block the caller.
 *
 * <p>System prompts for commands live alongside the command definition in
 * {@code commands/prompts/<name>.txt} (copied into the app's assets by Gradle and read
 * via {@code LmStudioAiClient.systemPromptFor}), so an integration loads its own prompt
 * and passes it straight in.
 */
public interface GeminiService {

    interface TextCallback {
        void onText(String text);
        void onError(String reason);
    }

    interface ImageCallback {
        void onImage(byte[] png);
        void onError(String reason);
    }

    /** Reference image for {@link #imageEdit}. */
    final class InlineImage {
        public final byte[] bytes;
        public final String mime;
        public InlineImage(byte[] bytes, String mime) {
            this.bytes = bytes;
            this.mime = mime;
        }
    }

    /** Text completion. {@code systemPrompt} may be null for a raw completion. */
    void text(@Nullable String systemPrompt, String userPrompt, TextCallback cb);

    /** Image generation from text only. Used by {@code /cap}, {@code /sticker}. */
    void image(@Nullable String systemPrompt, String userPrompt, ImageCallback cb);

    /** Image generation conditioned on one or more reference images. Used by
     *  {@code /edit}, {@code /style}, {@code /us}. */
    void imageEdit(@Nullable String systemPrompt, String userPrompt,
                   List<InlineImage> references, ImageCallback cb);

    /** Same as {@link #imageEdit} but pinned to the Nano Banana Pro model
     *  ({@code gemini-3-pro-image-preview}). Pro holds compositional layout
     *  (grids, sprite sheets, comics) and instruction following dramatically
     *  better than Flash, at roughly 3× the per-image cost. Used by {@code /gif}
     *  where the model has to draw a 4×4 frame grid inside one image — Flash
     *  reliably fragments that into multiple separate outputs. */
    void imageEditPro(@Nullable String systemPrompt, String userPrompt,
                      List<InlineImage> references, ImageCallback cb);
}
