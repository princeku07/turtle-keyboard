package com.prince.turtlekeyboard.settings;

import android.content.Context;

import com.prince.kbd.core.KeyValueStore;
import com.prince.kbd.core.SharedPrefsKeyValueStore;

/**
 * App-level typed prefs wrapper. Keys here are app-global; per-module storage uses
 * scoped views off the same backing store, surfaced via {@code IntegrationContext.store}.
 */
public class Prefs {

    public static final String KEY_THEME = "theme";
    public static final String KEY_QUICK_PANEL_ENABLED = "quick_panel_enabled";
    public static final String KEY_NUMBER_ROW = "number_row_enabled";
    public static final String KEY_AI_ENDPOINT = "ai_endpoint";
    public static final String KEY_AUTH_TOKEN = "auth_token";
    public static final String KEY_FEATURE_ONBOARDING_SHOWN = "feature_onboarding_shown";
    public static final String KEY_INSTALL_CAMPAIGN_ID = "install_campaign_id";

    private final KeyValueStore kv;

    public Prefs(Context context) {
        this.kv = new SharedPrefsKeyValueStore(context, SharedPrefsKeyValueStore.DEFAULT_FILE);
    }

    public KeyValueStore root() { return kv; }

    public String getString(String key, String fallback) { return kv.getString(key, fallback); }
    public int getInt(String key, int fallback) { return kv.getInt(key, fallback); }
    public boolean getBool(String key, boolean fallback) { return kv.getBoolean(key, fallback); }
    public void putString(String key, String value) { kv.putString(key, value); }
    public void putInt(String key, int value) { kv.putInt(key, value); }
    public void putBool(String key, boolean value) { kv.putBoolean(key, value); }
}
