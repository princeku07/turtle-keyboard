package com.prince.turtlekeyboard.command;

/**
 * In-keyboard command buffer with two phases:
 *   NAME   — user is still typing "/foo"; rendered in the banner.
 *   PROMPT — command name is locked in and the keyboard is collecting its argument
 *            (e.g. the URL after "/search "); rendered in the command panel.
 *
 * <p>All keystrokes captured by the IME while {@link #isActive()} go here instead
 * of the input connection, so the slash invocation never reaches the editor.
 *
 * <p>In PROMPT mode the buffer carries an explicit insertion cursor
 * ({@link #promptCursor()}) so the panel can render a movable caret and the user
 * can tap/drag inside the typed prompt to edit it. Subsequent {@link #appendChar}
 * and {@link #backspace} insert / delete at the cursor's current position rather
 * than always operating at the end of the buffer.
 */
public class CommandComposer {

    public enum Mode { NAME, PROMPT }

    public interface Ui {
        void onNameChanged(String displayed);
        void onPromptStart(String commandName);
        /**
         * Fires whenever the prompt's buffer <i>or</i> caret position changes —
         * including pure cursor moves with no text edit, so the panel can move
         * its rendered caret in lock-step with drags.
         */
        void onPromptChanged(String commandName, String query, int cursorPos);
        void onComposeEnd();
    }

    private final Ui ui;
    private final StringBuilder buf = new StringBuilder();
    private Mode mode;
    private String commandName;
    /** Cursor index within the prompt buffer (0..buf.length()). Only meaningful
     *  in {@link Mode#PROMPT}; resets to 0 on prompt entry and tracks edits. */
    private int promptCursor;

    public CommandComposer(Ui ui) {
        this.ui = ui;
    }

    public boolean isActive() { return mode != null; }
    public Mode mode() { return mode; }
    public String commandName() { return commandName; }
    public String query() { return mode == Mode.PROMPT ? buf.toString() : ""; }
    public String nameText() { return mode == Mode.NAME ? buf.toString() : ""; }
    /** Current insertion-cursor position in the prompt buffer. Always within
     *  {@code [0, buf.length()]}. Meaningless when {@code mode != PROMPT}. */
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
            // PROMPT: insert at cursor, advance cursor.
            buf.insert(promptCursor, c);
            promptCursor++;
            ui.onPromptChanged(commandName, buf.toString(), promptCursor);
        }
    }

    /** Append a whole string at once — used for paste. No-op when not composing.
     *  In PROMPT mode the inserted block lands at the current cursor and the
     *  cursor advances by the string's length. */
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
        // PROMPT mode — delete the char to the left of the cursor.
        if (buf.length() == 0) { cancel(); return true; }
        if (promptCursor == 0) {
            // Caret at the very start with text still present: do nothing
            // (rather than canceling — the user has typed something they
            // want to keep, they just dragged the caret all the way left).
            return true;
        }
        buf.deleteCharAt(promptCursor - 1);
        promptCursor--;
        ui.onPromptChanged(commandName, buf.toString(), promptCursor);
        return true;
    }

    /** Move the prompt caret to {@code pos} (clamped to the buffer's bounds).
     *  No-op if not in PROMPT mode. Notifies the UI so the rendered caret
     *  can follow without changing the typed text. */
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
