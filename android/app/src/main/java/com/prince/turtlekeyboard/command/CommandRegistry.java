package com.prince.turtlekeyboard.command;

import androidx.annotation.Nullable;

import com.prince.split.kbd.CommandSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registered slash commands. Entries with a non-null {@link Entry#handler} run locally
 *  via the {@link CommandSpec.Handler}; the rest go to the AI backend. */
public class CommandRegistry {

    public static class Entry {
        public final String name;
        public final String label;
        public final String emoji;
        public final boolean needsPrompt;
        @Nullable public final CommandSpec.Handler handler;

        public Entry(String name, String label, String emoji, boolean needsPrompt) {
            this(name, label, emoji, needsPrompt, null);
        }

        public Entry(String name, String label, String emoji, boolean needsPrompt,
                     @Nullable CommandSpec.Handler handler) {
            this.name = name;
            this.label = label;
            this.emoji = emoji;
            this.needsPrompt = needsPrompt;
            this.handler = handler;
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
                new Entry("search", "Search", "🔍", true),
                new Entry("ask", "Ask", "❓", true),
                new Entry("org", "Organize", "🗂️", true)
        )) entries.put(e.name, e);
    }

    /** Register an integration-contributed command. Last writer wins on name collision. */
    public void register(CommandSpec spec) {
        entries.put(spec.name.toLowerCase(),
                new Entry(spec.name, spec.label, spec.emoji, spec.needsPrompt, spec.handler));
    }

    public boolean has(String name) {
        return name != null && entries.containsKey(name.toLowerCase());
    }

    public Entry get(String name) {
        return name == null ? null : entries.get(name.toLowerCase());
    }

    public List<Entry> all() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }
}
