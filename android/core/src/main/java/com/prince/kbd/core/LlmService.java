package com.prince.kbd.core;

/**
 * Module-side handle to the keyboard's LLM. Modules call this to ask the same model the
 * rest of the keyboard uses, without taking a dependency on a specific provider SDK. The
 * app composes the active provider (and any fallback) and exposes it via
 * {@link IntegrationContext#llm()}.
 *
 * <p>Calls are asynchronous. Implementations must not block the caller.
 */
public interface LlmService {

    interface Callback {
        void onText(String text);
        void onError(String reason);
    }

    /** Free-form prompt → text completion. */
    void complete(String prompt, Callback callback);
}
