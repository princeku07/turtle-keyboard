package com.prince.turtlekeyboard.overlay;

import android.net.Uri;

/**
 * Canonical URL builder for overlay-sheet artifacts: {@code https://<HOST>/<routeKey>/<id>}.
 * The manifest's BottomSheetActivity intent-filter host and the {@code assetlinks.json}
 * domain must match {@link #HOST} for {@code autoVerify="true"} App Links to work.
 */
public final class OverlayUrls {

    public static final String HOST = "www.turtlekeyboard.com";

    private OverlayUrls() {}

    /** Canonical artifact URL: {@code https://<HOST>/<routeKey>/<id>}. */
    public static String forArtifact(String routeKey, String id) {
        return new Uri.Builder()
                .scheme("https").authority(HOST)
                .appendPath(routeKey).appendPath(id)
                .build().toString();
    }
}
