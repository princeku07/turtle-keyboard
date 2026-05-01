package com.prince.split.kbd;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.Nullable;

import com.prince.split.SplitStore;

/**
 * Surface integrations talk to. Owned by the IME service; passed into every integration
 * call so integrations don't reach into the IME's internals.
 */
public interface IntegrationContext {

    Context appContext();

    /** Mount a panel view above the keys. Replaces whatever was previously shown. */
    void showPanel(View view);

    /** Detach the active panel view, if any. */
    void hidePanel();

    /** Show a chip above the keys. The {@code onTap} fires when the user taps it. */
    void showChip(ChipSpec spec, Runnable onTap);

    void hideChip();

    /** Transient notice above the keyboard. Auto-hides after {@code autoHideMs}. */
    void showBanner(String text, long autoHideMs);

    /** Loads the launcher icon for {@code pkg}, or null when the host isn't installed
     *  or isn't declared in the manifest's {@code <queries>} block. */
    @Nullable Drawable iconForPackage(String pkg);

    /** Persistent storage for both keyboard and Split UI. */
    SplitStore store();

    /** Commit text into the host editor at the cursor. */
    void commitText(CharSequence text);

    /** Delete {@code n} characters before the cursor in the host editor. */
    void deleteBeforeCursor(int n);

    /**
     * Hand off to a deeper screen the host app provides. The {@code screenId} is a stable
     * string the integration and the host agree on (e.g. {@code "split-detail"}). The host
     * decides which Activity / app handles it — today an in-process Activity, tomorrow
     * possibly a standalone APK launched via explicit-package Intent.
     *
     * <p>No-op when the host doesn't recognize the screen id.
     */
    void openScreen(String screenId);
}
