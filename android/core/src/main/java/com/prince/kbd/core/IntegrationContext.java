package com.prince.kbd.core;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.Nullable;

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

    /**
     * Module-scoped persistent storage. Each call returns a {@link KeyValueStore} whose
     * keys are isolated from every other namespace — so {@code store("notion")} can write
     * {@code "access_token"} without colliding with {@code store("slack")}'s key of the
     * same name. The app provides the underlying adapter; modules never touch it directly.
     */
    KeyValueStore store(String namespace);

    /** Lookup for known host apps. Integrations use this to decide when to activate. */
    AppProfileRegistry profiles();

    /** AI primitive — call Gemini directly with the integration's own system prompt.
     *  Replaces the legacy {@code llm()} / {@code images()} ports; each command owns its
     *  prompt and dispatch logic rather than routing through a god-class AI client. Use
     *  is opt-in — modules that don't need AI simply don't call this. */
    GeminiService ai();

    /** Cross-module Google OAuth. Modules declare the scopes they need per call; tokens
     *  are cached and shared across modules, so a feature that asks for a scope already
     *  granted to another feature reuses the same token without a second consent dialog.
     *  Same opt-in pattern as {@link #ai()} — modules that don't talk to Google APIs
     *  simply don't call this. */
    GoogleAuth googleAuth();

    /** Commit text into the host editor at the cursor. */
    void commitText(CharSequence text);

    /** Delete {@code n} characters before the cursor in the host editor. */
    void deleteBeforeCursor(int n);

    /**
     * Hand off to a deeper screen the host app provides. The {@code screenId} is a stable
     * string the integration and the host agree on (e.g. {@code "split-detail"}). The host
     * decides which Activity / view controller handles it.
     *
     * <p>No-op when the host doesn't recognize the screen id.
     */
    void openScreen(String screenId);
}
