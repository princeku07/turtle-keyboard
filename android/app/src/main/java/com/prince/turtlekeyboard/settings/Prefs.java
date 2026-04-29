package com.prince.turtlekeyboard.settings;

import android.content.Context;
import android.content.SharedPreferences;

/** Centralized SharedPreferences access. Keys are constants here so consumers don't drift. */
public class Prefs {

    public static final String FILE = "turtle_prefs";

    public static final String KEY_THEME = "theme";
    public static final String KEY_QUICK_PANEL_ENABLED = "quick_panel_enabled";
    public static final String KEY_NUMBER_ROW = "number_row_enabled";
    public static final String KEY_AI_ENDPOINT = "ai_endpoint";
    public static final String KEY_AUTH_TOKEN = "auth_token";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        this.sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getString(String key, String fallback) { return sp.getString(key, fallback); }
    public boolean getBool(String key, boolean fallback) { return sp.getBoolean(key, fallback); }
    public void putString(String key, String value) { sp.edit().putString(key, value).apply(); }
    public void putBool(String key, boolean value) { sp.edit().putBoolean(key, value).apply(); }
}
