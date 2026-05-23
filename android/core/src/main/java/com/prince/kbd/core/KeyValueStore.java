package com.prince.kbd.core;

/**
 * Storage port. Modules receive a {@link KeyValueStore} from {@link IntegrationContext}
 * and use {@link #scoped(String)} to namespace their keys so two modules can use the
 * same key name without colliding.
 */
public interface KeyValueStore {
    String getString(String key, String fallback);
    int getInt(String key, int fallback);
    boolean getBoolean(String key, boolean fallback);
    long getLong(String key, long fallback);

    void putString(String key, String value);
    void putInt(String key, int value);
    void putBoolean(String key, boolean value);
    void putLong(String key, long value);

    /** @return a child view where every key is silently prefixed by {@code namespace + "."}.
     *  Calling {@code scoped} on a scoped view composes prefixes. */
    KeyValueStore scoped(String namespace);
}
