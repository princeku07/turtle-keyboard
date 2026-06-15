package com.prince.turtlekeyboard.suggest;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.prince.turtlekeyboard.suggest.dafsa.DafsaDict;
import com.prince.turtlekeyboard.suggest.symspell.SuggestItem;
import com.prince.turtlekeyboard.suggest.symspell.SymSpell;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final String DAWG_ASSET = "dict/en.dawg";

    private static final int INITIAL_CAPACITY = 82_000;
    /** Distance 1 only — distance 2 over 82k words OOMs against the 256MB IME heap. */
    private static final int MAX_EDIT_DISTANCE = 1;
    private static final int PREFIX_LENGTH = 7;
    private static final int COUNT_THRESHOLD = 1;
    private static final int LOOKUP_DISTANCE = 1;

    private final UserWordStore userStore;
    private volatile SymSpell symSpell;
    /** mmapped DAFSA used for prefix completion (best-first, O(prefix + K)). */
    private volatile DafsaDict dafsa;
    private volatile boolean ready;
    private volatile boolean loading;
    /** Fired once when the bundled dictionary finishes loading. */
    private volatile Runnable onReadyListener;
    /** Owns the load thread so {@link #shutdown()} can stop it cleanly when the
     *  IME is destroyed mid-load (e.g. focus bounce during first-run onboarding). */
    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TurtleSuggestLoad");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });

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
        loadExecutor.execute(() -> {
            long t0 = SystemClock.elapsedRealtime();
            try {
                // Phase 1: mmap the DAFSA — no parse, no allocation per word.
                // Cold cost is the mmap call itself (~1ms) plus the OS paging in
                // touched bytes on first lookup; the asset is uncompressed in the
                // APK (aaptOptions { noCompress("dawg") }) so this maps directly.
                dafsa = DafsaDict.openFromAsset(appCtx, DAWG_ASSET);
                if (dafsa != null) {
                    ready = true;
                    Log.i(TAG, "dafsa ready in "
                            + (SystemClock.elapsedRealtime() - t0) + "ms ("
                            + dafsa.nodeCount() + " nodes)");
                    Runnable cb = onReadyListener;
                    if (cb != null) cb.run();
                } else {
                    Log.w(TAG, "dafsa missing — prefix completion will be empty");
                }

                // Phase 2: SymSpell edit-distance index for typo correction.
                // Still reads the text unigrams asset; DAFSA replaces only the
                // prefix-completion path. OOM here degrades to prefix-only
                // instead of killing the IME.
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
        });
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

    /** Top-K prefix completions via the DAFSA. Empty until the DAFSA is mmapped. */
    private List<String> prefixComplete(String prefix, int max) {
        DafsaDict d = dafsa;
        if (d == null) return Collections.emptyList();
        return d.completions(prefix, max);
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

    /** Persist any in-memory bumps that haven't reached disk yet. Cheap when
     *  there's nothing pending — safe to call on focus-out. */
    public void flush() {
        userStore.flush();
    }

    /** Flush + tear down both background executors (load thread + user-word IO).
     *  Call from {@code InputMethodService.onDestroy}. */
    public void shutdown() {
        userStore.shutdown();
        loadExecutor.shutdown();
    }
}
