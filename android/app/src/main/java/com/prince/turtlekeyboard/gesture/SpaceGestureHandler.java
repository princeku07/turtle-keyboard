package com.prince.turtlekeyboard.gesture;

/** Encapsulates space-bar gestures; currently double-tap-space → Quick Panel. */
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
