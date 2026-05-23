package com.prince.turtlekeyboard.ai;

import android.os.Handler;
import android.os.Looper;

import com.prince.turtlekeyboard.command.SlashCommand;

/** Placeholder AI client emitting deterministic stand-ins so the pipeline can be exercised offline. */
public class StubAiClient implements AiClient {

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void execute(SlashCommand cmd, Callback callback) {
        main.postDelayed(() -> callback.onResult(stub(cmd)), 400L);
    }

    private AiResult stub(SlashCommand cmd) {
        switch (cmd.name) {
            case "cap":
                return AiResult.image("turtle://stub/" + cmd.prompt);
            case "fix":
                return AiResult.text("[fixed text]");
            case "tone":
                return AiResult.text("[" + (cmd.prompt.isEmpty() ? "neutral" : cmd.prompt) + " rewrite]");
            case "reply":
                return AiResult.suggestions(new String[]{"Sounds good!", "On it.", "Got it, thanks."});
            case "tl":
                return AiResult.text("[translated]");
            case "search":
                return AiResult.suggestions(new String[]{
                        "🔍 " + cmd.prompt,
                        "Top result for " + cmd.prompt,
                        "Open " + cmd.prompt
                });
            default:
                return AiResult.error("Unknown command: /" + cmd.name);
        }
    }
}
