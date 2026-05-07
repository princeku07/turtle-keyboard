package com.prince.turtlekeyboard.integration;

import com.prince.kbd.core.ImageService;

/**
 * Tail of the image-provider chain. When no real provider is configured (or every
 * provider in a fallback chain has errored), this returns a structured error so
 * callers can banner instead of silently hanging.
 */
public final class NoopImageService implements ImageService {
    @Override public void generate(Request req, Callback cb) {
        cb.onError("no_image_provider_configured");
    }
}
