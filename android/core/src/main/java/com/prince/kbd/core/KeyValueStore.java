package com.prince.kbd.core;

/**
 * Storage port. The single primitive every module persists through. The keyboard app
 * provides the only adapter ({@link SharedPrefsKeyValueStore}); modules never instantiate
 * storage themselves — they receive a {@link KeyValueStore} from {@link IntegrationContext}.
 *
 * <p>Each module gets its own namespaced view via {@link #scoped(String)}, so two modules
 * can use the same key name without colliding. The on-disk encoding is "{@code <ns>.<key>}";
 * the scoped view applies the prefix automatically and modules write/read unprefixed keys.
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

    /** @return a child view where every key read/written is silently prefixed by
     *  {@code namespace + "."}. Calling {@code scoped} on a scoped view composes prefixes. */
    KeyValueStore scoped(String namespace);
}
