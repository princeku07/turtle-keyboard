package com.prince.ai;

/**
 * Live text-provider config. The host (IME) builds an implementation backed by the
 * app's prefs so toggling the provider in Settings takes effect on the next call
 * without re-creating the AI clients.
 *
 * <p>Only <em>text</em> ("basic questions") is routable — image generation always
 * stays on Gemini because LM Studio can't generate images. Callers consult
 * {@link #useLocal()} per request; when true they hit {@link LmStudioClient} at
 * {@link #baseUrl()} instead of Gemini.
 */
public interface TextRoute {

    /** True when text should go to the local LM Studio server instead of Gemini. */
    boolean useLocal();

    /** LM Studio base URL, e.g. {@code http://10.0.2.2:1234/v1}. Used only when {@link #useLocal()}. */
    String baseUrl();

    /** LM Studio model id; empty lets the server use whichever model is loaded. */
    String model();
}
