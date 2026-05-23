package com.prince.turtlekeyboard.suggest;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.prince.turtlekeyboard.suggest.symspell.SuggestItem;
import com.prince.turtlekeyboard.suggest.symspell.SymSpell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Facade over the vendored SymSpell engine and the per-user word store.
 *
 * <p>Lifecycle: construct once, call {@link #loadAsync(Context)} to start
 * background load, call {@link #suggest(String, int)} from the IME text path
 * (returns empty until {@link #isReady()}), and {@link #learn(String)} on each
 * committed word.
 *
 * <p>Does not touch the network and does not log word contents.
 */
public final class SuggestionEngine {

    private static final String TAG = "TurtleSuggest";
    private static final String DICT_ASSET = "dict/en_unigrams.txt";

    private static final int INITIAL_CAPACITY = 82_000;
    /** Distance 1 only — distance 2 over 82k words OOMs against the 256MB IME heap. */
    private static final int MAX_EDIT_DISTANCE = 1;
    private static final int PREFIX_LENGTH = 7;
    private static final int COUNT_THRESHOLD = 1;
    private static final int LOOKUP_DISTANCE = 1;

    private final UserWordStore userStore;
    private volatile SymSpell symSpell;
    /** Dictionary words sorted by frequency descending, used for prefix completion. */
    private volatile String[] sortedWords;
    private volatile boolean ready;
    private volatile boolean loading;
    /** Fired once when the bundled dictionary finishes loading. */
    private volatile Runnable onReadyListener;

    public SuggestionEngine(Context ctx) {
        this.userStore = new UserWordStore(ctx);
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * Sets a listener fired when the dictionary becomes ready. Fires synchronously
     * if already ready. Replaces any previously set listener. Fires on the load
     * thread; post to the main thread before touching views.
     */
    public void setOnReadyListener(Runnable listener) {
        this.onReadyListener = listener;
        if (ready && listener != null) listener.run();
    }

    /** Idempotent. Returns immediately; load runs on a background thread. */
    public void loadAsync(Context ctx) {
        if (ready || loading) return;
        loading = true;
        final Context appCtx = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            long t0 = SystemClock.elapsedRealtime();
            try {
                // Phase 1: prefix completion ships first so the strip populates
                // before the slower SymSpell index finishes.
                sortedWords = loadSortedWords(appCtx);
                ready = true;
                Log.i(TAG, "prefix completion ready in "
                        + (SystemClock.elapsedRealtime() - t0) + "ms");
                Runnable cb = onReadyListener;
                if (cb != null) cb.run();

                // Phase 2: SymSpell edit-distance index. OOM here degrades to
                // prefix-only instead of killing the IME.
                try {
                    SymSpell s = new SymSpell(
                            INITIAL_CAPACITY, MAX_EDIT_DISTANCE,
                            PREFIX_LENGTH, COUNT_THRESHOLD);
                    try (InputStream in = appCtx.getAssets().open(DICT_ASSET)) {
                        s.loadDictionary(in, 0, 1);
                    }
                    symSpell = s;
                    Log.i(TAG, "symspell ready in "
                            + (SystemClock.elapsedRealtime() - t0) + "ms");
                } catch (OutOfMemoryError oom) {
                    symSpell = null;
                    Log.w(TAG, "symspell build OOM — falling back to prefix-only", oom);
                }
            } catch (IOException e) {
                Log.e(TAG, "dictionary load failed", e);
            } finally {
                loading = false;
            }
        }, "TurtleSuggestLoad");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /**
     * Top suggestions for {@code prefix}, in priority order: user vocabulary,
     * dictionary prefix completion, then SymSpell typo corrections. Deduped and
     * capped at {@code max}. Returns lowercase; the caller restores case.
     */
    public List<String> suggest(String prefix, int max) {
        if (prefix == null || prefix.isEmpty() || max <= 0) {
            return Collections.emptyList();
        }
        String q = prefix.toLowerCase(Locale.ROOT);

        LinkedHashSet<String> out = new LinkedHashSet<>(max * 2);

        for (String w : userStore.prefixMatches(q, max)) {
            out.add(w);
            if (out.size() >= max) break;
        }

        if (out.size() < max) {
            for (String w : prefixComplete(q, max)) {
                out.add(w);
                if (out.size() >= max) break;
            }
        }

        SymSpell engine = symSpell;
        if (out.size() < max && engine != null) {
            try {
                List<SuggestItem> items = engine.lookup(
                        q, SymSpell.Verbosity.Closest, LOOKUP_DISTANCE);
                if (items != null) {
                    for (SuggestItem it : items) {
                        out.add(it.term);
                        if (out.size() >= max) break;
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "lookup failed", e);
            }
        }

        return new ArrayList<>(out);
    }

    /** Linear scan of the frequency-sorted dictionary for entries starting with {@code prefix}. */
    private List<String> prefixComplete(String prefix, int max) {
        String[] dict = sortedWords;
        if (dict == null || prefix.isEmpty() || max <= 0) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(max);
        for (int i = 0; i < dict.length; i++) {
            if (dict[i].startsWith(prefix)) {
                out.add(dict[i]);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    /** Returns the bundled dictionary words sorted by frequency descending. */
    private static String[] loadSortedWords(Context appCtx) throws IOException {
        // Each line is "<word><space><frequency>".
        ArrayList<String> words = new ArrayList<>(82_000);
        ArrayList<Long> freqs = new ArrayList<>(82_000);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                appCtx.getAssets().open(DICT_ASSET), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp <= 0 || sp >= line.length() - 1) continue;
                long freq;
                try {
                    freq = Long.parseLong(line.substring(sp + 1));
                } catch (NumberFormatException e) {
                    continue;
                }
                words.add(line.substring(0, sp));
                freqs.add(freq);
            }
        }
        Integer[] idx = new Integer[words.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Long.compare(freqs.get(b), freqs.get(a)));
        String[] sorted = new String[idx.length];
        for (int i = 0; i < idx.length; i++) sorted[i] = words.get(idx[i]);
        return sorted;
    }

    /**
     * Record a committed word so personal vocabulary accrues over time.
     * Skips non-alpha tokens (URLs, numbers, slash commands, emoji); hyphen and
     * apostrophe are allowed.
     */
    public void learn(String word) {
        if (word == null) return;
        String w = word.trim().toLowerCase(Locale.ROOT);
        if (w.isEmpty()) return;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!Character.isLetter(c) && c != '\'' && c != '-') return;
        }
        userStore.bump(w);
    }

    /** Wipes personal vocabulary; the bundled dictionary is unaffected. */
    public void resetUserVocabulary() {
        userStore.clear();
    }
}
