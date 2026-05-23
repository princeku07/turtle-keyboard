package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.ai.ImageHistory;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

import java.io.File;
import java.util.List;

/**
 * In-keyboard grid of generated images. Mounts in the quick-panel host so it
 * replaces the keys; tapping a tile inserts that image via commitContent.
 * Thumbnails decode async through {@link ThumbnailLoader}; an empty state
 * replaces the grid when the user has no history yet.
 */
public class HistoryPanelView extends FrameLayout {

    public interface OnPick { void onPick(File file); }
    public interface OnClose { void onClose(); }

    private static final int GRID_COLS = 3;
    private static final int TILE_GUTTER_DP = 6;
    private static final int PAGE_PAD_DP = 12;
    private static final int TILE_RADIUS_DP = 10;
    private static final int TILE_HEIGHT_DP = 96;
    private static final int PANEL_RADIUS_DP = 16;
    private static final int TOP_GAP_DP = 12;
    private static final int SLIDE_OFFSET_DP = 28;
    private static final Interpolator ENTER_EASING =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f);
    private static final Interpolator EXIT_EASING =
            new AccelerateInterpolator(1.2f);

    // Hardcoded dark palette so generated media always shows on a high-contrast bg,
    // regardless of the keyboard's light/dark KeyboardTheme.
    private static final int BG           = 0xFF000000;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;
    private static final int TEXT_MUTED   = 0xA0F5F5F5;
    private static final int DIVIDER      = 0x22FFFFFF;
    private static final int CHIP_FILL    = 0x14FFFFFF;
    private static final int RIPPLE_WASH  = 0x33FFFFFF;

    private LinearLayout container;
    private LinearLayout header;
    private TextView title;
    private TextView count;
    private TextView close;
    private View divider;

    private GridView grid;
    private LinearLayout emptyState;
    private TextView emptyIcon;
    private TextView emptyHeading;
    private TextView emptySubtitle;

    private List<ImageHistory.Entry> entries;
    @Nullable private KeyboardTheme theme;

    public HistoryPanelView(Context c) { super(c); init(); }
    public HistoryPanelView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        final int radius = dp(PANEL_RADIUS_DP);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(BG);
        cardBg.setCornerRadius(radius);
        cardBg.setStroke(dp(1), 0x33FFFFFF);
        container.setBackground(cardBg);
        container.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        container.setClipToOutline(true);
        LayoutParams containerLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        containerLp.topMargin = dp(TOP_GAP_DP);
        addView(container, containerLp);

        buildHeader();
        buildDivider();
        buildGrid();
        buildEmptyState();
    }

    private void buildHeader() {
        header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(10), dp(10), dp(10));

        title = new TextView(getContext());
        title.setText("History");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        title.setLetterSpacing(0.01f);
        title.setTextColor(TEXT_PRIMARY);
        header.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        count = new TextView(getContext());
        count.setText("");
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        count.setIncludeFontPadding(false);
        count.setTextColor(TEXT_MUTED);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
        countLp.leftMargin = dp(8);
        header.addView(count, countLp);

        close = new TextView(getContext());
        close.setText("×");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        close.setTypeface(close.getTypeface(), Typeface.BOLD);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setTextColor(TEXT_PRIMARY);
        close.setClickable(true);
        close.setFocusable(true);
        GradientDrawable chip = new GradientDrawable();
        chip.setShape(GradientDrawable.OVAL);
        chip.setColor(CHIP_FILL);
        close.setBackground(chip);
        close.setForeground(ovalRipple(RIPPLE_WASH));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        header.addView(close, closeLp);

        container.addView(header,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void buildDivider() {
        divider = new View(getContext());
        divider.setBackgroundColor(DIVIDER);
        container.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
    }

    private void buildGrid() {
        grid = new GridView(getContext());
        grid.setNumColumns(GRID_COLS);
        grid.setHorizontalSpacing(dp(TILE_GUTTER_DP));
        grid.setVerticalSpacing(dp(TILE_GUTTER_DP));
        grid.setPadding(dp(PAGE_PAD_DP), dp(PAGE_PAD_DP),
                dp(PAGE_PAD_DP), dp(PAGE_PAD_DP));
        grid.setClipToPadding(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(new GradientDrawable());
        grid.setVisibility(GONE);
        container.addView(grid,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        0, 1f));
    }

    private void buildEmptyState() {
        emptyState = new LinearLayout(getContext());
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(dp(24), dp(8), dp(24), dp(8));
        emptyState.setVisibility(GONE);

        emptyIcon = new TextView(getContext());
        emptyIcon.setText("🖼️");
        emptyIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f);
        emptyIcon.setIncludeFontPadding(false);
        emptyIcon.setAlpha(0.75f);
        emptyState.addView(emptyIcon, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        emptyHeading = new TextView(getContext());
        emptyHeading.setText("No generations yet");
        emptyHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        emptyHeading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        emptyHeading.setIncludeFontPadding(false);
        emptyHeading.setTextColor(TEXT_PRIMARY);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(12);
        emptyState.addView(emptyHeading, hLp);

        emptySubtitle = new TextView(getContext());
        emptySubtitle.setText("Try /sticker, /cap, or /gif\nin any chat.");
        emptySubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        emptySubtitle.setIncludeFontPadding(false);
        emptySubtitle.setGravity(Gravity.CENTER);
        emptySubtitle.setLineSpacing(dp(2), 1f);
        emptySubtitle.setTextColor(TEXT_MUTED);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(4);
        emptyState.addView(emptySubtitle, sLp);

        container.addView(emptyState, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    public void show(List<ImageHistory.Entry> entries, OnPick onPick, OnClose onClose) {
        this.entries = entries;
        close.setOnClickListener(v ->
                animateOut(() -> { if (onClose != null) onClose.onClose(); }));
        boolean empty = entries == null || entries.isEmpty();
        count.setText(empty ? "" : "· " + entries.size());
        if (empty) {
            grid.setVisibility(GONE);
            emptyState.setVisibility(VISIBLE);
        } else {
            emptyState.setVisibility(GONE);
            grid.setVisibility(VISIBLE);
            grid.setAdapter(new ThumbAdapter());
            grid.setOnItemClickListener((parent, v, position, id) -> {
                if (onPick != null) {
                    final java.io.File picked = this.entries.get(position).file;
                    animateOut(() -> onPick.onPick(picked));
                }
            });
        }
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
        animate().cancel();
        setAlpha(1f);
        setTranslationY(0f);
    }

    /** No-op for API symmetry — the panel pins to its own dark palette. */
    @SuppressWarnings("unused")
    public void applyTheme(KeyboardTheme t) {
        this.theme = t;
    }

    private static RippleDrawable ovalRipple(int color) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(color), null, mask);
    }

    private static RippleDrawable roundedRipple(int color, float radiusPx) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(color), null, mask);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private class ThumbAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int p) { return entries.get(p); }
        @Override public long getItemId(int p) { return entries.get(p).ts; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            HistoryTile tile;
            if (convertView instanceof HistoryTile) {
                tile = (HistoryTile) convertView;
            } else {
                tile = new HistoryTile(getContext());
                tile.setLayoutParams(new GridView.LayoutParams(
                        GridView.LayoutParams.MATCH_PARENT, dp(TILE_HEIGHT_DP)));
            }
            tile.bind(entries.get(position).file);
            return tile;
        }
    }

    private class HistoryTile extends FrameLayout {
        private final ImageView image;
        private final TextView badge;

        HistoryTile(Context ctx) {
            super(ctx);
            // Outline on the container so the badge clips with the rounded corners.
            final int radius = dp(TILE_RADIUS_DP);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            setClipToOutline(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(CHIP_FILL);
            bg.setCornerRadius(radius);
            setBackground(bg);
            setForeground(roundedRipple(RIPPLE_WASH, radius));

            image = new ImageView(ctx);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(image, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));

            badge = new TextView(ctx);
            badge.setTextColor(0xFFFFFFFF);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            badge.setTypeface(badge.getTypeface(), Typeface.BOLD);
            badge.setLetterSpacing(0.08f);
            badge.setIncludeFontPadding(false);
            badge.setPadding(dp(5), dp(2), dp(5), dp(2));
            GradientDrawable pill = new GradientDrawable();
            pill.setShape(GradientDrawable.RECTANGLE);
            pill.setColor(0xCC000000);
            pill.setCornerRadius(dp(8));
            badge.setBackground(pill);
            LayoutParams blp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.BOTTOM | Gravity.END;
            blp.rightMargin = dp(5);
            blp.bottomMargin = dp(5);
            addView(badge, blp);
        }

        void bind(File file) {
            ThumbnailLoader.load(file, dp(TILE_HEIGHT_DP), image);
            badge.setText(file.getName().endsWith(".gif") ? "GIF" : "IMG");
        }
    }
}
