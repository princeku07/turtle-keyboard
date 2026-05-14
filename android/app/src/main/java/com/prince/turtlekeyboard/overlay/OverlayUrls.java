package com.prince.turtlekeyboard.overlay;

import android.net.Uri;

/**
 * Canonical URL builder for overlay-sheet artifacts. Single source of truth for the
 * scheme + host so a future domain change is one edit, not a grep.
 *
 * <p>URL shape: {@code https://<HOST>/<routeKey>/<id>[?<query>]}. The
 * {@code AndroidManifest.xml} intent-filter for {@code BottomSheetActivity} must match
 * the same host. {@code assetlinks.json} hosted at
 * {@code https://<HOST>/.well-known/assetlinks.json} (served by the landing-app's
 * {@code public/} folder) lets {@code android:autoVerify="true"} pass on install, so
 * taps in chat open Turtle directly without a chooser.
 */
public final class OverlayUrls {

    /** App Link host. Must match the {@code <data android:host="..."/>} in the
     *  manifest's BottomSheetActivity intent-filter AND the domain serving
     *  {@code /.well-known/assetlinks.json} from {@code lading-app/public/}. */
    public static final String HOST = "www.turtlekeyboard.com";

    /** Cloudflare Worker base URL for overlay artifact CRUD. Shared by every overlay
     *  client (PollClient, WyrClient, etc.) so a deploy-host change is one edit, not a
     *  grep. Separate from {@link #HOST} — {@code HOST} is the user-facing shareable
     *  URL host (App Link target), {@code WORKER_BASE_URL} is the JSON API. */
    public static final String WORKER_BASE_URL = "https://turtle-worker.trtlk.workers.dev";

    private OverlayUrls() {}

    /** Canonical artifact URL: {@code https://<HOST>/<routeKey>/<id>}. */
    public static String forArtifact(String routeKey, String id) {
        return new Uri.Builder()
                .scheme("https").authority(HOST)
                .appendPath(routeKey).appendPath(id)
                .build().toString();
    }
}
