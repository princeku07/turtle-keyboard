package com.prince.kbd.core;

import android.content.Context;

import java.util.Map;

/**
 * Handed to a {@link SheetView} at build time. Carries the parsed URL data from the
 * deep-link, an Android {@link Context} for inflating resources, and a {@link #dismiss}
 * callback the sheet can invoke from its own UI.
 */
public interface SheetContext {

    /** Android context (the host {@code BottomSheetActivity}). */
    Context androidContext();

    /** The route key matched from the URL path (e.g. {@code "poll"}). */
    String routeKey();

    /** The artifact id from the URL path (e.g. {@code "abc123"}). */
    String artifactId();

    /** Query params parsed from the URL, never null. */
    Map<String, String> params();

    /** Close the sheet. Triggers {@link SheetView#onDismiss()} and finishes the host activity. */
    void dismiss();
}
