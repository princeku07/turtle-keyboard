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

    /** Delete one word before the cursor: trailing whitespace, then back to the next whitespace. */
    public void deleteWord() {
        InputConnection ic = connection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(2048, 0);
        if (before == null || before.length() == 0) return;
        int i = before.length();
        while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(before.charAt(i - 1))) i--;
        int toDelete = before.length() - i;
        if (toDelete > 0) ic.deleteSurroundingText(toDelete, 0);
    }

    /**
     * Delete one sentence before the cursor: trailing whitespace, then back to the last
     * sentence-ending punctuation or line break (or the lookback boundary).
     */
    public void deleteSentence() {
        InputConnection ic = connection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(8192, 0);
        if (before == null || before.length() == 0) return;
        int i = before.length();
        while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
        while (i > 0) {
            char c = before.charAt(i - 1);
            if (c == '.' || c == '!' || c == '?' || c == '\n') break;
            i--;
        }
        int toDelete = before.length() - i;
        if (toDelete > 0) ic.deleteSurroundingText(toDelete, 0);
    }

    /**
     * Delete every character on both sides of the cursor. Uses explicit deletion
     * because {@code performContextMenuAction(selectAll)} + commitText races in some editors.
     */
    public void clearAll() {
        InputConnection ic = connection();
        if (ic == null) return;
        ic.finishComposingText();
        CharSequence before = ic.getTextBeforeCursor(Integer.MAX_VALUE, 0);
        CharSequence after = ic.getTextAfterCursor(Integer.MAX_VALUE, 0);
        int beforeLen = before == null ? 0 : before.length();
        int afterLen = after == null ? 0 : after.length();
        if (beforeLen > 0 || afterLen > 0) {
            ic.deleteSurroundingText(beforeLen, afterLen);
        }
    }
}
