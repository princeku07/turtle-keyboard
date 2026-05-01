package com.prince.split;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Default {@link SplitStore} backed by {@link SharedPreferences}. Drop-in for any consumer
 * that doesn't already have its own settings layer — e.g. a future standalone Split APK.
 *
 * <pre>
 *   SplitStore store = new SharedPreferencesSplitStore(context, "split_prefs");
 *   SplitHistory history = new SplitHistory(store);
 * </pre>
 *
 * <p>Pick a file name distinct from any other prefs file the host app uses, since this
 * store touches keys defined in {@link SplitKeys}.
 */
public class SharedPreferencesSplitStore implements SplitStore {

    private final SharedPreferences sp;

    public SharedPreferencesSplitStore(Context context, String fileName) {
        this.sp = context.getSharedPreferences(fileName, Context.MODE_PRIVATE);
    }

    /** For callers that already hold a {@link SharedPreferences} (e.g. an existing prefs layer). */
    public SharedPreferencesSplitStore(SharedPreferences sp) {
        this.sp = sp;
    }

    @Override public String getString(String key, String fallback) { return sp.getString(key, fallback); }
    @Override public int getInt(String key, int fallback) { return sp.getInt(key, fallback); }
    @Override public void putString(String key, String value) { sp.edit().putString(key, value).apply(); }
    @Override public void putInt(String key, int value) { sp.edit().putInt(key, value).apply(); }
}
