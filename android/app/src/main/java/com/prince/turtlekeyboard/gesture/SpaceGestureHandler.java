package com.prince.turtlekeyboard.gesture;

/**
 * Encapsulates space-bar gestures. Currently: double-tap-space → Quick Panel (PRD §6.6).
 * The host wires the listener to a Quick Panel toggler.
 */
public class SpaceGestureHandler {

    public interface Listener {
        void onDoubleTapSpace();
    }

    private final DoubleTapDetector doubleTap = new DoubleTapDetector(300L);
    private final Listener listener;

    public SpaceGestureHandler(Listener listener) {
        this.listener = listener;
    }

    public void onSpacePressed() {
        if (doubleTap.tap()) listener.onDoubleTapSpace();
    }
}
