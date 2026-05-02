package com.prince.turtlekeyboard.integration;

/**
 * One pre-baked shortcut a {@link SuggestedShortcutSource} offers when an app is enrolled.
 * Inserted via the existing slash-command + composer pipeline: the trigger {@code /name}
 * shows up in the Quick Panel for the app it's affine to, picking it drops the user into
 * the inline composer for a confirmation step, and tapping Go commits the {@link #template}
 * (or opens the host's AI flow if a future source returns prompt-style shortcuts).
 */
public final class SuggestedShortcut {

    public final String name;       // "standup"
    public final String label;      // "Standup"
    public final String emoji;      // "🗓️"
    public final String template;   // "Yesterday:\n…\nToday:\n…\nBlockers:\n…"
    public final boolean needsPrompt;

    public SuggestedShortcut(String name, String label, String emoji,
                             String template, boolean needsPrompt) {
        this.name = name;
        this.label = label;
        this.emoji = emoji;
        this.template = template;
        this.needsPrompt = needsPrompt;
    }
}
