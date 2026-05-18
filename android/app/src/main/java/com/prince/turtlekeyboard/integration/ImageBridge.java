package com.prince.turtlekeyboard.integration;

import android.net.Uri;

import com.prince.kbd.core.IntegrationContext;

/**
 * Surface the IME provides to {@link KeyboardIntegrationContextImpl} for the
 * image side of the SPI (picking and committing). Kept inside {@code :app} — it
 * wraps IME-only plumbing ({@code ImagePickerActivity}, {@code commitContent},
 * clipboard fallback) that integrations shouldn't see directly.
 */
public interface ImageBridge {
    /** Launches the system photo picker and fires {@code cb} once on the main
     *  thread with downsized bytes, or null on cancel / failure. */
    void pickImage(IntegrationContext.ImagePickCallback cb);

    /** Inserts the image at {@code uri} into the active host field. Uses
     *  {@code commitContent} where supported, clipboard fallback elsewhere. */
    void commitImage(Uri uri, String mime);
}
