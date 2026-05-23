package com.prince.turtlekeyboard.ai;

import android.os.Handler;
import android.os.Looper;

import com.prince.turtlekeyboard.command.SlashCommand;

/**
 * Loader-testing client. Holds the loader visible for a fixed delay then completes
 * with an empty text result so the loader hides cleanly with no host-field side effects.
 */
public class MockAiClient implements AiClient {

    private static final long DELAY_MS = 10_000L;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void execute(SlashCommand cmd, Callback callback) {
        main.postDelayed(() -> callback.onResult(AiResult.text("")), DELAY_MS);
    }
}
