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

    // -- AI providers (Settings → AI) ------------------------------------------
    /** Text ("basic questions") provider: {@link #PROVIDER_GEMINI} or {@link #PROVIDER_LMSTUDIO}. */
    public static final String KEY_TEXT_PROVIDER = "text_provider";
    /** Image-generation provider; Gemini-only for now (LM Studio can't generate images). */
    public static final String KEY_IMAGE_PROVIDER = "image_provider";
    /** LM Studio server base URL, e.g. {@code http://10.0.2.2:1234/v1}. */
    public static final String KEY_LMSTUDIO_URL = "lmstudio_url";
    /** LM Studio model id; empty = let the server use its loaded model. */
    public static final String KEY_LMSTUDIO_MODEL = "lmstudio_model";

    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_LMSTUDIO = "lmstudio";
    /** Local LM Studio test server; host must be whitelisted in network_security_config.xml.
     *  (Emulator would use http://10.0.2.2:1234/v1 instead.) */
    public static final String DEFAULT_LMSTUDIO_URL = "http://192.168.1.13:1234/v1";
    /** Loaded chat model on the test server; blank in prefs falls back to this. */
    public static final String DEFAULT_LMSTUDIO_MODEL = "google/gemma-4-e4b";

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
