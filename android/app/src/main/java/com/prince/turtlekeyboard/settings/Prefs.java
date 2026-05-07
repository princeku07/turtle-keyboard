package com.prince.turtlekeyboard.settings;

import android.content.Context;

import com.prince.kbd.core.KeyValueStore;
import com.prince.kbd.core.SharedPrefsKeyValueStore;

/**
 * App-level typed prefs wrapper. Keys here are app-global (theme, AI endpoint, master
 * toggles); per-module storage goes through scoped views off the same backing
 * {@link SharedPrefsKeyValueStore}, surfaced to integrations via
 * {@code IntegrationContext.store(namespace)}.
 *
 * <p>The root {@link KeyValueStore} is exposed via {@link #root()} so the IME can hand
 * scoped views to integrations without duplicating storage construction.
 */
public class Prefs {

    public static final String KEY_THEME = "theme";
    public static final String KEY_QUICK_PANEL_ENABLED = "quick_panel_enabled";
    public static final String KEY_NUMBER_ROW = "number_row_enabled";
    public static final String KEY_AI_ENDPOINT = "ai_endpoint";
    public static final String KEY_AUTH_TOKEN = "auth_token";

    private final KeyValueStore kv;

    public Prefs(Context context) {
        this.kv = new SharedPrefsKeyValueStore(context, SharedPrefsKeyValueStore.DEFAULT_FILE);
    }

    /** @return the root, unscoped store the IME hands to {@code ctx.store(namespace)}. */
    public KeyValueStore root() { return kv; }

    public String getString(String key, String fallback) { return kv.getString(key, fallback); }
    public int getInt(String key, int fallback) { return kv.getInt(key, fallback); }
    public boolean getBool(String key, boolean fallback) { return kv.getBoolean(key, fallback); }
    public void putString(String key, String value) { kv.putString(key, value); }
    public void putInt(String key, int value) { kv.putInt(key, value); }
    public void putBool(String key, boolean value) { kv.putBoolean(key, value); }
}
