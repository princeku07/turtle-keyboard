package com.prince.kbd.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The only {@link KeyValueStore} adapter in the system. Backed by {@link SharedPreferences};
 * keeps namespacing in-process (no separate prefs file per module) so we don't fan out
 * disk I/O. The scoped views are zero-allocation light: they hold the parent {@link
 * SharedPreferences} and a string prefix.
 */
public final class SharedPrefsKeyValueStore implements KeyValueStore {

    /** SharedPreferences file every module reads/writes through. Single file keeps the
     *  on-disk format predictable and lets one xml back the entire app + all modules. */
    public static final String DEFAULT_FILE = "turtle_prefs";

    private final SharedPreferences sp;
    private final String prefix;

    public SharedPrefsKeyValueStore(Context context, String fileName) {
        this(context.getSharedPreferences(fileName, Context.MODE_PRIVATE), "");
    }

    public SharedPrefsKeyValueStore(SharedPreferences sp) {
        this(sp, "");
    }

    private SharedPrefsKeyValueStore(SharedPreferences sp, String prefix) {
        this.sp = sp;
        this.prefix = prefix;
    }

    private String k(String key) { return prefix.isEmpty() ? key : prefix + key; }

    @Override public String getString(String key, String fallback) { return sp.getString(k(key), fallback); }
    @Override public int getInt(String key, int fallback) { return sp.getInt(k(key), fallback); }
    @Override public boolean getBoolean(String key, boolean fallback) { return sp.getBoolean(k(key), fallback); }
    @Override public long getLong(String key, long fallback) { return sp.getLong(k(key), fallback); }

    @Override public void putString(String key, String value) { sp.edit().putString(k(key), value).apply(); }
    @Override public void putInt(String key, int value) { sp.edit().putInt(k(key), value).apply(); }
    @Override public void putBoolean(String key, boolean value) { sp.edit().putBoolean(k(key), value).apply(); }
    @Override public void putLong(String key, long value) { sp.edit().putLong(k(key), value).apply(); }

    @Override public KeyValueStore scoped(String namespace) {
        if (namespace == null || namespace.isEmpty()) return this;
        return new SharedPrefsKeyValueStore(sp, prefix + namespace + ".");
    }
}
