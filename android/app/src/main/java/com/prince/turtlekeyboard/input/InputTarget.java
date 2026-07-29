package com.prince.turtlekeyboard.input;

/**
 * A component that owns keyboard input while focused — printable keys,
 * backspace, the Done key, and voice transcriptions all route here instead
 * of the host editor. The IME holds at most one active target at a time.
 *
 * <p>Components register/unregister themselves via {@link ActiveChangeListener}.
 * When a user taps a prompt field (AI panel) or the emoji search bar, the
 * panel flips into input mode and fires the listener with {@code active=true};
 * the IME stores it as the current target. On exit, the panel fires with
 * {@code active=false} and the IME clears the slot.</p>
 *
 * <p>This replaces per-component conditionals in {@code onKey} /
 * {@code voiceSink.onFinal}, so adding a new input-owning view requires no
 * IME changes — just implement {@link InputTarget} and wire the listener.</p>
 */
public interface InputTarget {

    /** Receive one printable character (UTF-16 code unit). */
    void appendChar(char c);

    /** Handle a backspace. Targets may choose to delete a char, exit input mode, etc. */
    void onBackspace();

    /** Optional Enter/Done key handler. Default: ignore. */
    default void onDone() {}

    /** Append a chunk of text (e.g. a voice transcription). Default: per-char loop. */
    default void appendText(String text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) appendChar(text.charAt(i));
    }

    /** Fires whenever a target enters/exits input mode. The IME uses this to
     *  swap its active slot. */
    interface ActiveChangeListener {
        void onActiveChanged(InputTarget target, boolean active);
    }
}
