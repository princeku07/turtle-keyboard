package com.prince.split.kbd;

/**
 * A slash command an integration contributes to the keyboard. Commands with a non-null
 * {@link #handler} run locally — the dispatcher invokes the handler instead of routing
 * to the AI backend.
 */
public final class CommandSpec {

    public interface Handler {
        /** @param prompt the text after the command name, or empty string */
        void handle(String prompt, IntegrationContext ctx);
    }

    public final String name;
    public final String label;
    public final String emoji;
    public final boolean needsPrompt;
    public final Handler handler;

    public CommandSpec(String name, String label, String emoji, boolean needsPrompt, Handler handler) {
        this.name = name;
        this.label = label;
        this.emoji = emoji;
        this.needsPrompt = needsPrompt;
        this.handler = handler;
    }
}
