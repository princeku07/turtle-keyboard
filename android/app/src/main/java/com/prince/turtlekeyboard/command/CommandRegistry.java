package com.prince.turtlekeyboard.command;

import androidx.annotation.Nullable;

import com.prince.split.kbd.CommandSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registered slash commands. Entries with a non-null {@link Entry#handler} run locally
 *  via the {@link CommandSpec.Handler}; the rest go to the AI backend. */
public class CommandRegistry {

    public static class Entry {
        public final String name;
        public final String label;
        public final String emoji;
        public final boolean needsPrompt;
        @Nullable public final CommandSpec.Handler handler;
        public final Set<String> affinityPkgs;

        public Entry(String name, String label, String emoji, boolean needsPrompt) {
            this(name, label, emoji, needsPrompt, null, Collections.emptySet());
        }

        public Entry(String name, String label, String emoji, boolean needsPrompt,
                     @Nullable CommandSpec.Handler handler) {
            this(name, label, emoji, needsPrompt, handler, Collections.emptySet());
        }

        public Entry(String name, String label, String emoji, boolean needsPrompt,
                     @Nullable CommandSpec.Handler handler, Set<String> affinityPkgs) {
            this.name = name;
            this.label = label;
            this.emoji = emoji;
            this.needsPrompt = needsPrompt;
            this.handler = handler;
            this.affinityPkgs = affinityPkgs == null ? Collections.emptySet() : affinityPkgs;
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
                new Entry(spec.name, spec.label, spec.emoji, spec.needsPrompt,
                        spec.handler, spec.affinityPkgs));
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

    /**
     * Same set as {@link #all()}, but with commands whose {@link Entry#affinityPkgs}
     * contains {@code currentPkg} hoisted to the front. Order is otherwise preserved
     * (stable sort), so non-affine commands keep their registration order.
     *
     * <p>Affinity is purely a ranking signal — every command stays in the list. {@code
     * /split} in WhatsApp is just lower in the grid than {@code /split} in GPay.
     */
    /**
     * Commands whose name starts with {@code prefix}, affinity-sorted for {@code
     * currentPkg}. Drives the autocomplete strip while the user types {@code /…}: each
     * keystroke narrows the strip; commands with affinity for the current app float to
     * the front. Empty {@code prefix} returns the same set as {@link #allSortedFor}.
     */
    public List<Entry> matchesFor(@Nullable String prefix, @Nullable String currentPkg) {
        List<Entry> ranked = allSortedFor(currentPkg);
        if (prefix == null || prefix.isEmpty()) return ranked;
        String p = prefix.toLowerCase();
        List<Entry> out = new ArrayList<>();
        for (Entry e : ranked) {
            if (e.name.toLowerCase().startsWith(p)) out.add(e);
        }
        return Collections.unmodifiableList(out);
    }

    public List<Entry> allSortedFor(@Nullable String currentPkg) {
        List<Entry> base = new ArrayList<>(entries.values());
        if (currentPkg == null) return Collections.unmodifiableList(base);
        List<Entry> affine = new ArrayList<>();
        List<Entry> rest = new ArrayList<>();
        for (Entry e : base) {
            if (e.affinityPkgs.contains(currentPkg)) affine.add(e);
            else rest.add(e);
        }
        affine.addAll(rest);
        return Collections.unmodifiableList(affine);
    }
}
