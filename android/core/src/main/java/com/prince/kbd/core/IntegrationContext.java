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

    /** LLM completion service. Modules that structure free text into API payloads talk to
     *  the same model as the rest of the IME. Use is opt-in — modules that don't need AI
     *  simply don't call this. */
    LlmService llm();

    /** Image generation service. Same opt-in pattern as {@link #llm()} — only modules
     *  that actually generate images call this. The provider (OpenAI / Gemini / …) is
     *  composed in the app and may include fallback chains; modules see only the port. */
    ImageService images();

    /** Commit text into the host editor at the cursor. */
    void commitText(CharSequence text);

    /** Delete {@code n} characters before the cursor in the host editor. */
    void deleteBeforeCursor(int n);

    /**
     * Hand off to a deeper screen the host app provides. The {@code screenId} is a stable
     * string the integration and the host agree on (e.g. {@code "split-detail"}). The host
     * decides which Activity / app handles it.
     *
     * <p>No-op when the host doesn't recognize the screen id.
     */
    void openScreen(String screenId);
}
