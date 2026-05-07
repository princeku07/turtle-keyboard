package com.prince.turtlekeyboard.command;

import com.prince.turtlekeyboard.input.InputCommitter;

/**
 * Watches text-before-cursor and notifies when the user has typed a complete slash command
 * (terminated by a space, enter, or punctuation). The service calls {@link #onTextChanged}
 * after every commit so the detector can re-evaluate.
 */
public class SlashCommandDetector {

    public interface Listener {
        /** Fired when a slash command is detected and ready to dispatch. */
        void onCommand(SlashCommand cmd);
    }

    private static final int LOOKBACK = 200;

    private final InputCommitter committer;
    private final CommandRegistry registry;
    private final Listener listener;

    public SlashCommandDetector(InputCommitter committer, CommandRegistry registry, Listener listener) {
        this.committer = committer;
        this.registry = registry;
        this.listener = listener;
    }

    /** Call after every text mutation. */
    public void onTextChanged() {
        CharSequence before = committer.textBeforeCursor(LOOKBACK);
        if (before.length() == 0) return;
        char last = before.charAt(before.length() - 1);
        if (last != ' ' && last != '\n') return;

        // Find the most recent '/' that begins a token (start-of-input or after whitespace).
        String s = before.toString();
        int slash = -1;
        for (int i = s.length() - 2; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '/') {
                if (i == 0 || Character.isWhitespace(s.charAt(i - 1))) slash = i;
                break;
            }
            if (c == '\n') break;
        }
        if (slash < 0) return;

        String token = s.substring(slash, s.length() - 1);
        SlashCommand cmd = SlashCommand.parse(token);
        if (cmd == null || !registry.has(cmd.name)) return;
        listener.onCommand(cmd);
    }
}
