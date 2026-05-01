package com.prince.split.view;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.split.SplitContract;

import java.util.Locale;

/**
 * In-keyboard split sheet. Shown above the keys when the user taps the integration chip
 * in a payment app — lets them pick a head-count and save the split without leaving the
 * host. No activity launch.
 */
public class SplitPanelView extends LinearLayout {

    public interface Listener {
        void onSave(double amount, int people);
        void onCancel();
    }

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
        setBackgroundColor(0xFF0D3F12);
        int pad = dp(12);
        setPadding(pad, pad, pad, pad);

        headline = text(16, 0xFFFFFFFF);
        headline.setGravity(Gravity.CENTER);
        addView(headline, lp(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        addView(buildStepper(), lp(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, dp(8)));

        perPersonText = text(14, 0xFFCCE9C7);
        perPersonText.setGravity(Gravity.CENTER);
        addView(perPersonText, lp(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, dp(8)));

        addView(buildActions(), lp(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, dp(10)));
    }

    private LinearLayout buildStepper() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button minus = chipButton("−");
        minus.setOnClickListener(v -> {
            if (people > SplitContract.MIN_PEOPLE) { people--; refresh(); }
        });
        countText = text(18, 0xFFFFFFFF);
        countText.setGravity(Gravity.CENTER);
        countText.setMinWidth(dp(120));
        Button plus = chipButton("+");
        plus.setOnClickListener(v -> {
            if (people < SplitContract.MAX_PEOPLE) { people++; refresh(); }
        });

        row.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(40)));
        row.addView(countText, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        row.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(40)));
        return row;
    }

    private LinearLayout buildActions() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);

        Button cancel = chipButton("Cancel");
        cancel.setOnClickListener(v -> { if (listener != null) listener.onCancel(); });
        Button save = chipButton("Save");
        save.setBackgroundColor(0xFF1F6F2A);
        save.setOnClickListener(v -> {
            if (listener != null) listener.onSave(amount, people);
        });

        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0, dp(40), 1f);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0, dp(40), 1f);
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
        setVisibility(VISIBLE);
    }

    public void hide() {
        listener = null;
        setVisibility(GONE);
    }

    private void refresh() {
        headline.setText("Split ₹" + formatAmount(amount));
        countText.setText(people + (people == 1 ? " person" : " people"));
        double per = people > 0 ? amount / people : amount;
        perPersonText.setText("₹" + formatAmount(per) + " each");
    }

    // -- factories ------------------------------------------------------------

    private TextView text(int sp, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button chipButton(String label) {
        Button b = new Button(getContext());
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xFF15803D);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = topMargin;
        return p;
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
