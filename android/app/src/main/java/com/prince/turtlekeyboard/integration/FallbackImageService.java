package com.prince.turtlekeyboard.integration;

import com.prince.kbd.core.ImageService;

/** {@link ImageService} mirror of {@link FallbackLlm} — primary first, fallback on error. */
public final class FallbackImageService implements ImageService {

    private final ImageService primary;
    private final ImageService fallback;

    public FallbackImageService(ImageService primary, ImageService fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public void generate(Request req, Callback cb) {
        primary.generate(req, new Callback() {
            @Override public void onImage(String imageUrl) { cb.onImage(imageUrl); }
            @Override public void onError(String reason) {
                fallback.generate(req, cb);
            }
        });
    }
}
