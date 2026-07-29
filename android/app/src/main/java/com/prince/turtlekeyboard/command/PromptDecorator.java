package com.prince.turtlekeyboard.command;

import java.util.List;
import java.util.function.Consumer;

/**
 * Per-command extension point for UI extras shown during prompt mode (preset
 * chips, secondary strips, etc.). The IME calls the decorator's hooks at the
 * standard composer lifecycle points; the decorator talks back through the
 * narrow {@link Ui} adapter so it doesn't depend on view internals.
 */
public interface PromptDecorator {

    default void onStart(Ui ui) {}
    default void onQueryChanged(Ui ui, String query) {}
    default void onEnd(Ui ui) {}

    /** IME-provided façade for the surfaces decorators may drive. */
    interface Ui {
        /** Text-chip preset row (e.g. /us scenarios). */
        void showTextPresets(List<String> presets, Consumer<String> onPick);

        /** Image-preview preset row (e.g. /style thumbnails). */
        void showImagePreviewPresets(List<String> presets, Consumer<String> onPick);

        /** Hide both preset rows. */
        void hidePresets();
    }
}
