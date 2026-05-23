package com.prince.turtlekeyboard.ai;

import com.prince.turtlekeyboard.command.SlashCommand;

/** Abstraction over the AI backend so tests and offline builds can swap in a stub. */
public interface AiClient {

    interface Callback {
        void onResult(AiResult result);
    }

    /** Asynchronous: implementations must not block the IME thread. */
    void execute(SlashCommand cmd, Callback callback);
}
