package com.prince.turtlekeyboard.suggestion;

import java.util.List;

/** Source of next-word / correction candidates shown in the suggestion strip. */
public interface SuggestionProvider {
    /** Returns up to 3 candidates for the given word being typed. May return empty. */
    List<String> suggest(CharSequence textBeforeCursor);
}
