package com.prince.turtlekeyboard.command;

import androidx.annotation.Nullable;

import com.prince.kbd.core.CommandSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure registry of slash commands populated from {@link com.prince.kbd.core.CommandProvider}s.
 * Ranking for a given host pkg falls through user pins → default affinity → registration order.
 */
public class CommandRegistry {

    public static class Entry {
        public final String name;
        public final String label;
        public final String emoji;
        public final boolean needsPrompt;
        @Nullable public final CommandSpec.Handler handler;
        public final Set<String> affinityPkgs;
        /** Status copy shown in the loader (e.g. "Generating image"); null falls back to "/" + name. */
        @Nullable public final String loadingMessage;

        public Entry(String name, String label, String emoji, boolean needsPrompt) {
            this(name, label, emoji, needsPrompt, null, Collections.emptySet(), null);
        }

        public Entry(String name, String label, String emoji, boolean needsPrompt,
                     @Nullable CommandSpec.Handler handler) {
            this(name, label, emoji, needsPrompt, handler, Collections.emptySet(), null);
        }

        public Entry(String name, String label, String emoji, boolean needsPrompt,
                     @Nullable CommandSpec.Handler handler, Set<String> affinityPkgs) {
            this(name, label, emoji, needsPrompt, handler, affinityPkgs, null);
        }

        public Entry(String name, String label, String emoji, boolean needsPrompt,
                     @Nullable CommandSpec.Handler handler, Set<String> affinityPkgs,
                     @Nullable String loadingMessage) {
            this.name = name;
            this.label = label;
            this.emoji = emoji;
            this.needsPrompt = needsPrompt;
            this.handler = handler;
            this.affinityPkgs = affinityPkgs == null ? Collections.emptySet() : affinityPkgs;
            this.loadingMessage = loadingMessage;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, PromptSuggestionSource> suggestionSources = new HashMap<>();
    private final Map<String, ImagePickerKind> imagePickers = new HashMap<>();
    private final Map<String, PromptDecorator> promptDecorators = new HashMap<>();
    @Nullable private UserCommandPins pins;

    public void setPins(@Nullable UserCommandPins pins) {
        this.pins = pins;
    }

    /** Overrides what the suggestion strip shows while this command is in prompt mode.
     *  Pass {@code null} to remove the override and fall back to the global dictionary. */
    public void setSuggestionSource(String commandName, @Nullable PromptSuggestionSource source) {
        if (commandName == null) return;
        String key = commandName.toLowerCase();
        if (source == null) suggestionSources.remove(key);
        else suggestionSources.put(key, source);
    }

    /** Returns the registered override for {@code commandName}, or null if none. */
    @Nullable
    public PromptSuggestionSource suggestionSourceFor(@Nullable String commandName) {
        return commandName == null ? null : suggestionSources.get(commandName.toLowerCase());
    }

    /** Declares whether the IME should pre-launch an image picker on prompt start. */
    public void setImagePicker(String commandName, @Nullable ImagePickerKind kind) {
        if (commandName == null) return;
        String key = commandName.toLowerCase();
        if (kind == null || kind == ImagePickerKind.NONE) imagePickers.remove(key);
        else imagePickers.put(key, kind);
    }

    /** Returns the picker kind for {@code commandName}; {@link ImagePickerKind#NONE} if unset. */
    public ImagePickerKind imagePickerFor(@Nullable String commandName) {
        if (commandName == null) return ImagePickerKind.NONE;
        ImagePickerKind k = imagePickers.get(commandName.toLowerCase());
        return k == null ? ImagePickerKind.NONE : k;
    }

    /** Per-command prompt-mode UI extras (preset chips, etc.). Pass null to remove. */
    public void setPromptDecorator(String commandName, @Nullable PromptDecorator decorator) {
        if (commandName == null) return;
        String key = commandName.toLowerCase();
        if (decorator == null) promptDecorators.remove(key);
        else promptDecorators.put(key, decorator);
    }

    @Nullable
    public PromptDecorator promptDecoratorFor(@Nullable String commandName) {
        return commandName == null ? null : promptDecorators.get(commandName.toLowerCase());
    }

    /** Register a command. Last writer wins on name collision. */
    public void register(CommandSpec spec) {
        entries.put(spec.name.toLowerCase(),
                new Entry(spec.name, spec.label, spec.emoji, spec.needsPrompt,
                        spec.handler, spec.affinityPkgs, spec.loadingMessage));
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

    /** Commands matching {@code prefix}, ranked for {@code currentPkg} via {@link #allSortedFor}. */
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

        // Tier 1: user pins in order; unresolved names (uninstalled/renamed) silently skipped.
        List<String> pinNames = pins == null ? Collections.emptyList() : pins.pinsFor(currentPkg);
        Set<String> pinned = new HashSet<>();
        List<Entry> pinnedEntries = new ArrayList<>();
        for (String name : pinNames) {
            Entry e = entries.get(name.toLowerCase());
            if (e != null && pinned.add(e.name.toLowerCase())) pinnedEntries.add(e);
        }

        // Tiers 2+3: default affinity vs the rest, preserving registration order.
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
