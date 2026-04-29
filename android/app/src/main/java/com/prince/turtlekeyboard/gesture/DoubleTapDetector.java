package com.prince.turtlekeyboard.gesture;

/** Stateful double-tap detector with a configurable window. */
public class DoubleTapDetector {

    private final long windowMs;
    private long lastTapMs = 0L;

    public DoubleTapDetector(long windowMs) {
        this.windowMs = windowMs;
    }

    /** Returns true when this tap landed within {@link #windowMs} of the previous one. */
    public boolean tap() {
        long now = System.currentTimeMillis();
        boolean isDouble = (now - lastTapMs) < windowMs;
        lastTapMs = isDouble ? 0L : now;
        return isDouble;
    }

    public void reset() {
        lastTapMs = 0L;
    }
}
