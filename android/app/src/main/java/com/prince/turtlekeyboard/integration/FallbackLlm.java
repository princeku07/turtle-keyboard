package com.prince.turtlekeyboard.integration;

import com.prince.kbd.core.LlmService;

/**
 * Composes two {@link LlmService} providers: try {@code primary} first, fall through to
 * {@code fallback} on error. Lets the IME prefer (say) OpenAI but degrade to a local LM
 * Studio model when network is flaky or the primary throws.
 *
 * <p>Stack multiple of these to chain three or more providers.
 */
public final class FallbackLlm implements LlmService {

    private final LlmService primary;
    private final LlmService fallback;

    public FallbackLlm(LlmService primary, LlmService fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public void complete(String prompt, Callback cb) {
        primary.complete(prompt, new Callback() {
            @Override public void onText(String text) { cb.onText(text); }
            @Override public void onError(String reason) {
                fallback.complete(prompt, cb);
            }
        });
    }
}
