package com.prince.kbd.core;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Surface integrations talk to. Owned by the IME service and passed into every
 * integration call so integrations don't reach into the IME's internals.
 */
public interface IntegrationContext {

    /** Result of a {@link #pickImage} call. Bytes are pre-downsized by the IME. */
    final class PickedImage {
        public final byte[] bytes;
        public final String mime;
        public PickedImage(byte[] bytes, String mime) {
            this.bytes = bytes;
            this.mime = mime;
        }
    }

    interface ImagePickCallback {
        /** Fires once, on the main thread. {@code picked == null} means cancelled or unavailable. */
        void onPicked(@Nullable PickedImage picked);
    }

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

    /** @return the launcher icon for {@code pkg}, or null when the host isn't installed
     *  or isn't declared in the manifest's {@code <queries>} block. */
    @Nullable Drawable iconForPackage(String pkg);

    /** Module-scoped persistent storage. Keys are isolated per namespace. */
    KeyValueStore store(String namespace);

    /** Lookup for known host apps. Integrations use this to decide when to activate. */
    AppProfileRegistry profiles();

    /** AI primitive — call the LLM directly with the integration's own system prompt.
     *  Opt-in; modules that don't need AI simply don't call this. */
    GeminiService ai();

    /** MCP primitive — JSON-RPC {@code tools/call} transport against any MCP-over-HTTP
     *  server. Endpoint URL and per-user auth token are owned by the integration. Opt-in. */
    McpService mcp();

    /** Cross-module Google OAuth. Tokens are cached and shared across modules. Opt-in. */
    GoogleAuth googleAuth();

    /** Commit text into the host editor at the cursor. */
    void commitText(CharSequence text);

    /** Delete {@code n} characters before the cursor in the host editor. */
    void deleteBeforeCursor(int n);

    /** Launch the shared system photo picker. Callback fires once on the main thread
     *  with downsized bytes, or null if cancelled / unavailable. */
    void pickImage(ImagePickCallback cb);

    /** Insert an image into the host editor. Uses {@code commitContent()} where the host
     *  field accepts {@code mime}; falls back to clipboard with a "tap to paste" banner. */
    void commitImage(Uri uri, String mime);

    /** Hand off to a deeper screen the host app provides. {@code screenId} is a stable
     *  string the integration and host agree on. No-op when the host doesn't recognize it. */
    void openScreen(String screenId);
}
