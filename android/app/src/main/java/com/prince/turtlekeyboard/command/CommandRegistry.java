package com.prince.turtlekeyboard.command;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registered slash commands per PRD §7.1. The dispatcher consults this to decide whether
 *  a parsed command is known before round-tripping to the backend. */
public class CommandRegistry {

    public static class Entry {
        public final String name;
        public final String label;
        public final String emoji;
        public final boolean needsPrompt;

        public Entry(String name, String label, String emoji, boolean needsPrompt) {
            this.name = name;
            this.label = label;
            this.emoji = emoji;
            this.needsPrompt = needsPrompt;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public CommandRegistry() {
        for (Entry e : Arrays.asList(
                new Entry("cap", "Image", "🎨", true),
                new Entry("fix", "Fix", "✏️", false),
                new Entry("tone", "Tone", "🎭", true),
                new Entry("reply", "Reply", "💬", false),
                new Entry("tl", "Translate", "🌐", true),
                new Entry("search", "Search", "🔍", true)
        )) entries.put(e.name, e);
    }

    public boolean has(String name) {
        return name != null && entries.containsKey(name.toLowerCase());
    }

    public Entry get(String name) {
        return name == null ? null : entries.get(name.toLowerCase());
    }

    public List<Entry> all() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(entries.values()));
    }
}
