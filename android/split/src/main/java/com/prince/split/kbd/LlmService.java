package com.prince.split.kbd;

/**
 * Module-side handle to the keyboard's LLM. Integrations (Notion, future Linear, etc.)
 * call this to ask the same model the rest of the keyboard uses, without taking a
 * dependency on the IME's concrete {@code AiClient}. The IME provides an adapter.
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
