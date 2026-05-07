package com.prince.turtlekeyboard.ai;

import com.prince.turtlekeyboard.command.SlashCommand;

/** Abstraction over the Turtle Keyboard backend (PRD §8.4). The keyboard depends on this
 *  interface so tests and offline builds can swap in a stub. */
public interface AiClient {

    interface Callback {
        void onResult(AiResult result);
    }

    /** Asynchronous: implementations should never block the IME thread. */
    void execute(SlashCommand cmd, Callback callback);
}
