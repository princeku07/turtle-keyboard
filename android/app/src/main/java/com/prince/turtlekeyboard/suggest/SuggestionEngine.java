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
 * Lifecycle:
 *   1. Construct once (e.g. in InputMethodService.onCreate).
 *   2. Call {@link #loadAsync(Context)} — kicks off background dictionary load.
 *   3. Call {@link #suggest(String, int)} from the IME's text path. It returns
 *      an empty list until {@link #isReady()} is true; safe to poll.
 *   4. Call {@link #learn(String)} when a word is committed (space/punctuation).
 *
 * Privacy: this class is read-only against the network — there is intentionally
 * no HTTP / file-IO surface beyond reading the bundled asset and the private
 * SharedPreferences for {@link UserWordStore}. The PRD's "no logging of
 * non-slash text" invariant applies — none of the methods here log word
 * contents.
 */
public final class SuggestionEngine {

    private static final String TAG = "TurtleSuggest";
    private static final String DICT_ASSET = "dict/en_unigrams.txt";

    private static final int INITIAL_CAPACITY = 82_000;
    private static final int MAX_EDIT_DISTANCE = 2;
    private static final int PREFIX_LENGTH = 7;
    private static final int COUNT_THRESHOLD = 1;
    private static final int LOOKUP_DISTANCE = 2;

    private final UserWordStore userStore;
    private volatile SymSpell symSpell;
    /** Dictionary words sorted by frequency descending — backs prefix completion. */
    private volatile String[] sortedWords;
    private volatile boolean ready;
    private volatile boolean loading;
    /** Fired exactly once after the bundled dictionary finishes loading. The
     *  IME uses this to repaint the suggestion strip — without it, the strip
     *  stays empty until the next keystroke even after the engine is ready. */
    private volatile Runnable onReadyListener;

    public SuggestionEngine(Context ctx) {
        this.userStore = new UserWordStore(ctx);
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * Sets a listener fired when the bundled dictionary becomes ready. If the
     * engine is already ready when this is called, the listener fires
     * synchronously. Replaces any previously set listener.
     *
     * The listener fires on the load thread — callers that need main-thread
     * work should post via a Handler.
     */
    public void setOnReadyListener(Runnable listener) {
        this.onReadyListener = listener;
        if (ready && listener != null) listener.run();
    }

    /** Idempotent. Returns immediately; load happens on a background thread. */
    public void loadAsync(Context ctx) {
        if (ready || loading) return;
        loading = true;
        final Context appCtx = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            long t0 = SystemClock.elapsedRealtime();
            try {
                // Phase 1 — frequency-sorted word list for prefix completion.
                // Fast (~150–250 ms), and this is the primary suggestion source
                // for normal typing. Ship it first so the strip populates almost
                // immediately on cold IME launch instead of waiting on the much
                // slower SymSpell index build.
                sortedWords = loadSortedWords(appCtx);
                ready = true;
                Log.i(TAG, "prefix completion ready in "
                        + (SystemClock.elapsedRealtime() - t0) + "ms");
                Runnable cb = onReadyListener;
                if (cb != null) cb.run();

                // Phase 2 — SymSpell edit-distance index. Slow (~1–3 s) but
                // only a typo-correction fallback; suggest() works without it
                // and folds it in once non-null.
                SymSpell s = new SymSpell(
                        INITIAL_CAPACITY, MAX_EDIT_DISTANCE,
                        PREFIX_LENGTH, COUNT_THRESHOLD);
                try (InputStream in = appCtx.getAssets().open(DICT_ASSET)) {
                    s.loadDictionary(in, 0, 1);
                }
                symSpell = s;
                Log.i(TAG, "symspell ready in "
                        + (SystemClock.elapsedRealtime() - t0) + "ms");
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
     * Top suggestions for {@code prefix}, in priority order:
     *   1. user vocabulary (words the user has typed before, by personal frequency)
     *   2. dictionary prefix completion (predictive — by global frequency)
     *   3. SymSpell edit-distance corrections (fallback for typos)
     *
     * Deduped and capped at {@code max}. Returns lowercase strings — the caller
     * restores case to match the active shift state when committing.
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

    /**
     * Walks the frequency-sorted dictionary collecting up to {@code max} words
     * that start with {@code prefix}. Linear scan, but the array is ordered by
     * frequency so common prefixes find their hits in the first few hundred
     * entries; rare prefixes worst-case scan all 82k words (~1–2 ms on device).
     */
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

    /**
     * Parses the bundled dictionary a second time and returns the words sorted
     * by frequency descending. We could share storage with SymSpell, but its
     * internal {@code words} map is private and we'd rather not patch the
     * vendored source for this.
     */
    private static String[] loadSortedWords(Context appCtx) throws IOException {
        // Each line is "<word><space><frequency>". Build a parallel words/freqs
        // pair so we can sort by freq without boxing into wrapper objects.
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
        // Sort indices by freq desc, then materialize the word array.
        Integer[] idx = new Integer[words.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Long.compare(freqs.get(b), freqs.get(a)));
        String[] sorted = new String[idx.length];
        for (int i = 0; i < idx.length; i++) sorted[i] = words.get(idx[i]);
        return sorted;
    }

    /**
     * Record a committed word so personal vocabulary accrues over time.
     * Filters out tokens that are not pure alpha (hyphen and apostrophe ok),
     * so URLs, numbers, slash commands, and emoji are skipped.
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

    /** Wipes personal vocabulary. Bundled dictionary is unaffected. */
    public void resetUserVocabulary() {
        userStore.clear();
    }
}
