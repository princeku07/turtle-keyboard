package com.prince.turtlekeyboard.command;

/**
 * In-keyboard command buffer with two phases:
 *   NAME   — user is still typing "/foo"; rendered in the banner.
 *   PROMPT — command name is locked in and the keyboard is collecting its argument
 *            (e.g. the URL after "/search "); rendered in the command panel.
 *
 * All keystrokes captured by the IME while {@link #isActive()} go here instead of the
 * input connection, so the slash invocation never reaches the editor.
 */
public class CommandComposer {

    public enum Mode { NAME, PROMPT }

    public interface Ui {
        void onNameChanged(String displayed);
        void onPromptStart(String commandName);
        void onPromptChanged(String commandName, String query);
        void onComposeEnd();
    }

    private final Ui ui;
    private final StringBuilder buf = new StringBuilder();
    private Mode mode;
    private String commandName;

    public CommandComposer(Ui ui) {
        this.ui = ui;
    }

    public boolean isActive() { return mode != null; }
    public Mode mode() { return mode; }
    public String commandName() { return commandName; }
    public String query() { return mode == Mode.PROMPT ? buf.toString() : ""; }
    public String nameText() { return mode == Mode.NAME ? buf.toString() : ""; }

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
        ui.onPromptStart(commandName);
    }

    public void appendChar(char c) {
        if (mode == null) return;
        buf.append(c);
        if (mode == Mode.NAME) ui.onNameChanged(buf.toString());
        else ui.onPromptChanged(commandName, buf.toString());
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
        // PROMPT mode
        if (buf.length() == 0) { cancel(); return true; }
        buf.deleteCharAt(buf.length() - 1);
        ui.onPromptChanged(commandName, buf.toString());
        return true;
    }

    public void cancel() {
        mode = null;
        commandName = null;
        buf.setLength(0);
        ui.onComposeEnd();
    }
}
