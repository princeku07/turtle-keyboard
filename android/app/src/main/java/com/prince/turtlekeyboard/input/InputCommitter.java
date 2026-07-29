package com.prince.turtlekeyboard.input;

import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

/**
 * Thin wrapper over the active {@link InputConnection}. Callers grab the current
 * connection via {@link #connection()} on each use, since the IME rebinds per session.
 */
public class InputCommitter {

    public interface ConnectionProvider {
        InputConnection get();
    }

    private final ConnectionProvider provider;

    public InputCommitter(ConnectionProvider provider) {
        this.provider = provider;
    }

    public InputConnection connection() {
        return provider.get();
    }

    public void commitChar(char c) {
        InputConnection ic = connection();
        if (ic != null) ic.commitText(String.valueOf(c), 1);
    }

    public void commitText(CharSequence text) {
        InputConnection ic = connection();
        if (ic != null) ic.commitText(text, 1);
    }

    public void backspace() {
        InputConnection ic = connection();
        if (ic == null) return;
        CharSequence selected = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(selected)) {
            ic.commitText("", 1);
        } else {
            ic.deleteSurroundingText(1, 0);
        }
    }

    public void sendEnter() {
        InputConnection ic = connection();
        if (ic == null) return;
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
    }

    /** Returns up to {@code n} characters before the cursor, or empty string. */
    public CharSequence textBeforeCursor(int n) {
        InputConnection ic = connection();
        if (ic == null) return "";
        CharSequence cs = ic.getTextBeforeCursor(n, 0);
        return cs == null ? "" : cs;
    }

    /** Returns up to {@code n} characters after the cursor, or empty string. */
    public CharSequence textAfterCursor(int n) {
        InputConnection ic = connection();
        if (ic == null) return "";
        CharSequence cs = ic.getTextAfterCursor(n, 0);
        return cs == null ? "" : cs;
    }

    public void deleteBeforeCursor(int n) {
        InputConnection ic = connection();
        if (ic != null) ic.deleteSurroundingText(n, 0);
    }

    /** Concatenation of text-before-cursor + selected + text-after-cursor. Capped at 8K chars. */
    public CharSequence getAllText() {
        InputConnection ic = connection();
        if (ic == null) return "";
        CharSequence before = ic.getTextBeforeCursor(4000, 0);
        CharSequence sel = ic.getSelectedText(0);
        CharSequence after = ic.getTextAfterCursor(4000, 0);
        StringBuilder sb = new StringBuilder();
        if (before != null) sb.append(before);
        if (sel != null) sb.append(sel);
        if (after != null) sb.append(after);
        return sb;
    }

    /** Deletes everything around the cursor then commits {@code text}. Silent overwrite. */
    public void replaceAll(CharSequence text) {
        InputConnection ic = connection();
        if (ic == null) return;
        ic.beginBatchEdit();
        try {
            // Clear selection first so deleteSurroundingText covers a clean cursor.
            CharSequence sel = ic.getSelectedText(0);
            if (!TextUtils.isEmpty(sel)) ic.commitText("", 1);
            ic.deleteSurroundingText(4000, 4000);
            ic.commitText(text == null ? "" : text, 1);
        } finally {
            ic.endBatchEdit();
        }
    }
}
