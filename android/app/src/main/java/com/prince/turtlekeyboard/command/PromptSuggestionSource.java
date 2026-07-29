package com.prince.turtlekeyboard.command;

import java.util.Collections;
import java.util.List;

/**
 * Per-command override for what the suggestion strip shows during prompt mode.
 * Default (no override registered) is the global word dictionary.
 *
 * <p>Commands that own the strip's row with their own UI (e.g. {@code /us}'s
 * preset chips) register {@link #NONE} so the two don't compete. Future
 * commands could register custom sources — language names for a translate
 * command, recent recipients for a reply command, etc.</p>
 */
public interface PromptSuggestionSource {

    /** Returns suggestions for {@code contextBeforeCursor}, or empty to hide them. */
    List<String> suggest(String contextBeforeCursor);

    /** Suppresses the strip entirely — for commands that own the row themselves. */
    PromptSuggestionSource NONE = ctx -> Collections.emptyList();
}
