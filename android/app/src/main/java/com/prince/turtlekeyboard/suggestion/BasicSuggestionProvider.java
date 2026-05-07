package com.prince.turtlekeyboard.suggestion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Minimal placeholder. Returns the typed word and two trivial completions so the strip is
 * visibly wired. Swap with a dictionary-backed provider (or on-device LM) for real parity.
 */
public class BasicSuggestionProvider implements SuggestionProvider {

    private static final List<String> COMMON =
            Arrays.asList("the", "and", "you", "that", "this", "with", "have", "for");

    @Override
    public List<String> suggest(CharSequence textBeforeCursor) {
        if (textBeforeCursor == null) return Collections.emptyList();
        String s = textBeforeCursor.toString();
        int start = s.length();
        while (start > 0 && !Character.isWhitespace(s.charAt(start - 1))) start--;
        String word = s.substring(start);

        List<String> out = new ArrayList<>(3);
        if (!word.isEmpty()) out.add(word);
        for (String c : COMMON) {
            if (out.size() >= 3) break;
            if (!c.equals(word)) out.add(c);
        }
        return out;
    }
}
