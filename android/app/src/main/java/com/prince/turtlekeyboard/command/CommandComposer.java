package com.prince.turtlekeyboard.command;

/**
 * In-keyboard command buffer with two phases: NAME (typing "/foo", rendered in the
 * banner) and PROMPT (collecting the argument after the command name, rendered in
 * the command panel). PROMPT mode carries an explicit insertion cursor so taps and
 * drags can move the caret without retyping.
 */
public class CommandComposer {

    public enum Mode { NAME, PROMPT }

    public interface Ui {
        void onNameChanged(String displayed);
        void onPromptStart(String commandName);
        /** Fires on any prompt buffer or caret change, including pure cursor moves. */
        void onPromptChanged(String commandName, String query, int cursorPos);
        void onComposeEnd();
    }

    private final Ui ui;
    private final StringBuilder buf = new StringBuilder();
    private Mode mode;
    private String commandName;
    /** Cursor index within the prompt buffer; only meaningful in {@link Mode#PROMPT}. */
    private int promptCursor;

    public CommandComposer(Ui ui) {
        this.ui = ui;
    }

    public boolean isActive() { return mode != null; }
    public Mode mode() { return mode; }
    public String commandName() { return commandName; }
    public String query() { return mode == Mode.PROMPT ? buf.toString() : ""; }
    public String nameText() { return mode == Mode.NAME ? buf.toString() : ""; }
    public int promptCursor() { return promptCursor; }

    public void startName() {
        mode = Mode.NAME;
        commandName = null;
        buf.setLength(0);
        buf.append('/');
        ui.onNameChanged(buf.toString());
    }

    public void enterPromptMode(String commandName) {
        this.mode = Mode.PROMPT;
        this.commandName = commandName;
        buf.setLength(0);
        promptCursor = 0;
        ui.onPromptStart(commandName);
    }

    public void appendChar(char c) {
        if (mode == null) return;
        if (mode == Mode.NAME) {
            buf.append(c);
            ui.onNameChanged(buf.toString());
        } else {
            buf.insert(promptCursor, c);
            promptCursor++;
            ui.onPromptChanged(commandName, buf.toString(), promptCursor);
        }
    }

    /** Insert {@code s} at the current cursor (paste path). No-op when not composing. */
    public void appendString(String s) {
        if (mode == null || s == null || s.isEmpty()) return;
        if (mode == Mode.NAME) {
            buf.append(s);
            ui.onNameChanged(buf.toString());
        } else {
            buf.insert(promptCursor, s);
            promptCursor += s.length();
            ui.onPromptChanged(commandName, buf.toString(), promptCursor);
        }
    }

    /** True if backspace consumed compose state and the IME should not fall through. */
    public boolean backspace() {
        if (mode == null) return false;
        if (mode == Mode.NAME) {
            if (buf.length() <= 1) { cancel(); return true; }
            buf.deleteCharAt(buf.length() - 1);
            ui.onNameChanged(buf.toString());
            return true;
        }
        if (buf.length() == 0) { cancel(); return true; }
        if (promptCursor == 0) {
            // Caret at start with text present: do nothing — user has typed something they want to keep.
            return true;
        }
        buf.deleteCharAt(promptCursor - 1);
        promptCursor--;
        ui.onPromptChanged(commandName, buf.toString(), promptCursor);
        return true;
    }

    /** Move the prompt caret to {@code pos}, clamped to buffer bounds. No-op outside PROMPT mode. */
    public void setPromptCursor(int pos) {
        if (mode != Mode.PROMPT) return;
        int clamped = Math.max(0, Math.min(buf.length(), pos));
        if (clamped == promptCursor) return;
        promptCursor = clamped;
        ui.onPromptChanged(commandName, buf.toString(), promptCursor);
    }

    public void cancel() {
        mode = null;
        commandName = null;
        buf.setLength(0);
        promptCursor = 0;
        ui.onComposeEnd();
    }
}
