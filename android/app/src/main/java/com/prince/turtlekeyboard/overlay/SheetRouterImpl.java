package com.prince.turtlekeyboard.overlay;

import androidx.annotation.Nullable;

import com.prince.kbd.core.SheetRouter;
import com.prince.kbd.core.SheetViewFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * App-scoped {@link SheetRouter} impl. ConcurrentHashMap-backed so registration can
 * happen during {@code Application.onCreate} while the deep-link {@code BottomSheetActivity}
 * may concurrently look up factories — practically rare today, but free safety.
 */
public final class SheetRouterImpl implements SheetRouter {

    private final Map<String, SheetViewFactory> routes = new ConcurrentHashMap<>();

    @Override
    public void register(String routeKey, SheetViewFactory factory) {
        if (routeKey == null || factory == null) return;
        routes.put(routeKey.toLowerCase(), factory);
    }

    @Override
    public void registerAll(Map<String, SheetViewFactory> entries) {
        if (entries == null) return;
        for (Map.Entry<String, SheetViewFactory> e : entries.entrySet()) {
            register(e.getKey(), e.getValue());
        }
    }

    @Override
    @Nullable
    public SheetViewFactory factoryFor(String routeKey) {
        return routeKey == null ? null : routes.get(routeKey.toLowerCase());
    }
}
