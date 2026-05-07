package com.prince.turtlekeyboard.integration;

import com.prince.kbd.core.LlmService;
import com.prince.turtlekeyboard.ai.AiClient;
import com.prince.turtlekeyboard.ai.AiResult;
import com.prince.turtlekeyboard.command.SlashCommand;

/**
 * Adapter from {@link AiClient} (which is shaped around slash commands and result kinds)
 * to the simpler module-side {@link LlmService}. Wraps the prompt in a synthetic
 * {@code SlashCommand} so the rest of the AI plumbing stays untouched, then collapses
 * the result back to a string.
 */
public final class AiClientLlmService implements LlmService {

    /** Sentinel command name used when round-tripping a free-form prompt through AiClient. */
    private static final String SYNTHETIC_NAME = "_llm";

    private final AiClient delegate;

    public AiClientLlmService(AiClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public void complete(String prompt, Callback callback) {
        SlashCommand cmd = new SlashCommand(SYNTHETIC_NAME, prompt, "/" + SYNTHETIC_NAME + " " + prompt);
        delegate.execute(cmd, result -> {
            if (result == null) { callback.onError("no_result"); return; }
            if (result.kind == AiResult.Kind.ERROR) {
                callback.onError(result.error == null ? "error" : result.error);
                return;
            }
            String text = result.text;
            if (text == null) { callback.onError("no_text"); return; }
            callback.onText(text);
        });
    }
}
