package com.prince.turtlekeyboard.keyboard;

import android.inputmethodservice.KeyboardView;

import com.prince.turtlekeyboard.gesture.DoubleTapDetector;

/**
 * Tracks shift / caps-lock state for the QWERTY layout. Double-tapping shift toggles caps lock.
 * Does not own the view — manipulates whichever KeyboardView is bound to it.
 */
public class ShiftController {

    private final DoubleTapDetector doubleTap = new DoubleTapDetector(300L);
    private KeyboardView view;
    private boolean capsLock = false;

    public void attach(KeyboardView view) {
        this.view = view;
    }

    public void reset() {
        capsLock = false;
        if (view != null) view.setShifted(false);
    }

    public boolean isUpper() {
        return view != null && (view.isShifted() || capsLock);
    }

    public boolean isCapsLock() {
        return capsLock;
    }

    public void onShiftPress() {
        if (view == null) return;
        if (doubleTap.tap()) {
            capsLock = !capsLock;
            view.setShifted(true);
        } else {
            capsLock = false;
            view.setShifted(!view.isShifted());
        }
    }

    /** Auto-clear shift after a non-shifted character is committed. */
    public void onCharCommitted() {
        if (view != null && view.isShifted() && !capsLock) {
            view.setShifted(false);
        }
    }

    /** Reapply current shift state when the layout swaps back to QWERTY. */
    public void reapply() {
        if (view != null) view.setShifted(capsLock);
    }
}
