package com.prince.turtlekeyboard.ai;

import androidx.annotation.Nullable;

/**
 * One-shot delivery channel between {@link ImagePickerActivity} and the IME service for
 * SPI-routed picks. The picker activity can't hold a reference to the IME (it's started
 * from an application context with {@code FLAG_ACTIVITY_NEW_TASK}), so it publishes
 * results into this static bus and the IME consumes them via a single listener registered
 * in {@code onCreate}.
 *
 * <p>Only used for SPI picks dispatched through {@link com.prince.kbd.core.IntegrationContext
 * #pickImage}; the legacy {@code /edit} path still calls {@link LmStudioAiClient#stageEditImage}
 * directly. The {@code requestId} lets the IME match a delivery back to the in-flight callback.
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

    /** Called from {@link ImagePickerActivity#onActivityResult}. No-op if no listener
     *  is currently registered (e.g. the IME was destroyed while the picker was up). */
    public static void deliver(int requestId, @Nullable byte[] bytes, @Nullable String mime) {
        Listener l = listener;
        if (l != null) l.onPicked(requestId, bytes, mime);
    }
}
