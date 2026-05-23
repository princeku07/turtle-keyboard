package com.prince.turtlekeyboard.suggestion;

import com.prince.turtlekeyboard.suggest.SuggestionEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bridges {@link SuggestionEngine} to the {@link SuggestionProvider} contract.
 * Returns up to 3 candidates for the last whitespace-separated token, with
 * leading capitalization preserved. Empty until the dictionary is loaded.
 */
public class SymSpellSuggestionProvider implements SuggestionProvider {

    private static final int MAX = 3;

    private final SuggestionEngine engine;

    public SymSpellSuggestionProvider(SuggestionEngine engine) {
        this.engine = engine;
    }

    @Override
    public List<String> suggest(CharSequence textBeforeCursor) {
        if (textBeforeCursor == null || textBeforeCursor.length() == 0) {
            return Collections.emptyList();
        }
        String s = textBeforeCursor.toString();
        int start = s.length();
        while (start > 0 && !isBoundary(s.charAt(start - 1))) start--;
        String word = s.substring(start);
        if (word.isEmpty()) return Collections.emptyList();

        List<String> raw = engine.suggest(word, MAX);
        if (raw.isEmpty() || !Character.isUpperCase(word.charAt(0))) return raw;

        List<String> out = new ArrayList<>(raw.size());
        for (String r : raw) out.add(capitalize(r));
        return out;
    }

    private static boolean isBoundary(char c) {
        return Character.isWhitespace(c) || c == '\n';
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
