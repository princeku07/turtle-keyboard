package com.prince.turtlekeyboard.keyboard;

import android.inputmethodservice.Keyboard;

public final class Keycodes {
    public static final int DELETE = Keyboard.KEYCODE_DELETE;
    public static final int SHIFT = Keyboard.KEYCODE_SHIFT;
    public static final int DONE = Keyboard.KEYCODE_DONE;
    public static final int MODE_CHANGE = Keyboard.KEYCODE_MODE_CHANGE;
    public static final int SPACE = 32;
    public static final int SLASH = 47;
    public static final int ENTER = 10;
    /** Synthetic keycode for the dedicated mic key. Negative so it never collides
     *  with a printable character or the framework's reserved negative codes. */
    public static final int MIC = -10;

    private Keycodes() {}
}
