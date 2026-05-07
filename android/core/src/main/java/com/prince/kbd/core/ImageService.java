package com.prince.kbd.core;

import android.net.Uri;

import androidx.annotation.Nullable;

/**
 * Module-side handle to image generation. Same shape as {@link LlmService}: callers send
 * a request and receive a callback with a result URL or an error. The active provider
 * (OpenAI / Gemini / Replicate / …) is wired up in {@code :app} and exposed via
 * {@link IntegrationContext#images()}.
 *
 * <p>Use is opt-in. Modules that don't need image gen simply don't call this.
 */
public interface ImageService {

    enum Size { SQUARE, PORTRAIT, LANDSCAPE }

    /** Visual style hint. Providers map to their nearest concept (DALL-E natural vs vivid,
     *  Imagen photoreal vs illustration, etc.); modules don't need to know the mapping. */
    enum Style { PHOTO, ILLUSTRATION, STICKER }

    final class Request {
        public final String prompt;
        public final Size size;
        public final Style style;
        @Nullable public final Uri reference;

        public Request(String prompt, Size size, Style style, @Nullable Uri reference) {
            this.prompt = prompt;
            this.size = size;
            this.style = style;
            this.reference = reference;
        }

        public static Request of(String prompt) {
            return new Request(prompt, Size.SQUARE, Style.PHOTO, null);
        }
    }

    interface Callback {
        /** @param imageUrl provider-hosted URL the IME can download or pass to the host. */
        void onImage(String imageUrl);
        void onError(String reason);
    }

    void generate(Request req, Callback callback);
}
