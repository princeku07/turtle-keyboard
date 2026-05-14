package com.prince.kbd.core;

import android.content.Context;

import java.util.Map;

/**
 * Handed to a {@link SheetView} at build time. Carries the parsed URL data the deep-link
 * intent matched, plus an Android {@link Context} for inflating resources, and a
 * {@link #dismiss} callback the sheet can call from its own UI.
 *
 * <p>Backend services (auth, storage, AI) are <em>not</em> exposed here — the sheet view
 * constructs whatever it needs the same way host activities do today
 * (e.g. {@code new GoogleAuthImpl(ctx.androidContext(), prefs.scoped("google"))}). Keeps
 * the SPI minimal; we can promote services to first-class methods on this interface if
 * a pattern emerges across multiple sheets.
 */
public interface SheetContext {

    /** Android context (the host {@code BottomSheetActivity}). Use for inflating views,
     *  reading resources, or constructing services like {@code GoogleAuthImpl}. */
    Context androidContext();

    /** The route key matched from the URL path, e.g. {@code "poll"} for
     *  {@code https://www.turtlekeyboard.com/poll/abc123}. Same value the integration used when
     *  registering the route. */
    String routeKey();

    /** The artifact id from the URL path, e.g. {@code "abc123"}. Sheet typically uses
     *  this as the key for fetching state from the backend. */
    String artifactId();

    /** Query params parsed from the URL, never null. Use for view-state hints
     *  ({@code ?from=share}, {@code ?ref=qr}). */
    Map<String, String> params();

    /** Close the sheet. Triggers {@link SheetView#onDismiss()} and finishes the host
     *  activity with the slide-down animation. */
    void dismiss();
}
