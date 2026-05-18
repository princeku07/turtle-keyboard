package com.prince.split.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.split.SplitHistory;

import java.util.List;
import java.util.Locale;

/**
 * In-keyboard saved-splits list. Mirrors the activity flow but stays inside
 * the panel slot so the user never leaves the host app. Each row is
 * tap-to-copy; footer offers Clear/Done.
 *
 * <p>Visual language matches the rest of the keyboard's polished panels
 * (history, emoji, GIFs, split): pure-black surface, white text at two
 * opacities, subtle-white chip secondary buttons, brand-lime primary CTA.
 * List rows are rounded cards with ripple foregrounds for press feedback.
 */
public class SplitHistoryView extends LinearLayout {

    public interface Listener {
        /** Copy a summary line for the entry (or wire to share, etc). */
        void onCopy(SplitHistory.Entry entry);
        /** Wipe the persisted history. */
        void onClear();
        /** Close the panel. */
        void onDismiss();
        /** Open the host's deeper detail / reports screen. */
        void onOpenReport();
    }

    // ── Dark-panel color tokens (mirror SplitPanelView) ──────────────
    private static final int BG           = 0xFF000000;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;
    private static final int TEXT_MUTED   = 0xA0F5F5F5;
    private static final int CHIP_FILL    = 0x14FFFFFF;
    private static final int RIPPLE_WASH  = 0x33FFFFFF;
    private static final int ACCENT_LIME  = 0xFF15803D;
    private static final int PILL_RADIUS_DP = 10;
    /** Panel-card corner radius. 16 dp matches {@link SplitPanelView} so the
     *  two split surfaces feel like the same rounded sheet rendered with
     *  different content. */
    private static final int PANEL_RADIUS_DP = 16;
    /** Translate-from offset for the slide-up entrance. */
    private static final int SLIDE_OFFSET_DP = 28;
    /** Space above the rounded card. Matches SplitPanelView so a flow that
     *  toggles from sheet → history (or vice versa) keeps the same gap. */
    private static final int TOP_GAP_DP = 12;
    /** Material's "emphasized" easing — shared across all the keyboard's
     *  rounded-card panels for a unified rise-into-place feel. */
    private static final Interpolator ENTER_EASING =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f);

    private TextView headline;
    private LinearLayout listColumn;
    private TextView empty;
    private Button clearButton;
    @Nullable private Listener listener;

    public SplitHistoryView(Context context) { super(context); init(); }
    public SplitHistoryView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setOrientation(VERTICAL);
        // Outer view is transparent so the keyboard chrome shows through
        // the TOP_GAP_DP space above the rounded card — matches the
        // restructure on SplitPanelView / HistoryPanelView / EmojiPanelView.

        // Rounded-card container — all real content (header, scroll list,
        // empty hint, actions) lives inside this. Card carries the
        // background, hairline stroke, outline + clipToOutline. Clip-to-
        // outline also keeps the scrollable row cards from poking past the
        // curved corners as the user scrolls them through.
        final int radius = dp(PANEL_RADIUS_DP);
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(BG);
        cardBg.setCornerRadius(radius);
        cardBg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(cardBg);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);

        card.addView(buildHeader(),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(getContext());
        scroll.setVerticalScrollBarEnabled(false);
        listColumn = new LinearLayout(getContext());
        listColumn.setOrientation(VERTICAL);
        scroll.addView(listColumn, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        // Cap height so the IME's panel slot doesn't push the keys offscreen.
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200));
        scrollLp.topMargin = dp(10);
        card.addView(scroll, scrollLp);

        empty = text(13, TEXT_MUTED, false);
        empty.setText("No splits yet — tap a payment app's chip and save one.");
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(GONE);
        LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        emptyLp.topMargin = dp(8);
        card.addView(empty, emptyLp);

        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(12);
        card.addView(buildActions(), actionsLp);

        // Mount the card on the outer view with a top gap.
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(TOP_GAP_DP);
        addView(card, cardLp);
    }

    private LinearLayout buildHeader() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        headline = text(15, TEXT_PRIMARY, /*bold*/ true);
        headline.setText("Saved splits");
        headline.setLetterSpacing(0.01f);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        row.addView(headline, hLp);

        TextView report = text(12, TEXT_MUTED, false);
        report.setText("Report ↗");
        report.setPadding(dp(8), dp(4), dp(8), dp(4));
        report.setClickable(true);
        report.setOnClickListener(v -> { if (listener != null) listener.onOpenReport(); });
        row.addView(report);

        return row;
    }

    private LinearLayout buildActions() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);

        clearButton = chipButton("Clear");
        clearButton.setOnClickListener(v -> { if (listener != null) listener.onClear(); });
        Button done = accentButton("Done");
        done.setOnClickListener(v -> { if (listener != null) listener.onDismiss(); });

        LinearLayout.LayoutParams left  = new LinearLayout.LayoutParams(0, dp(42), 1f);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(42), 1f);
        right.leftMargin = dp(8);
        row.addView(clearButton, left);
        row.addView(done, right);
        return row;
    }

    public void show(List<SplitHistory.Entry> entries, Listener listener) {
        this.listener = listener;
        listColumn.removeAllViews();

        if (entries == null || entries.isEmpty()) {
            empty.setVisibility(VISIBLE);
            clearButton.setEnabled(false);
            clearButton.setAlpha(0.5f);
            headline.setText("Saved splits");
            return;
        }

        empty.setVisibility(GONE);
        clearButton.setEnabled(true);
        clearButton.setAlpha(1f);
        headline.setText("Saved splits · " + entries.size());

        long now = System.currentTimeMillis();
        for (SplitHistory.Entry e : entries) {
            listColumn.addView(buildRow(e, now));
        }
    }

    /** Rounded card row — subtle-white fill + ripple foreground. Tap copies
     *  the entry to the host editor via the listener; press feedback comes
     *  from the ripple, clipped to the card's corner radius. */
    private LinearLayout buildRow(SplitHistory.Entry e, long now) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(VERTICAL);
        int pad = dp(12);
        row.setPadding(pad, pad, pad, pad);
        final int radius = dp(PILL_RADIUS_DP);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(CHIP_FILL);
        bg.setCornerRadius(radius);
        row.setBackground(bg);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        row.setForeground(new RippleDrawable(
                ColorStateList.valueOf(RIPPLE_WASH), null, mask));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> { if (listener != null) listener.onCopy(e); });

        TextView amount = text(16, TEXT_PRIMARY, true);
        amount.setText("₹" + formatAmount(e.amount));
        row.addView(amount);

        double per = e.people > 0 ? e.amount / e.people : e.amount;
        TextView meta = text(12, TEXT_MUTED, false);
        meta.setText(e.people + (e.people == 1 ? " person · ₹" : " people · ₹")
                + formatAmount(per) + " each · "
                + DateUtils.getRelativeTimeSpanString(
                        e.timestampMs, now, DateUtils.MINUTE_IN_MILLIS));
        row.addView(meta);

        return row;
    }

    // -- factories ------------------------------------------------------------

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private Button chipButton(String label) {
        return pillButton(label, CHIP_FILL, TEXT_PRIMARY);
    }

    private Button accentButton(String label) {
        return pillButton(label, ACCENT_LIME, Color.WHITE);
    }

    private Button pillButton(String label, int bgColor, int textColor) {
        Button b = new Button(getContext());
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        final int radius = dp(PILL_RADIUS_DP);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(bgColor);
        bg.setCornerRadius(radius);
        b.setBackground(bg);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        b.setForeground(new RippleDrawable(
                ColorStateList.valueOf(RIPPLE_WASH), null, mask));
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Smooth slide-up + fade-in whenever the parent mounts us. The host
        // controls our lifecycle by adding/removing the view, so this is the
        // most reliable place to trigger the entrance — show() itself runs
        // while the view may not yet have a parent. ViewPropertyAnimator is
        // GPU-accelerated for translate/alpha so this stays smooth with the
        // rounded-card outline clip.
        setAlpha(0f);
        setTranslationY(dp(SLIDE_OFFSET_DP));
        animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320)
                .setInterpolator(ENTER_EASING)
                .start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Cancel any in-flight entrance so the next attach starts from a
        // clean state. (Parent removes us instantly; no exit animation.)
        animate().cancel();
        setAlpha(1f);
        setTranslationY(0f);
    }

    private static String formatAmount(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
