package com.prince.turtlekeyboard.command;

import androidx.annotation.Nullable;

import com.prince.kbd.core.CommandSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure registry of slash commands. Starts empty — every entry comes from a
 * {@link com.prince.kbd.core.CommandProvider}: built-in AI commands ({@code BuiltinAiCommands}
 * in {@code :app}) and feature integrations ({@code SplitIntegration}, {@code NotionIntegration},
 * etc.). Commands with a non-null {@link Entry#handler} run locally; the rest go to the AI
 * backend via the dispatcher.
 *
 * <p>Ranking for a given host pkg is a three-tier fall-through:
 * <ol>
 *   <li><b>User pins</b> for the pkg — in the order the user set them.</li>
 *   <li><b>Default affinity</b> — commands whose {@code affinityPkgs} contains the pkg.</li>
 *   <li><b>Rest</b> — everything else, in registration order.</li>
 * </ol>
 */
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
    @Nullable private UserCommandPins pins;

    /** @param pins optional source of user-configurable per-pkg ordering. Null means
     *  ranking falls back to default affinity only. */
    public void setPins(@Nullable UserCommandPins pins) {
        this.pins = pins;
    }

    /** Register a command. Last writer wins on name collision. */
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
     * Commands whose name starts with {@code prefix}, ranked for {@code currentPkg}
     * via the same three-tier order as {@link #allSortedFor}. Drives the autocomplete
     * strip while the user types {@code /…}.
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

        // Tier 1 — user pins, in the user's chosen order. Names that no longer resolve
        // (command was uninstalled or renamed) are silently skipped.
        List<String> pinNames = pins == null ? Collections.emptyList() : pins.pinsFor(currentPkg);
        Set<String> pinned = new HashSet<>();
        List<Entry> pinnedEntries = new ArrayList<>();
        for (String name : pinNames) {
            Entry e = entries.get(name.toLowerCase());
            if (e != null && pinned.add(e.name.toLowerCase())) pinnedEntries.add(e);
        }

        // Tiers 2 + 3 — default affinity vs the rest, preserving registration order.
        List<Entry> affine = new ArrayList<>();
        List<Entry> rest = new ArrayList<>();
        for (Entry e : base) {
            if (pinned.contains(e.name.toLowerCase())) continue;
            if (e.affinityPkgs.contains(currentPkg)) affine.add(e);
            else rest.add(e);
        }

        List<Entry> out = new ArrayList<>(base.size());
        out.addAll(pinnedEntries);
        out.addAll(affine);
        out.addAll(rest);
        return Collections.unmodifiableList(out);
    }
}
