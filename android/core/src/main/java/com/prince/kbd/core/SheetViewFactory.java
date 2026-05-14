package com.prince.kbd.core;

/**
 * Factory for a {@link SheetView}. Registered with a route key in {@link SheetRouter} at
 * app startup; called fresh on every matching deep-link tap so the resulting view never
 * leaks state across sheets.
 */
public interface SheetViewFactory {
    SheetView create();
}
