package com.prince.turtlekeyboard.integration;

import java.util.List;

/**
 * Returns a starter set of shortcuts for a freshly-enrolled app. Today's only impl is
 * {@link StaticSuggestedShortcutSource} — a hardcoded map of pkg → templates. The
 * interface exists so a later impl can layer on community / AI-seeded sources without
 * touching the IME.
 */
public interface SuggestedShortcutSource {
    List<SuggestedShortcut> shortcutsFor(String pkg, String displayName);
}
