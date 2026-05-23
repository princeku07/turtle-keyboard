package com.prince.split.view;

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
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.split.SplitContract;

import java.util.Locale;

/**
 * In-keyboard split sheet shown above the keys. Lets the user pick a head-count
 * and save the split without leaving the host app.
 */
public class SplitPanelView extends LinearLayout {

    public interface Listener {
        void onSave(double amount, int people);
        void onCancel();
    }

    private static final int BG           = 0xFF000000;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;
    private static final int TEXT_MUTED   = 0xA0F5F5F5;
    private static final int CHIP_FILL    = 0x14FFFFFF;
    private static final int RIPPLE_WASH  = 0x33FFFFFF;
    private static final int ACCENT_LIME  = 0xFF15803D;
    private static final int PILL_RADIUS_DP = 10;
    private static final int PANEL_RADIUS_DP = 16;
    private static final int SLIDE_OFFSET_DP = 28;
    private static final int TOP_GAP_DP = 12;
    /** Material's "emphasized" easing — shared with the other rounded-card panels. */
    private static final Interpolator ENTER_EASING =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f);

    private TextView headline;
    private TextView countText;
    private TextView perPersonText;

    private double amount;
    private int people = SplitContract.DEFAULT_PEOPLE;
    @Nullable private Listener listener;

    public SplitPanelView(Context context) { super(context); init(); }
    public SplitPanelView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setOrientation(VERTICAL);
        setVisibility(GONE);
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

        headline = text(15, TEXT_PRIMARY, /*bold*/ true);
        headline.setGravity(Gravity.CENTER);
        headline.setLetterSpacing(0.01f);
        card.addView(headline,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams stepperLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        stepperLp.topMargin = dp(10);
        card.addView(buildStepper(), stepperLp);

        perPersonText = text(13, TEXT_MUTED, false);
        perPersonText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams perLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        perLp.topMargin = dp(8);
        card.addView(perPersonText, perLp);

        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(12);
        card.addView(buildActions(), actionsLp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(TOP_GAP_DP);
        addView(card, cardLp);
    }

    private LinearLayout buildStepper() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button minus = chipButton("−");
        minus.setOnClickListener(v -> {
            if (people > SplitContract.MIN_PEOPLE) { people--; refresh(); }
        });
        countText = text(18, TEXT_PRIMARY, true);
        countText.setGravity(Gravity.CENTER);
        countText.setMinWidth(dp(120));
        Button plus = chipButton("+");
        plus.setOnClickListener(v -> {
            if (people < SplitContract.MAX_PEOPLE) { people++; refresh(); }
        });

        row.addView(minus, new LinearLayout.LayoutParams(dp(44), dp(40)));
        row.addView(countText, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        row.addView(plus, new LinearLayout.LayoutParams(dp(44), dp(40)));
        return row;
    }

    private LinearLayout buildActions() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);

        Button cancel = chipButton("Cancel");
        cancel.setOnClickListener(v -> { if (listener != null) listener.onCancel(); });
        Button save = accentButton("Save");
        save.setOnClickListener(v -> {
            if (listener != null) listener.onSave(amount, people);
        });

        LinearLayout.LayoutParams w  = new LinearLayout.LayoutParams(0, dp(42), 1f);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        w2.leftMargin = dp(8);
        row.addView(cancel, w);
        row.addView(save, w2);
        return row;
    }

    public void show(String rawAmount, int defaultPeople, Listener listener) {
        this.amount = parseAmount(rawAmount);
        this.people = clamp(defaultPeople);
        this.listener = listener;
        refresh();
        animateIn();
    }

    public void hide() {
        animateOut(() -> listener = null);
    }

    private void animateIn() {
        animate().cancel();
        setVisibility(VISIBLE);
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
        if (getVisibility() != VISIBLE) {
            if (onEnd != null) onEnd.run();
            return;
        }
        animate()
                .alpha(0f)
                .translationY(dp(SLIDE_OFFSET_DP))
                .setDuration(220)
                .setInterpolator(new AccelerateInterpolator(1.2f))
                .withEndAction(() -> {
                    setVisibility(GONE);
                    setAlpha(1f);
                    setTranslationY(0f);
                    if (onEnd != null) onEnd.run();
                })
                .start();
    }

    private void refresh() {
        headline.setText("Split ₹" + formatAmount(amount));
        countText.setText(people + (people == 1 ? " person" : " people"));
        double per = people > 0 ? amount / people : amount;
        perPersonText.setText("₹" + formatAmount(per) + " each");
    }

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

    private static int clamp(int v) {
        if (v < SplitContract.MIN_PEOPLE) return SplitContract.MIN_PEOPLE;
        if (v > SplitContract.MAX_PEOPLE) return SplitContract.MAX_PEOPLE;
        return v;
    }

    private static double parseAmount(String s) {
        if (s == null || s.isEmpty()) return 0d;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0d; }
    }

    private static String formatAmount(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
