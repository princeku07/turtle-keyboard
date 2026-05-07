package com.prince.turtlekeyboard.theme;

import android.content.Context;

import com.prince.turtlekeyboard.settings.Prefs;

/** Resolves the active KeyboardTheme based on user settings. Currently only ships the
 *  Turtle dark theme; a light theme + per-user palettes can plug in here. */
public class ThemeManager {

    private final Prefs prefs;

    public ThemeManager(Context context) {
        this.prefs = new Prefs(context);
    }

    public KeyboardTheme current() {
        // Reserved for future "light" / "system" modes — Prefs already exposes the key.
        String name = prefs.getString(Prefs.KEY_THEME, "turtle_dark");
        switch (name) {
            case "turtle_dark":
            default:
                return KeyboardTheme.turtleDark();
        }
    }
}
