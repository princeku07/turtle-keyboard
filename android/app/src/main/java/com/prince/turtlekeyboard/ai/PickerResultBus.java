package com.prince.turtlekeyboard.ai;

import androidx.annotation.Nullable;

/**
 * Static one-shot bus between {@link ImagePickerActivity} and the IME for SPI-routed
 * picks; the picker activity has no IME reference. {@code requestId} matches a delivery
 * back to the in-flight integration callback.
 */
public final class PickerResultBus {

    public interface Listener {
        /** Main thread. {@code bytes == null} means cancel or read failure. */
        void onPicked(int requestId, @Nullable byte[] bytes, @Nullable String mime);
    }

    private static volatile Listener listener;

    private PickerResultBus() {}

    public static void setListener(@Nullable Listener l) {
        listener = l;
    }

    /** No-op if no listener is registered. */
    public static void deliver(int requestId, @Nullable byte[] bytes, @Nullable String mime) {
        Listener l = listener;
        if (l != null) l.onPicked(requestId, bytes, mime);
    }
}
