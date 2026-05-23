package com.prince.kbd.core;

import androidx.annotation.Nullable;

import java.util.Map;

/**
 * App-scoped registry mapping URL route keys to {@link SheetViewFactory} instances.
 * Populated at app startup from each {@link KeyboardIntegration#sheetRoutes()}. Route
 * keys are case-insensitive (lowercase on register and lookup).
 */
public interface SheetRouter {

    /** Register a single route. Last writer wins on collision. */
    void register(String routeKey, SheetViewFactory factory);

    /** Bulk-register, e.g. from {@link KeyboardIntegration#sheetRoutes()}. */
    void registerAll(Map<String, SheetViewFactory> routes);

    /** @return the factory for {@code routeKey}, or null if no integration claimed it. */
    @Nullable SheetViewFactory factoryFor(String routeKey);
}
