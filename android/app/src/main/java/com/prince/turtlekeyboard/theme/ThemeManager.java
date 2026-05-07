package com.prince.turtlekeyboard.theme;

import android.content.Context;
import android.content.res.Configuration;

import com.prince.turtlekeyboard.settings.Prefs;

/**
 * Resolves the active {@link KeyboardTheme}. Honors an explicit Prefs override
 * ({@code "turtle_light"} / {@code "turtle_dark"}); otherwise follows the system
 * dark-mode flag so the keyboard tracks the device's day/night state.
 */
public class ThemeManager {

    public static final String AUTO = "auto";
    public static final String LIGHT = "turtle_light";
    public static final String DARK = "turtle_dark";

    private final Context context;
    private final Prefs prefs;

    public ThemeManager(Context context) {
        this.context = context;
        this.prefs = new Prefs(context);
    }

    public KeyboardTheme current() {
        String name = prefs.getString(Prefs.KEY_THEME, AUTO);
        switch (name) {
            case LIGHT:
                return KeyboardTheme.turtleLight();
            case DARK:
                return KeyboardTheme.turtleDark();
            case AUTO:
            default:
                return systemPrefersDark() ? KeyboardTheme.turtleDark() : KeyboardTheme.turtleLight();
        }
    }

    private boolean systemPrefersDark() {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }
}
