package com.prince.turtlekeyboard.integration;

/**
 * One pre-baked shortcut a {@link SuggestedShortcutSource} offers when an app is
 * enrolled. The trigger {@code /name} shows up in the Quick Panel; tapping Go commits
 * the {@link #template} into the host editor.
 */
public final class SuggestedShortcut {

    public final String name;
    public final String label;
    public final String emoji;
    public final String template;
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
