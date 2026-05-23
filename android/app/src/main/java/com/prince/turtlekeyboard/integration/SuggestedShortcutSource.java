package com.prince.turtlekeyboard.integration;

import java.util.List;

/**
 * Returns a starter set of shortcuts for a freshly-enrolled app.
 */
public interface SuggestedShortcutSource {
    List<SuggestedShortcut> shortcutsFor(String pkg, String displayName);
}
