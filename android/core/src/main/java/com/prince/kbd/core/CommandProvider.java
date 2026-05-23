package com.prince.kbd.core;

import java.util.List;

/**
 * Anything that contributes slash commands to the keyboard. {@link KeyboardIntegration}
 * extends this; the app also registers a built-in provider for commands that don't
 * belong to any feature module.
 */
public interface CommandProvider {
    List<CommandSpec> commands();
}
