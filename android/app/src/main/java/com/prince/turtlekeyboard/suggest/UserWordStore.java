package com.prince.turtlekeyboard.suggest;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * On-device per-word counter for words the user has typed. Backed by a private
 * SharedPreferences file; reads hit an in-memory mirror. The disk load runs on
 * a background executor so the IME's onCreateInputView never blocks on
 * {@link SharedPreferences#getAll()}. Bumps are buffered and flushed every
 * {@link #FLUSH_DELAY_MS} so typing doesn't pay an editor-allocation +
 * journal-write per word; callers should invoke {@link #flush()} on lifecycle
 * teardown to persist accrued counts before process kill. Capped at
 * {@link #MAX_ENTRIES}; eviction drops the lowest-count entries in batches.
 */
public final class UserWordStore {

    private static final String PREFS_NAME = "turtle_user_words";
    private static final int MAX_ENTRIES = 2000;
    private static final int EVICT_BATCH = 200;
    private static final long FLUSH_DELAY_MS = 5_000L;

    private final SharedPreferences prefs;
    // TreeMap so prefixMatches can use a range subMap instead of an O(n) scan.
    private final TreeMap<String, Integer> cache = new TreeMap<>();
    // Words bumped since the last flush; cumulative counts so the snapshot we
    // hand to SharedPreferences is the authoritative value, not a delta.
    private final TreeMap<String, Integer> pendingWrites = new TreeMap<>();
    private final ScheduledExecutorService io;
    private volatile boolean ready;
    private ScheduledFuture<?> pendingFlush;

    public UserWordStore(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.io = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TurtleUserWordIO");
            t.setPriority(Thread.MIN_PRIORITY);
            t.setDaemon(true);
            return t;
        });
        // Hop the prefs read off the main thread; reads gate on `ready`, writes merge in.
        io.execute(this::loadFromDisk);
    }

    private void loadFromDisk() {
        Map<String, ?> all = prefs.getAll();
        synchronized (this) {
            for (Map.Entry<String, ?> e : all.entrySet()) {
                Object v = e.getValue();
                if (!(v instanceof Integer)) continue;
                String key = e.getKey();
                Integer pending = cache.get(key);
                int loaded = (Integer) v;
                // A bump that landed during load started from 0 — merge so the on-disk
                // count is preserved (cache becomes loaded + bumps-during-load).
                if (pending == null) {
                    cache.put(key, loaded);
                } else {
                    int merged = loaded + pending;
                    cache.put(key, merged);
                    pendingWrites.put(key, merged);
                }
            }
            ready = true;
        }
    }

    public boolean isReady() {
        return ready;
    }

    public synchronized void bump(String word) {
        if (word == null || word.isEmpty()) return;
        Integer current = cache.get(word);
        int next = (current == null) ? 1 : current + 1;
        cache.put(word, next);
        pendingWrites.put(word, next);
        if (cache.size() > MAX_ENTRIES) evict();
        // First bump after a flush schedules the next one; subsequent bumps in the
        // window ride the same flush so latency is bounded at FLUSH_DELAY_MS.
        if (pendingFlush == null) {
            pendingFlush = io.schedule(this::flush, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized int count(String word) {
        Integer v = cache.get(word);
        return v == null ? 0 : v;
    }

    /** Words starting with {@code prefix}, sorted by count desc. Case-insensitive.
     *  Empty until the on-disk dictionary has finished loading. */
    public synchronized List<String> prefixMatches(String prefix, int max) {
        if (!ready || prefix == null || prefix.isEmpty() || max <= 0) {
            return Collections.emptyList();
        }
        String p = prefix.toLowerCase(Locale.ROOT);
        // Learned words are letters + ' + - only, so ￿ is a safe exclusive upper bound.
        SortedMap<String, Integer> range = cache.subMap(p, p + "￿");
        if (range.isEmpty()) return Collections.emptyList();
        // Fixed-size top-K via insertion. Avoids the full-list allocation + general sort
        // that ran on every keystroke, and short-circuits once size == max with low values.
        String[] topK = new String[max];
        int[] topV = new int[max];
        int size = 0;
        for (Map.Entry<String, Integer> e : range.entrySet()) {
            int v = e.getValue();
            if (size < max) {
                int i = size;
                while (i > 0 && topV[i - 1] < v) {
                    topK[i] = topK[i - 1];
                    topV[i] = topV[i - 1];
                    i--;
                }
                topK[i] = e.getKey();
                topV[i] = v;
                size++;
            } else if (v > topV[max - 1]) {
                int i = max - 1;
                while (i > 0 && topV[i - 1] < v) {
                    topK[i] = topK[i - 1];
                    topV[i] = topV[i - 1];
                    i--;
                }
                topK[i] = e.getKey();
                topV[i] = v;
            }
        }
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) out.add(topK[i]);
        return out;
    }

    public synchronized void clear() {
        cache.clear();
        pendingWrites.clear();
        if (pendingFlush != null) {
            pendingFlush.cancel(false);
            pendingFlush = null;
        }
        prefs.edit().clear().apply();
    }

    /** Persist any pending bumps. Safe to call from any thread. Idempotent
     *  when there's nothing pending. Callers should invoke this on focus-out
     *  / IME teardown so unflushed counts survive process kill. */
    public void flush() {
        Map<String, Integer> snapshot;
        synchronized (this) {
            pendingFlush = null;
            if (pendingWrites.isEmpty()) return;
            snapshot = new TreeMap<>(pendingWrites);
            pendingWrites.clear();
        }
        SharedPreferences.Editor ed = prefs.edit();
        for (Map.Entry<String, Integer> e : snapshot.entrySet()) {
            ed.putInt(e.getKey(), e.getValue());
        }
        ed.apply();
    }

    /** Flush + stop the background executor. Call from {@code onDestroy}. */
    public void shutdown() {
        flush();
        io.shutdown();
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
            // Drop any pending bump for the same key — it would re-add it to disk
            // on next flush after we just removed it.
            pendingWrites.remove(key);
            ed.remove(key);
        }
        ed.apply();
    }
}
