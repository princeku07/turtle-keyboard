package com.prince.kbd.core;

import android.view.View;

/**
 * Module-provided content for a bottom-sheet overlay. One instance per sheet display;
 * the {@link SheetViewFactory} is invoked fresh on every matching deep-link tap.
 * Sheets run outside the IME (link tap, share intent).
 */
public interface SheetView {

    /** Build the View tree to mount inside the bottom sheet. Called once. */
    View buildView(SheetContext ctx);

    /** Fired after {@link #buildView} is attached and visible. */
    default void onShow() {}

    /** Fired before the sheet's host activity finishes. Cancel pending work, drop listeners. */
    default void onDismiss() {}
}
