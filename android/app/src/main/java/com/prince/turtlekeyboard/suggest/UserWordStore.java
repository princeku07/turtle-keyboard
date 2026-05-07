package com.prince.turtlekeyboard.suggest;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * On-device store for words the user has typed, with per-word counts.
 *
 * Persistence is via a private SharedPreferences file. Reads hit an in-memory
 * mirror so suggestion lookups stay off the disk path.
 *
 * Capped at MAX_ENTRIES; eviction drops the lowest-count entries in batches.
 *
 * Privacy note: this stores plain words. The backing prefs file is MODE_PRIVATE
 * (readable only by this app). Nothing here writes outside the device.
 */
public final class UserWordStore {

    private static final String PREFS_NAME = "turtle_user_words";
    private static final int MAX_ENTRIES = 2000;
    private static final int EVICT_BATCH = 200;

    private final SharedPreferences prefs;
    private final Map<String, Integer> cache;

    public UserWordStore(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> all = prefs.getAll();
        this.cache = new HashMap<>(all.size() * 2);
        for (Map.Entry<String, ?> e : all.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Integer) cache.put(e.getKey(), (Integer) v);
        }
    }

    public synchronized void bump(String word) {
        if (word == null || word.isEmpty()) return;
        Integer current = cache.get(word);
        int next = (current == null) ? 1 : current + 1;
        cache.put(word, next);
        prefs.edit().putInt(word, next).apply();
        if (cache.size() > MAX_ENTRIES) evict();
    }

    public synchronized int count(String word) {
        Integer v = cache.get(word);
        return v == null ? 0 : v;
    }

    /**
     * Returns words in the store that start with {@code prefix}, sorted by
     * count descending. Case-insensitive (the store is lowercase by convention).
     */
    public synchronized List<String> prefixMatches(String prefix, int max) {
        if (prefix == null || prefix.isEmpty() || max <= 0) {
            return Collections.emptyList();
        }
        String p = prefix.toLowerCase(Locale.ROOT);
        List<Map.Entry<String, Integer>> hits = new ArrayList<>();
        for (Map.Entry<String, Integer> e : cache.entrySet()) {
            if (e.getKey().startsWith(p)) hits.add(e);
        }
        hits.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>(Math.min(max, hits.size()));
        for (int i = 0; i < hits.size() && i < max; i++) {
            out.add(hits.get(i).getKey());
        }
        return out;
    }

    public synchronized void clear() {
        cache.clear();
        prefs.edit().clear().apply();
    }

    private void evict() {
        int target = MAX_ENTRIES - EVICT_BATCH;
        List<Map.Entry<String, Integer>> all = new ArrayList<>(cache.entrySet());
        all.sort((a, b) -> Integer.compare(a.getValue(), b.getValue()));
        SharedPreferences.Editor ed = prefs.edit();
        int removeCount = cache.size() - target;
        for (int i = 0; i < removeCount && i < all.size(); i++) {
            String key = all.get(i).getKey();
            cache.remove(key);
            ed.remove(key);
        }
        ed.apply();
    }
}
