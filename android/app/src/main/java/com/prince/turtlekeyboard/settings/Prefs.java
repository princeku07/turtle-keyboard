package com.prince.turtlekeyboard.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.prince.split.SplitContract;
import com.prince.split.SplitStore;

/**
 * Centralized SharedPreferences access. Keys are constants here so consumers don't drift.
 * Also implements {@link SplitStore} so the Split SDK can persist via the same store
 * without leaking SharedPreferences across module boundaries.
 */
public class Prefs implements SplitStore {

    /** Same file the {@code :split} module reads via {@link SplitContract#STORAGE_FILE}. */
    public static final String FILE = SplitContract.STORAGE_FILE;

    public static final String KEY_THEME = "theme";
    public static final String KEY_QUICK_PANEL_ENABLED = "quick_panel_enabled";
    public static final String KEY_NUMBER_ROW = "number_row_enabled";
    public static final String KEY_AI_ENDPOINT = "ai_endpoint";
    public static final String KEY_AUTH_TOKEN = "auth_token";
    public static final String KEY_SPLIT_ENABLED = "split_enabled";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        this.sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @Override public String getString(String key, String fallback) { return sp.getString(key, fallback); }
    public boolean getBool(String key, boolean fallback) { return sp.getBoolean(key, fallback); }
    @Override public int getInt(String key, int fallback) { return sp.getInt(key, fallback); }
    @Override public void putString(String key, String value) { sp.edit().putString(key, value).apply(); }
    public void putBool(String key, boolean value) { sp.edit().putBoolean(key, value).apply(); }
    @Override public void putInt(String key, int value) { sp.edit().putInt(key, value).apply(); }
}
