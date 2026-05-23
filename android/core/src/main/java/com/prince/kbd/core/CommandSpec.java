package com.prince.kbd.core;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Set;

/**
 * A slash command a {@link CommandProvider} contributes to the keyboard. Commands with a
 * non-null {@link #handler} run locally; otherwise the dispatcher routes to the AI backend.
 * {@link #affinityPkgs} is a ranking signal (Quick Panel ordering), not a gate.
 * {@link #loadingMessage} is the in-flight status; null falls back to {@code "/" + name}.
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
    public final Set<String> affinityPkgs;
    @Nullable public final String loadingMessage;

    public CommandSpec(String name, String label, String emoji, boolean needsPrompt, Handler handler) {
        this(name, label, emoji, needsPrompt, handler, Collections.emptySet(), null);
    }

    public CommandSpec(String name, String label, String emoji, boolean needsPrompt,
                       Handler handler, Set<String> affinityPkgs) {
        this(name, label, emoji, needsPrompt, handler, affinityPkgs, null);
    }

    public CommandSpec(String name, String label, String emoji, boolean needsPrompt,
                       Handler handler, Set<String> affinityPkgs,
                       @Nullable String loadingMessage) {
        this.name = name;
        this.label = label;
        this.emoji = emoji;
        this.needsPrompt = needsPrompt;
        this.handler = handler;
        this.affinityPkgs = affinityPkgs == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(affinityPkgs);
        this.loadingMessage = loadingMessage;
    }
}
