package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.ai.ImageHistory;
import com.prince.turtlekeyboard.emoji.EmojiData;
import com.prince.turtlekeyboard.emoji.EmojiSearchIndex;
import com.prince.turtlekeyboard.emoji.RecentEmojiStore;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Categorised emoji grid that replaces the keys when the user taps the leading
 * 😀 chip on the suggestion strip. The panel has two modes:
 *
 * <ul>
 *   <li><b>Browse</b>: a tab bar (🔍 + Recent + nine Unicode categories) on top and a
 *       full-height 8-column {@link GridView} of emoji cells below. Replaces the
 *       keyboard area completely.</li>
 *   <li><b>Search</b>: a search bar (back arrow, query display, clear) on top and a
 *       compressed three-row results grid below. The hardware keys come back into
 *       view; the IME routes typed characters to {@link #appendQueryChar(char)}
 *       / {@link #backspaceQuery()} via the listener registered with
 *       {@link #setOnSearchStateListener(OnSearchStateListener)}.</li>
 * </ul>
 *
 * <p>Glyphs are rendered as Unicode strings inside {@link TextView}s; EmojiCompat
 * (initialised in {@code TurtleApp.onCreate}) ensures consistent rendering across
 * Android 7+.
 */
public class EmojiPanelView extends LinearLayout {

    public interface OnEmojiPickListener { void onPick(String emoji); }
    public interface OnCloseListener { void onClose(); }
    /** Fires when the user taps a GIF tile in the GIFs tab. The IME commits
     *  the file as an inline GIF via its commitContent path. */
    public interface OnGifPickListener { void onPick(File gifFile); }

    /** Callbacks the IME implements so it can re-show the keys + route typed
     *  characters to this panel while the search bar is open. */
    public interface OnSearchStateListener {
        void onEnterSearch();
        void onExitSearch();
    }

    private static final int COLUMNS = 8;
    private static final int BG = 0xFF000000;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;
    private static final int TAB_DIVIDER = 0x22FFFFFF;
    private static final int HINT = 0x66FFFFFF;

    /** Height of the search-mode panel (search bar + three grid rows). The keys
     *  re-appear below in this mode, so the panel must not occupy the whole
     *  keyboard slot. */
    private static final int SEARCH_HEIGHT_DP = 172;

    /** Panel-card corner radius — matches HistoryPanelView and the split
     *  surfaces so the keyboard's panel family reads as one rounded sheet. */
    private static final int PANEL_RADIUS_DP = 16;
    /** Space above the rounded card so the keyboard chrome behind us shows
     *  through and the panel reads as a floating sheet. */
    private static final int TOP_GAP_DP = 12;
    /** Translate-from offset for the slide-up entrance / slide-down exit. */
    private static final int SLIDE_OFFSET_DP = 28;
    /** Material's "emphasized" easing — quick start, organic deceleration,
     *  smooth settle. Shared across all keyboard panels. */
    private static final Interpolator ENTER_EASING =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f);
    private static final Interpolator EXIT_EASING =
            new AccelerateInterpolator(1.2f);

    // Top-bar switcher — only one of normalBar / searchBar is visible at a time.
    private final FrameLayout topSwitcher;
    private final LinearLayout normalBar;
    private final LinearLayout searchBar;

    private final TextView searchTabButton;
    private final LinearLayout tabsRow;
    private final HorizontalScrollView tabsScroll;
    private final TextView abcButton;

    private final TextView searchBackButton;
    private final TextView searchDisplay;
    private final TextView searchClearButton;

    private final GridView grid;
    private final EmojiGridAdapter adapter;
    /** GIF tab body — kept hidden until the GIFs category is selected so it
     *  doesn't cost anything on the emoji-only paths. Same parent slot as
     *  {@link #grid} (only one is visible at a time). */
    private final GifGridView gifGrid;
    private final List<TabView> tabs = new ArrayList<>();

    @Nullable private OnEmojiPickListener pickListener;
    @Nullable private OnCloseListener closeListener;
    @Nullable private OnSearchStateListener searchStateListener;
    @Nullable private OnGifPickListener gifPickListener;
    private EmojiData.Category currentCategory = EmojiData.Category.SMILEYS;

    /** Background loader for {@link ImageHistory#list(Context)} so the panel
     *  never blocks the IME thread on disk I/O when the GIFs tab is opened. */
    private final ExecutorService gifIo = Executors.newSingleThreadExecutor();
    /** Monotonic counter — every GIF load increments this, and a stale
     *  callback whose epoch no longer matches is dropped. Prevents the user
     *  spamming category taps from clobbering a newer load with an older
     *  result on the main thread. */
    private long gifLoadEpoch;

    /** Browse-mode height the IME assigns when mounting. Cached so we can
     *  restore it when leaving search mode. */
    private int browseHeightPx = ViewGroup.LayoutParams.WRAP_CONTENT;
    private boolean inSearchMode = false;
    private final StringBuilder query = new StringBuilder();

    public EmojiPanelView(Context context) {
        this(context, null);
    }

    public EmojiPanelView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        // Outer view is transparent so the keyboard chrome shows through the
        // TOP_GAP above the card — the panel reads as a floating sheet
        // rather than welded to the keyboard area.

        // Rounded-card container — all real content (top bar, grid, GIF
        // grid) lives inside this. Card carries the background, hairline
        // stroke, outline + clipToOutline. The outer view stays transparent.
        final int radius = dp(PANEL_RADIUS_DP);
        final LinearLayout cardContainer = new LinearLayout(context);
        cardContainer.setOrientation(VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(BG);
        cardBg.setCornerRadius(radius);
        cardBg.setStroke(dp(1), 0x33FFFFFF);
        cardContainer.setBackground(cardBg);
        cardContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        cardContainer.setClipToOutline(true);

        // ── Top-bar switcher (40dp fixed) ──
        topSwitcher = new FrameLayout(context);

        // Normal (browse) top bar: 🔍 + scrolling tabs + ABC.
        normalBar = new LinearLayout(context);
        normalBar.setOrientation(HORIZONTAL);
        normalBar.setGravity(Gravity.CENTER_VERTICAL);
        normalBar.setBackgroundColor(BG);

        searchTabButton = new TextView(context);
        searchTabButton.setText("🔍");
        searchTabButton.setGravity(Gravity.CENTER);
        searchTabButton.setIncludeFontPadding(false);
        searchTabButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        searchTabButton.setAlpha(0.7f);
        searchTabButton.setClickable(true);
        searchTabButton.setFocusable(true);
        searchTabButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            enterSearchMode();
        });
        LayoutParams searchTabLp = new LayoutParams(dp(40), dp(40));
        normalBar.addView(searchTabButton, searchTabLp);

        // Thin divider between 🔍 and the tab strip.
        View headDivider = new View(context);
        headDivider.setBackgroundColor(TAB_DIVIDER);
        LayoutParams hdLp = new LayoutParams(dp(1), dp(24));
        hdLp.leftMargin = dp(2);
        hdLp.rightMargin = dp(2);
        normalBar.addView(headDivider, hdLp);

        tabsScroll = new HorizontalScrollView(context);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsScroll.setClipChildren(false);
        tabsRow = new LinearLayout(context);
        tabsRow.setOrientation(HORIZONTAL);
        tabsRow.setGravity(Gravity.CENTER_VERTICAL);
        tabsScroll.addView(tabsRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        normalBar.addView(tabsScroll, new LayoutParams(0, dp(40), 1f));

        View tailDivider = new View(context);
        tailDivider.setBackgroundColor(TAB_DIVIDER);
        LayoutParams tdLp = new LayoutParams(dp(1), dp(24));
        tdLp.leftMargin = dp(4);
        tdLp.rightMargin = dp(4);
        normalBar.addView(tailDivider, tdLp);

        abcButton = new TextView(context);
        abcButton.setText("ABC");
        abcButton.setTextColor(TEXT_PRIMARY);
        abcButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        abcButton.setTypeface(abcButton.getTypeface(), Typeface.BOLD);
        abcButton.setGravity(Gravity.CENTER);
        abcButton.setPadding(dp(12), dp(6), dp(12), dp(6));
        abcButton.setClickable(true);
        abcButton.setFocusable(true);
        abcButton.setBackground(pillBackground(0x14FFFFFF, dp(14)));
        abcButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            // ABC always closes the whole panel. We exit search first so the
            // IME's state stays consistent (the search mode adjusted our
            // height + asked the IME to re-show the keys; the host's close
            // path assumes browse-mode geometry). Then slide-out before
            // firing the host's teardown so the dismissal feels like one
            // motion instead of an abrupt cut.
            if (inSearchMode) exitSearchMode();
            animateOut(() -> { if (closeListener != null) closeListener.onClose(); });
        });
        LayoutParams abcLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        abcLp.rightMargin = dp(8);
        normalBar.addView(abcButton, abcLp);

        // Search top bar — back arrow, query display, clear.
        searchBar = new LinearLayout(context);
        searchBar.setOrientation(HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackgroundColor(BG);
        searchBar.setVisibility(GONE);

        searchBackButton = new TextView(context);
        searchBackButton.setText("←");
        searchBackButton.setTextColor(TEXT_PRIMARY);
        searchBackButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        searchBackButton.setGravity(Gravity.CENTER);
        searchBackButton.setClickable(true);
        searchBackButton.setFocusable(true);
        searchBackButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            exitSearchMode();
        });
        searchBar.addView(searchBackButton, new LayoutParams(dp(40), dp(40)));

        searchDisplay = new TextView(context);
        searchDisplay.setTextColor(TEXT_PRIMARY);
        searchDisplay.setHintTextColor(HINT);
        searchDisplay.setHint("Search emoji");
        searchDisplay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        searchDisplay.setSingleLine(true);
        searchDisplay.setEllipsize(android.text.TextUtils.TruncateAt.START);
        searchDisplay.setPadding(dp(8), 0, dp(8), 0);
        searchBar.addView(searchDisplay, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        searchClearButton = new TextView(context);
        searchClearButton.setText("✕");
        searchClearButton.setTextColor(TEXT_PRIMARY);
        searchClearButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        searchClearButton.setGravity(Gravity.CENTER);
        searchClearButton.setClickable(true);
        searchClearButton.setFocusable(true);
        searchClearButton.setVisibility(INVISIBLE);
        searchClearButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            clearQuery();
        });
        searchBar.addView(searchClearButton, new LayoutParams(dp(40), dp(40)));

        topSwitcher.addView(normalBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        topSwitcher.addView(searchBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        cardContainer.addView(topSwitcher,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        // ── Body: emoji grid + (hidden by default) GIF grid ──
        // Both live in the same slot below the tabs; visibility is swapped
        // by selectCategory() so only one shows at a time.
        FrameLayout body = new FrameLayout(context);
        body.setBackgroundColor(BG);

        grid = new GridView(context);
        grid.setNumColumns(COLUMNS);
        grid.setVerticalSpacing(dp(2));
        grid.setHorizontalSpacing(dp(2));
        grid.setSelector(new GradientDrawable());
        grid.setBackgroundColor(BG);
        grid.setPadding(dp(4), dp(4), dp(4), dp(4));
        adapter = new EmojiGridAdapter();
        grid.setAdapter(adapter);
        body.addView(grid, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        gifGrid = new GifGridView(context);
        gifGrid.setVisibility(GONE);
        body.addView(gifGrid, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        gifGrid.setOnGifPick(file -> {
            // Tap on a GIF tile: slide-out first so the panel doesn't pop
            // away the instant the user lifts their finger. The host's
            // onPick fires at the end of the exit animation, then inserts
            // the GIF + tears down the panel.
            animateOut(() -> {
                if (gifPickListener != null) gifPickListener.onPick(file);
            });
        });

        cardContainer.addView(body,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Mount the populated card on the outer view with a top gap so the
        // chrome above (the IME's panel slot host) shows through. Using
        // height=0 + weight=1 instead of MATCH_PARENT here so the
        // LinearLayout deducts the topMargin from the card's size — with
        // MATCH_PARENT the card would extend below the outer view's
        // bottom edge by the margin amount and lose its bottom corners
        // to clipping.
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cardLp.topMargin = dp(TOP_GAP_DP);
        addView(cardContainer, cardLp);

        buildTabs();
    }

    public void show(OnEmojiPickListener pick, OnCloseListener close) {
        this.pickListener = pick;
        this.closeListener = close;
        // Open on Recent when the user has any, otherwise Smileys — same default
        // as Gboard so the panel feels familiar.
        List<String> recents = RecentEmojiStore.get(getContext());
        EmojiData.Category start = recents.isEmpty()
                ? EmojiData.Category.SMILEYS : EmojiData.Category.RECENT;
        selectCategory(start);
        animateIn();
    }

    /** Slide-up + fade-in. Material-emphasized easing gives an organic
     *  decelerating settle that matches HistoryPanelView and the split
     *  surfaces, so all the keyboard's panels rise with one vocabulary.
     *  Cancels any in-flight exit so a rapid show after a partial exit
     *  doesn't leave the panel mid-fade. */
    private void animateIn() {
        animate().cancel();
        setAlpha(0f);
        setTranslationY(dp(SLIDE_OFFSET_DP));
        animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320)
                .setInterpolator(ENTER_EASING)
                .start();
    }

    /** Mirror: fade out + slide down, then fire {@code onEnd} so the host can
     *  tear down the panel as a follow-up to the visual exit. Reset the
     *  transform at the end so a re-attach starts clean. */
    private void animateOut(Runnable onEnd) {
        animate().cancel();
        animate()
                .alpha(0f)
                .translationY(dp(SLIDE_OFFSET_DP))
                .setDuration(220)
                .setInterpolator(EXIT_EASING)
                .withEndAction(() -> {
                    setAlpha(1f);
                    setTranslationY(0f);
                    if (onEnd != null) onEnd.run();
                })
                .start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Belt-and-braces: if the host force-removes us mid-animation,
        // cancel and reset so the next attach starts from a clean state.
        animate().cancel();
        setAlpha(1f);
        setTranslationY(0f);
    }

    public void setOnSearchStateListener(@Nullable OnSearchStateListener l) {
        this.searchStateListener = l;
    }

    /** Register the IME's GIF-commit callback. The panel calls this whenever
     *  the user taps a tile inside the GIFs tab; the IME then routes the file
     *  through its commitContent path (mirroring how generated GIFs are
     *  inserted right after generation in the host chat). */
    public void setOnGifPickListener(@Nullable OnGifPickListener l) {
        this.gifPickListener = l;
    }

    /** True while the search bar is showing. The IME polls this to decide
     *  whether key strokes should drive {@link #appendQueryChar} or commit to
     *  the host editor. */
    public boolean isInSearchMode() { return inSearchMode; }

    /** The IME calls this from {@code showEmojiPanel} so the panel can restore
     *  itself to the full keyboard height when the user backs out of search. */
    public void setBrowseHeightPx(int px) {
        this.browseHeightPx = px;
        if (!inSearchMode) {
            ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp != null) {
                lp.height = px;
                setLayoutParams(lp);
            }
        }
    }

    public void applyTheme(KeyboardTheme theme) {
        // Surface stays black so it reads as a continuous panel with the dark
        // keyboard chrome; only the ABC close pill tracks the theme accent so
        // brand changes propagate.
        abcButton.setBackground(pillBackground(theme == null
                ? 0x14FFFFFF : (theme.accent & 0x00FFFFFF) | 0x33000000, dp(14)));
    }

    private void buildTabs() {
        tabsRow.removeAllViews();
        tabs.clear();
        for (EmojiData.Category c : EmojiData.Category.values()) {
            TabView tab = new TabView(getContext(), c);
            tab.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                selectCategory(c);
            });
            tabs.add(tab);
            tabsRow.addView(tab, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
    }

    private void selectCategory(EmojiData.Category category) {
        currentCategory = category;
        for (TabView t : tabs) t.setSelectedState(t.category == category);
        if (category == EmojiData.Category.GIFS) {
            // Body swap: hide the emoji grid, show the GIF grid. Pre-set it
            // empty so the previous tab's data doesn't flash through while
            // the async list is in flight.
            grid.setVisibility(GONE);
            gifGrid.setVisibility(VISIBLE);
            gifGrid.setData(new ArrayList<>());
            loadGifsAsync();
            return;
        }
        gifGrid.setVisibility(GONE);
        grid.setVisibility(VISIBLE);
        String[] data = category == EmojiData.Category.RECENT
                ? RecentEmojiStore.get(getContext()).toArray(new String[0])
                : EmojiData.forCategory(category);
        adapter.setData(data);
    }

    /** Scans {@link ImageHistory} on the background executor for entries
     *  whose file ends in {@code .gif} (i.e. /gif and /gift outputs, never
     *  the PNG sprite-sheet debug artifacts). Posts the result back to the
     *  main thread, dropping anything but the most recent load via the
     *  {@link #gifLoadEpoch} guard. */
    private void loadGifsAsync() {
        final long epoch = ++gifLoadEpoch;
        final Context ctx = getContext().getApplicationContext();
        gifIo.execute(() -> {
            List<File> gifs = new ArrayList<>();
            for (ImageHistory.Entry e : ImageHistory.list(ctx)) {
                if (e.file != null && e.file.getName().endsWith(".gif")) {
                    gifs.add(e.file);
                }
            }
            post(() -> {
                if (epoch != gifLoadEpoch) return;
                if (currentCategory != EmojiData.Category.GIFS) return;
                gifGrid.setData(gifs);
            });
        });
    }

    private void onCellPick(String emoji) {
        if (emoji == null || emoji.isEmpty()) return;
        RecentEmojiStore.push(getContext(), emoji);
        if (pickListener != null) pickListener.onPick(emoji);
    }

    // ── Search-mode lifecycle ──────────────────────────────────────────────

    private void enterSearchMode() {
        if (inSearchMode) return;
        inSearchMode = true;
        beginCoordinatedTransition();
        normalBar.setVisibility(GONE);
        searchBar.setVisibility(VISIBLE);
        query.setLength(0);
        renderQuery();

        // Shrink the panel so the hardware keys can re-appear below.
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null) {
            lp.height = dp(SEARCH_HEIGHT_DP);
            setLayoutParams(lp);
        }
        if (searchStateListener != null) searchStateListener.onEnterSearch();
    }

    private void exitSearchMode() {
        if (!inSearchMode) return;
        inSearchMode = false;
        beginCoordinatedTransition();
        searchBar.setVisibility(GONE);
        normalBar.setVisibility(VISIBLE);
        query.setLength(0);

        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null) {
            lp.height = browseHeightPx;
            setLayoutParams(lp);
        }
        if (searchStateListener != null) searchStateListener.onExitSearch();
        // Re-show whichever category we were on before searching.
        selectCategory(currentCategory);
    }

    /** Stage an Auto-style transition on the IME's scene root so the panel
     *  height change, the top-bar crossfade, and the keyboard re-show all
     *  animate together in one coordinated motion. TransitionManager captures
     *  state on the next layout pass, so every change made synchronously after
     *  this call (including the IME callback's {@code keys.setVisibility(...)})
     *  is part of the same animation. */
    private void beginCoordinatedTransition() {
        View root = getRootView();
        if (!(root instanceof ViewGroup)) return;
        androidx.transition.AutoTransition t = new androidx.transition.AutoTransition();
        t.setDuration(180);
        t.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        androidx.transition.TransitionManager.beginDelayedTransition((ViewGroup) root, t);
    }

    /** Append one character to the search query — wired from the IME's key
     *  handler. Accepts any printable Unicode {@code char}; non-letters are
     *  fine too (digits, punctuation) but most keyword matches are alphabetic. */
    public void appendQueryChar(char c) {
        if (!inSearchMode) return;
        query.append(c);
        renderQuery();
    }

    /** Backspace removes one char; on an already-empty query it exits search
     *  mode entirely (mirrors Gboard). */
    public void backspaceQuery() {
        if (!inSearchMode) return;
        if (query.length() == 0) {
            exitSearchMode();
            return;
        }
        query.deleteCharAt(query.length() - 1);
        renderQuery();
    }

    public void clearQuery() {
        if (!inSearchMode) return;
        query.setLength(0);
        renderQuery();
    }

    private void renderQuery() {
        String q = query.toString();
        searchDisplay.setText(q);
        searchClearButton.setVisibility(q.isEmpty() ? INVISIBLE : VISIBLE);
        if (q.isEmpty()) {
            // Empty query: show the user's recents (or smileys if none) so the
            // grid never looks broken between keystrokes.
            List<String> recents = RecentEmojiStore.get(getContext());
            String[] fallback = recents.isEmpty()
                    ? EmojiData.forCategory(EmojiData.Category.SMILEYS)
                    : recents.toArray(new String[0]);
            adapter.setData(fallback);
        } else {
            List<String> hits = EmojiSearchIndex.search(q);
            adapter.setData(hits.toArray(new String[0]));
        }
    }

    private GradientDrawable pillBackground(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** Single category tab. Shows the category's representative glyph; the
     *  selected tab carries a soft underline-style fill to read as active. */
    private static class TabView extends TextView {
        final EmojiData.Category category;

        TabView(Context ctx, EmojiData.Category c) {
            super(ctx);
            this.category = c;
            setText(c.tabGlyph);
            setGravity(Gravity.CENTER);
            setIncludeFontPadding(false);
            setClickable(true);
            setFocusable(true);
            if (c.tabIsText) {
                // Text-chip styling: smaller font + bold so 3 letters read
                // as a tag at the same visual weight as a single-emoji tab.
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                setTypeface(getTypeface(), Typeface.BOLD);
                setTextColor(0xFFF5F5F5);
                setLetterSpacing(0.08f);
            } else {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
            }
            setSelectedState(false);
        }

        void setSelectedState(boolean selected) {
            if (selected) {
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setColor(0x22FFFFFF);
                bg.setCornerRadius(dp(8));
                setBackground(bg);
                setAlpha(1f);
            } else {
                setBackground(null);
                setAlpha(0.55f);
            }
        }

        private int dp(int v) {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    getResources().getDisplayMetrics());
        }
    }

    /** Minimal {@link BaseAdapter} for the grid — recycles {@link TextView}s
     *  and only mutates their {@code text} so scrolling stays smooth. */
    private class EmojiGridAdapter extends BaseAdapter {
        private String[] data = new String[0];

        void setData(String[] next) {
            this.data = next == null ? new String[0] : next;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return data.length; }
        @Override public Object getItem(int position) { return data[position]; }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView cell;
            if (convertView instanceof TextView) {
                cell = (TextView) convertView;
            } else {
                cell = new TextView(getContext());
                cell.setGravity(Gravity.CENTER);
                cell.setIncludeFontPadding(false);
                cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
                cell.setTextColor(Color.WHITE);
                cell.setClickable(true);
                cell.setFocusable(true);
                cell.setHeight(dp(44));
                cell.setBackground(cellRipple());
            }
            final String emoji = data[position];
            cell.setText(emoji);
            cell.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                onCellPick(emoji);
            });
            return cell;
        }

        private GradientDrawable cellRipple() {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.RECTANGLE);
            d.setColor(0x00000000);
            d.setCornerRadius(dp(6));
            return d;
        }
    }
}
