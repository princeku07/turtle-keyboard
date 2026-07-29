package com.prince.turtlekeyboard.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PromptSuggestionSourceTest {

    @Test
    public void none_returns_empty_for_any_context() {
        assertTrue(PromptSuggestionSource.NONE.suggest("").isEmpty());
        assertTrue(PromptSuggestionSource.NONE.suggest("hello world").isEmpty());
        assertTrue(PromptSuggestionSource.NONE.suggest(null).isEmpty());
    }

    @Test
    public void custom_source_receives_context_and_returns_list() {
        final String[] captured = {null};
        PromptSuggestionSource src = ctx -> {
            captured[0] = ctx;
            return Arrays.asList("a", "b", "c");
        };
        List<String> out = src.suggest("hi there");
        assertEquals("hi there", captured[0]);
        assertNotNull(out);
        assertEquals(3, out.size());
        assertEquals("a", out.get(0));
    }
}
