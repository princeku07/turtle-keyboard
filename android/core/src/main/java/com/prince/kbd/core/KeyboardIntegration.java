package com.prince.kbd.core;

import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pluggable contextual integration for the keyboard. An integration can do any of:
 *
 * <ul>
 *   <li><b>Activate per input session</b> — return a non-null {@link IntegrationSession}
 *       from {@link #activate} when the host app and field match. The session drives chip
 *       and panel UI for as long as the input session is alive.</li>
 *   <li><b>Contribute slash commands</b> — return commands from {@link #commands()}. They
 *       work in any field; the keyboard's command dispatcher invokes their handlers locally
 *       without round-tripping to the AI backend.</li>
 *   <li><b>Ship bottom-sheet overlays</b> — return routes from {@link #sheetRoutes()}. Each
 *       entry maps a URL route key to a factory that builds the {@link SheetView}. The
 *       host app's {@code BottomSheetActivity} reads the registry at deep-link time and
 *       mounts the matched sheet. Sheets are <em>not</em> triggered from inside the IME;
 *       they fire when the user taps a Turtle link in a chat / share intent.</li>
 * </ul>
 *
 * <p>Each integration is registered once with the IME's integration registry, and (via
 * its sheet routes) once with the app-scoped {@link SheetRouter}.
 */
public interface KeyboardIntegration extends CommandProvider {

    /** Stable identifier (e.g. {@code "split"}). */
    String id();

    /** @return a session if this integration applies to the current input, null otherwise */
    @Nullable IntegrationSession activate(EditorInfo info, IntegrationContext ctx);

    @Override default List<CommandSpec> commands() { return Collections.emptyList(); }

    /** Sheet routes this integration contributes. Keyed by the URL route key
     *  (e.g. {@code "poll"} for {@code https://www.turtlekeyboard.com/poll/<id>}). Default: empty —
     *  most integrations don't ship sheets. */
    default Map<String, SheetViewFactory> sheetRoutes() {
        return Collections.emptyMap();
    }
}
