package com.prince.split;

/**
 * Narrow key/value store the SDK needs to persist split state. Decouples the SDK from any
 * particular storage backend — today the keyboard APK adapts {@code SharedPreferences}; a
 * future standalone Split APK could implement this against its own DB or a content
 * provider without the SDK changing.
 */
public interface SplitStore {
    String getString(String key, String fallback);
    int getInt(String key, int fallback);
    void putString(String key, String value);
    void putInt(String key, int value);
}
