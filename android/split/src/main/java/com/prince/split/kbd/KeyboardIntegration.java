package com.prince.split.kbd;

import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Pluggable contextual integration for the keyboard. An integration can do either or both:
 *
 * <ul>
 *   <li><b>Activate per input session</b> — return a non-null {@link IntegrationSession}
 *       from {@link #activate} when the host app and field match. The session drives chip
 *       and panel UI for as long as the input session is alive.</li>
 *   <li><b>Contribute slash commands</b> — return commands from {@link #commands()}. They
 *       work in any field; the keyboard's command dispatcher invokes their handlers locally
 *       without round-tripping to the AI backend.</li>
 * </ul>
 *
 * <p>Each integration is registered once with the IME's {@code IntegrationRegistry}.
 */
public interface KeyboardIntegration {

    /** Stable identifier (e.g. {@code "split"}). */
    String id();

    /** @return a session if this integration applies to the current input, null otherwise */
    @Nullable IntegrationSession activate(EditorInfo info, IntegrationContext ctx);

    /** Slash commands this integration ships. Default: none. */
    default List<CommandSpec> commands() { return Collections.emptyList(); }
}
