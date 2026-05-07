package com.prince.kbd.core;

import java.util.List;

/**
 * Anything that contributes slash commands to the keyboard. {@link KeyboardIntegration}
 * extends this; the app also registers a built-in provider for AI-backed commands like
 * {@code /cap}, {@code /fix}, {@code /tone} that don't belong to any feature module.
 *
 * <p>Adding or removing a command means editing one provider — the registry, core, and
 * the rest of the modules don't change.
 */
public interface CommandProvider {
    List<CommandSpec> commands();
}
