package com.prince.turtlekeyboard.integration;

import android.net.Uri;

import com.prince.kbd.core.IntegrationContext;

/**
 * IME-side surface for picking and committing images, wrapping
 * {@code ImagePickerActivity} and {@code commitContent} so integrations
 * never see them directly.
 */
public interface ImageBridge {
    /** Launches the system photo picker; fires {@code cb} on the main thread
     *  with downsized bytes, or null on cancel / failure. */
    void pickImage(IntegrationContext.ImagePickCallback cb);

    /** Inserts the image at {@code uri} into the active host field. */
    void commitImage(Uri uri, String mime);
}
