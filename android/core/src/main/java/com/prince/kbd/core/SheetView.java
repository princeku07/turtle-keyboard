package com.prince.kbd.core;

import android.view.View;

/**
 * Module-provided content for a bottom-sheet overlay. The host {@code BottomSheetActivity}
 * in {@code :app} hands the sheet view a {@link SheetContext} carrying the URL data; the
 * sheet view returns the {@link View} to mount and gets lifecycle pings on show / dismiss.
 *
 * <p>One {@link SheetView} instance per sheet display — the factory in
 * {@link SheetViewFactory} is called fresh every time a matching deep link is tapped.
 * The view can hold instance state freely (loaded artifact, in-flight network call,
 * subscription handles) and clean it up in {@link #onDismiss()}.
 *
 * <p>Sheets are <em>only</em> invoked when the keyboard can't open (link tap from a
 * chat, share-target intent). They do not run inside the IME.
 */
public interface SheetView {

    /** Build the View tree to mount inside the bottom sheet. Called once. */
    View buildView(SheetContext ctx);

    /** Fired after {@link #buildView} is attached and visible. Default: no-op. */
    default void onShow() {}

    /** Fired before the sheet's host activity finishes. Cancel pending work, drop
     *  listeners. Default: no-op. */
    default void onDismiss() {}
}
