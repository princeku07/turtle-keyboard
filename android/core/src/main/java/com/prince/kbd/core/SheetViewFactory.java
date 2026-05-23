package com.prince.kbd.core;

/**
 * Factory for a {@link SheetView}. Registered with a route key in {@link SheetRouter};
 * called fresh on every matching deep-link tap so views never leak state across sheets.
 */
public interface SheetViewFactory {
    SheetView create();
}
