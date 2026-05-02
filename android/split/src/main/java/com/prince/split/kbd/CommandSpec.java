package com.prince.split.kbd;

import java.util.Collections;
import java.util.Set;

/**
 * A slash command an integration contributes to the keyboard. Commands with a non-null
 * {@link #handler} run locally — the dispatcher invokes the handler instead of routing
 * to the AI backend.
 *
 * <p>{@link #affinityPkgs} is a ranking signal, not a gate: a command is always callable
 * everywhere, but tiles for it float to the top of the Quick Panel when the user is in
 * one of the listed apps. Empty set ⇒ no preference.
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

    public CommandSpec(String name, String label, String emoji, boolean needsPrompt, Handler handler) {
        this(name, label, emoji, needsPrompt, handler, Collections.emptySet());
    }

    public CommandSpec(String name, String label, String emoji, boolean needsPrompt,
                       Handler handler, Set<String> affinityPkgs) {
        this.name = name;
        this.label = label;
        this.emoji = emoji;
        this.needsPrompt = needsPrompt;
        this.handler = handler;
        this.affinityPkgs = affinityPkgs == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(affinityPkgs);
    }
}
