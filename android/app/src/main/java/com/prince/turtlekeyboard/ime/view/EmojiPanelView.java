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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.prince.turtlekeyboard.R;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.ai.ImageHistory;
import com.prince.turtlekeyboard.emoji.EmojiData;
import com.prince.turtlekeyboard.emoji.EmojiSearchIndex;
import com.prince.turtlekeyboard.emoji.RecentEmojiStore;
import com.prince.turtlekeyboard.input.InputTarget;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Categorised emoji grid that replaces the keys. Has a Browse mode (tab bar + 8-column
 * grid) and a Search mode (search bar + compressed grid with the hardware keys re-shown
 * below). Glyphs render via EmojiCompat for consistency across Android 7+.
 */
public class EmojiPanelView extends LinearLayout implements InputTarget {

    public interface OnEmojiPickListener { void onPick(String emoji); }
    public interface OnCloseListener { void onClose(); }
    public interface OnGifPickListener { void onPick(File gifFile); }
    public interface OnGifAddListener { void onAdd(); }

    /** Callbacks the IME uses to re-show the keys and route typed characters into search. */
    public interface OnSearchStateListener {
        void onEnterSearch();
        void onExitSearch();
    }

    private static final int COLUMNS = 8;
    private static final int BG = 0xFF000000;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;
    private static final int TAB_DIVIDER = 0x22FFFFFF;
    private static final int HINT = 0x66FFFFFF;

    private static final int SEARCH_HEIGHT_DP = 172;

    private static final int PANEL_RADIUS_DP = 16;
    private static final int TOP_GAP_DP = 12;
    private static final int SLIDE_OFFSET_DP = 28;
    private static final Interpolator ENTER_EASING =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f);
    private static final Interpolator EXIT_EASING =
            new AccelerateInterpolator(1.2f);

    private final FrameLayout topSwitcher;
    private final LinearLayout normalBar;
    private final LinearLayout searchBar;

    private final ImageView searchTabButton;
    private final LinearLayout tabsRow;
    private final HorizontalScrollView tabsScroll;
    private final ImageView closeButton;

    private final TextView searchBackButton;
    private final TextView searchDisplay;
    private final TextView searchClearButton;

    private final GridView grid;
    private final EmojiGridAdapter adapter;
    private final GifGridView gifGrid;
    private final List<TabView> tabs = new ArrayList<>();

    @Nullable private OnEmojiPickListener pickListener;
    @Nullable private OnCloseListener closeListener;
    @Nullable private OnSearchStateListener searchStateListener;
    @Nullable private OnGifPickListener gifPickListener;
    @Nullable private OnGifAddListener gifAddListener;
    @Nullable private InputTarget.ActiveChangeListener inputModeListener;
    private EmojiData.Category currentCategory = EmojiData.Category.SMILEYS;

    private final ExecutorService gifIo = Executors.newSingleThreadExecutor();
    // Each load increments the epoch; stale callbacks drop themselves.
    private long gifLoadEpoch;

    private int browseHeightPx = ViewGroup.LayoutParams.WRAP_CONTENT;
    private boolean inSearchMode = false;
    private final StringBuilder query = new StringBuilder();

    public EmojiPanelView(Context context) {
        this(context, null);
    }

    public EmojiPanelView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        // Outer view stays transparent; rounded card carries the visual surface.
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

        topSwitcher = new FrameLayout(context);

        normalBar = new LinearLayout(context);
        normalBar.setOrientation(HORIZONTAL);
        normalBar.setGravity(Gravity.CENTER_VERTICAL);
        normalBar.setBackgroundColor(BG);

        closeButton = new ImageView(context);
        closeButton.setImageResource(R.drawable.ic_arrow_back_24);
        closeButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        closeButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        closeButton.setClickable(true);
        closeButton.setFocusable(true);
        closeButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            // Exit search first so the host's close path sees browse-mode geometry.
            if (inSearchMode) exitSearchMode();
            animateOut(() -> { if (closeListener != null) closeListener.onClose(); });
        });
        LayoutParams closeLp = new LayoutParams(dp(40), dp(40));
        closeLp.leftMargin = dp(4);
        normalBar.addView(closeButton, closeLp);

        View headDivider = new View(context);
        headDivider.setBackgroundColor(TAB_DIVIDER);
        LayoutParams hdLp = new LayoutParams(dp(1), dp(24));
        hdLp.leftMargin = dp(4);
        hdLp.rightMargin = dp(4);
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
        tdLp.rightMargin = dp(2);
        normalBar.addView(tailDivider, tdLp);

        searchTabButton = new ImageView(context);
        searchTabButton.setImageResource(R.drawable.ic_search_24);
        searchTabButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        searchTabButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        searchTabButton.setAlpha(0.7f);
        searchTabButton.setClickable(true);
        searchTabButton.setFocusable(true);
        searchTabButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            enterSearchMode();
        });
        LayoutParams searchTabLp = new LayoutParams(dp(40), dp(40));
        searchTabLp.rightMargin = dp(4);
        normalBar.addView(searchTabButton, searchTabLp);

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

        // Emoji grid and GIF grid share this body slot; selectCategory() swaps visibility.
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
            animateOut(() -> {
                if (gifPickListener != null) gifPickListener.onPick(file);
            });
        });
        gifGrid.setOnAddClick(() -> {
            // Mirrors typing `/gif`: close the panel, then let the host start the command.
            animateOut(() -> {
                if (closeListener != null) closeListener.onClose();
                if (gifAddListener != null) gifAddListener.onAdd();
            });
        });

        cardContainer.addView(body,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // height=0 + weight=1 deducts topMargin; MATCH_PARENT would clip the bottom corners.
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cardLp.topMargin = dp(TOP_GAP_DP);
        addView(cardContainer, cardLp);

        buildTabs();
    }

    public void show(OnEmojiPickListener pick, OnCloseListener close) {
        this.pickListener = pick;
        this.closeListener = close;
        // Default to Recent if any; else Smileys (matches Gboard).
        List<String> recents = RecentEmojiStore.get(getContext());
        EmojiData.Category start = recents.isEmpty()
                ? EmojiData.Category.SMILEYS : EmojiData.Category.RECENT;
        selectCategory(start);
        animateIn();
    }

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
        // Reset transforms if removed mid-animation so the next attach starts clean.
        animate().cancel();
        setAlpha(1f);
        setTranslationY(0f);
    }

    public void setOnSearchStateListener(@Nullable OnSearchStateListener l) {
        this.searchStateListener = l;
    }

    public void setOnGifPickListener(@Nullable OnGifPickListener l) {
        this.gifPickListener = l;
    }

    public void setOnGifAddListener(@Nullable OnGifAddListener l) {
        this.gifAddListener = l;
    }

    public boolean isInSearchMode() { return inSearchMode; }

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
        // Icon buttons sit on the black surface; nothing theme-tracked to update right now.
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
            // Pre-clear so the previous tab's data doesn't flash while the async list loads.
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

    private void enterSearchMode() {
        if (inSearchMode) return;
        inSearchMode = true;
        // Snap height + grid contents in one layout pass; visual smoothness comes from
        // the bar crossfade on the panel and the keys sliding up from below.
        query.setLength(0);
        renderQuery();
        setPanelHeight(dp(SEARCH_HEIGHT_DP));
        crossfadeBars(normalBar, searchBar);
        if (searchStateListener != null) searchStateListener.onEnterSearch();
        if (inputModeListener != null) inputModeListener.onActiveChanged(this, true);
    }

    private void exitSearchMode() {
        if (!inSearchMode) return;
        inSearchMode = false;
        // Hide keys + snap height + restore grid in the same frame so IME-window height
        // goes 312→240 in one reflow. Doing them in separate ticks makes keys visibly
        // jump down (panel grows, pushes keys 68dp before they're hidden) and triggers
        // two IME window resizes back-to-back.
        if (searchStateListener != null) searchStateListener.onExitSearch();
        query.setLength(0);
        selectCategory(currentCategory);
        setPanelHeight(browseHeightPx);
        crossfadeBars(searchBar, normalBar);
        if (inputModeListener != null) inputModeListener.onActiveChanged(this, false);
    }

    public void setOnInputActiveChangedListener(@Nullable InputTarget.ActiveChangeListener l) {
        this.inputModeListener = l;
    }

    @Override public void appendChar(char c) { appendQueryChar(c); }
    @Override public void onBackspace() { backspaceQuery(); }

    private void setPanelHeight(int px) {
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null || lp.height == px) return;
        lp.height = px;
        setLayoutParams(lp);
    }

    /** Property-animator crossfade in place — no layout pass, no transition-framework capture. */
    private void crossfadeBars(View out, View in) {
        out.animate().cancel();
        in.animate().cancel();
        in.setAlpha(0f);
        in.setVisibility(VISIBLE);
        in.animate().alpha(1f).setDuration(180).start();
        out.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            out.setVisibility(GONE);
            out.setAlpha(1f);
        }).start();
    }

    public void appendQueryChar(char c) {
        if (!inSearchMode) return;
        query.append(c);
        renderQuery();
    }

    /** Backspace on an empty query exits search mode (mirrors Gboard). */
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
            // Fall back to recents or smileys so the grid is never empty between keystrokes.
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

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

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
