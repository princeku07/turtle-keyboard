package com.prince.kbd.core;

import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pluggable keyboard integration. An integration may do any combination of:
 * activate a per-input session (chip + panel UI), contribute slash commands,
 * or register bottom-sheet overlays opened from deep links.
 */
public interface KeyboardIntegration extends CommandProvider {

    /** Stable identifier, e.g. {@code "split"}. */
    String id();

    /** Returns a session if this integration applies to the current input, or null. */
    @Nullable IntegrationSession activate(EditorInfo info, IntegrationContext ctx);

    @Override default List<CommandSpec> commands() { return Collections.emptyList(); }

    /**
     * Bottom-sheet routes keyed by URL route key (e.g. {@code "poll"} for
     * {@code https://www.turtlekeyboard.com/poll/<id>}). Default: empty.
     */
    default Map<String, SheetViewFactory> sheetRoutes() {
        return Collections.emptyMap();
    }

    /** Releases long-lived resources (executors, network clients). Default: no-op. */
    default void destroy() {}
}
