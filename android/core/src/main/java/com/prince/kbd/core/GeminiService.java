package com.prince.kbd.core;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * AI primitive port. Modules call this to invoke the keyboard's LLM directly with their
 * own system prompt. The host app composes the concrete implementation and exposes it
 * via {@link IntegrationContext#ai()}. Each method returns immediately; the callback
 * fires once on the main thread.
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

    /** Image generation from text only. */
    void image(@Nullable String systemPrompt, String userPrompt, ImageCallback cb);

    /** Image generation conditioned on one or more reference images. */
    void imageEdit(@Nullable String systemPrompt, String userPrompt,
                   List<InlineImage> references, ImageCallback cb);

    /** Same as {@link #imageEdit} but pinned to a higher-fidelity model variant — better
     *  compositional layout (grids, sprite sheets) at higher per-image cost. */
    void imageEditPro(@Nullable String systemPrompt, String userPrompt,
                      List<InlineImage> references, ImageCallback cb);
}
