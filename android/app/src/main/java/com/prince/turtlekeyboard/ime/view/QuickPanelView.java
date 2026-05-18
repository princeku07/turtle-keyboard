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
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.command.CommandRegistry;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

import java.util.List;

/**
 * Quick Panel — replaces the keyboard's key area with a 2-column grid of
 * slash commands (PRD §6.6). Opened by double-tap-space.
 *
 * <p>A tap on a tile does <b>not</b> dispatch the command and does
 * <b>not</b> write {@code "/<name>"} to the host editor. The panel hands
 * the command name back to the IME, which routes through the same
 * {@link com.prince.turtlekeyboard.command.CommandComposer} the typed-slash
 * flow uses — so picking from the grid is indistinguishable from typing
 * the command. The host app sees nothing until the final result is
 * committed.
 *
 * <p>Visual language matches the keyboard's other rounded-card panels
 * (history, emoji, split): pure-black surface inside a 16 dp-radius card
 * with a 1 dp hairline stroke, sitting under a 12 dp top gap so the chrome
 * behind shows through. Tiles are subtle-white chips with a ripple
 * foreground, plus the brand-lime accent on the dismiss CTA. The whole
 * card slides up + fades in with the same Material-emphasized easing the
 * other panels use, so the keyboard's "panel rising into view" vocabulary
 * stays consistent across surfaces.
 */
public class QuickPanelView extends LinearLayout {

    public interface OnPickListener { void onPick(CommandRegistry.Entry entry); }
    public interface OnDismissListener { void onDismiss(); }

    private static final int COLUMNS = 2;

    // ── Dark-panel color tokens (mirror HistoryPanelView etc.) ──────────
    private static final int BG           = 0xFF000000;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;
    private static final int TEXT_MUTED   = 0xA0F5F5F5;
    private static final int CHIP_FILL    = 0x14FFFFFF;
    private static final int RIPPLE_WASH  = 0x33FFFFFF;
    private static final int DIVIDER      = 0x22FFFFFF;
    /** Brand lime — same value as colors.xml#lime. */
    private static final int ACCENT_LIME  = 0xFF15803D;

    private static final int PANEL_RADIUS_DP = 16;
    private static final int TILE_RADIUS_DP  = 12;
    private static final int TOP_GAP_DP      = 12;
    private static final int SLIDE_OFFSET_DP = 28;
    private static final Interpolator ENTER_EASING =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f);
    private static final Interpolator EXIT_EASING =
            new AccelerateInterpolator(1.2f);

    private final LinearLayout cardContainer;
    private final ScrollView scroller;
    private final GridLayout grid;
    private final View divider;
    private final TextView dismissBar;

    @Nullable private OnDismissListener dismissListener;

    public QuickPanelView(Context context) {
        this(context, null);
    }

    public QuickPanelView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        cardContainer = new LinearLayout(context);
        scroller = new ScrollView(context);
        grid = new GridLayout(context);
        divider = new View(context);
        dismissBar = new TextView(context);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        // Outer view stays transparent so the keyboard chrome shows through
        // the TOP_GAP_DP gap above the rounded card.

        // ── Rounded-card container ───────────────────────────────────────
        cardContainer.setOrientation(VERTICAL);
        final int cardRadius = dp(PANEL_RADIUS_DP);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(BG);
        cardBg.setCornerRadius(cardRadius);
        cardBg.setStroke(dp(1), 0x33FFFFFF);
        cardContainer.setBackground(cardBg);
        cardContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cardRadius);
            }
        });
        cardContainer.setClipToOutline(true);

        // ── Grid scroller (top of card) ──────────────────────────────────
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        grid.setColumnCount(COLUMNS);
        grid.setUseDefaultMargins(false);
        int gridPad = dp(10);
        grid.setPadding(gridPad, gridPad, gridPad, gridPad);
        scroller.addView(grid, new ScrollView.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        cardContainer.addView(scroller,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── Hairline divider above the dismiss bar ───────────────────────
        divider.setBackgroundColor(DIVIDER);
        cardContainer.addView(divider,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        Math.max(1, dp(1) / 2)));

        // ── Dismiss bar (full-width, single tap target back to keys) ─────
        dismissBar.setText("↓  Keyboard");
        dismissBar.setTextColor(TEXT_PRIMARY);
        dismissBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        dismissBar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dismissBar.setLetterSpacing(0.04f);
        dismissBar.setGravity(Gravity.CENTER);
        dismissBar.setIncludeFontPadding(false);
        dismissBar.setClickable(true);
        dismissBar.setFocusable(true);
        int barPad = dp(12);
        dismissBar.setPadding(barPad, barPad, barPad, barPad);
        // Transparent fill, ripple foreground for press feedback. No background
        // shape — the dismiss bar reads as the card's bottom edge rather than
        // a separate button.
        dismissBar.setForeground(new RippleDrawable(
                ColorStateList.valueOf(RIPPLE_WASH), null, solidWhiteMask(0)));
        dismissBar.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            animateOut(() -> { if (dismissListener != null) dismissListener.onDismiss(); });
        });
        cardContainer.addView(dismissBar,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Mount the card with a top gap ────────────────────────────────
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cardLp.topMargin = dp(TOP_GAP_DP);
        addView(cardContainer, cardLp);
    }

    public void show(List<CommandRegistry.Entry> entries,
                     OnPickListener pickListener,
                     OnDismissListener dismissListener) {
        this.dismissListener = dismissListener;
        grid.removeAllViews();
        for (CommandRegistry.Entry e : entries) {
            grid.addView(makeTile(e, pickListener));
        }
        animateIn();
    }

    private View makeTile(CommandRegistry.Entry entry, OnPickListener listener) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(VERTICAL);
        tile.setGravity(Gravity.CENTER);
        int padV = dp(14), padH = dp(10);
        tile.setPadding(padH, padV, padH, padV);
        final int tileRadius = dp(TILE_RADIUS_DP);
        tile.setBackground(tileBg(tileRadius));
        tile.setForeground(new RippleDrawable(
                ColorStateList.valueOf(RIPPLE_WASH), null, solidWhiteMask(tileRadius)));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            animateOut(() -> listener.onPick(entry));
        });

        TextView emoji = new TextView(getContext());
        emoji.setText(entry.emoji == null ? "•" : entry.emoji);
        emoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        emoji.setIncludeFontPadding(false);
        emoji.setGravity(Gravity.CENTER);
        tile.addView(emoji);

        TextView slash = new TextView(getContext());
        slash.setText("/" + entry.name);
        slash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        slash.setTextColor(TEXT_PRIMARY);
        slash.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        slash.setIncludeFontPadding(false);
        slash.setGravity(Gravity.CENTER);
        slash.setLetterSpacing(0.01f);
        LinearLayout.LayoutParams slashLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slashLp.topMargin = dp(6);
        tile.addView(slash, slashLp);

        TextView sub = new TextView(getContext());
        sub.setText(entry.label);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        sub.setTextColor(TEXT_MUTED);
        sub.setIncludeFontPadding(false);
        sub.setGravity(Gravity.CENTER);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        tile.addView(sub, subLp);

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        int margin = dp(5);
        lp.setMargins(margin, margin, margin, margin);
        tile.setLayoutParams(lp);
        return tile;
    }

    private GradientDrawable tileBg(int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(radius);
        g.setColor(CHIP_FILL);
        return g;
    }

    /** Solid-white {@link GradientDrawable} sized as a rounded-corner mask
     *  for {@link RippleDrawable}. Radius 0 means a square clip (used by the
     *  dismiss bar, which has no rounded corners of its own — its ripple
     *  spans the rectangular bar area). */
    private GradientDrawable solidWhiteMask(int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(Color.WHITE);
        if (radius > 0) g.setCornerRadius(radius);
        return g;
    }

    /** Kept for API compatibility with the IME's mount path; the panel
     *  intentionally pins itself to the dark palette declared above so the
     *  keyboard's light/dark theme can't repaint it underneath. */
    @SuppressWarnings("unused")
    public void applyTheme(KeyboardTheme theme) { }

    /** Slide-up + fade-in. Material-emphasized easing matches every other
     *  rounded-card panel in the keyboard. */
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

    /** Mirror: fade + slide down, then fire {@code onEnd} so the host can
     *  tear down or dispatch the picked command as a follow-up to the
     *  visual exit. Reset transform so a re-attach starts clean. */
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
