package com.prince.split.view;

import android.content.Context;
import android.graphics.Color;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.split.SplitHistory;

import java.util.List;
import java.util.Locale;

/**
 * In-keyboard saved-splits list. Mirrors the activity flow but stays inside the panel slot
 * so the user never leaves the host app. Each row is tap-to-copy; footer offers Clear/Done.
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

    private TextView headline;
    private LinearLayout listColumn;
    private TextView empty;
    private Button clearButton;
    @Nullable private Listener listener;

    public SplitHistoryView(Context context) { super(context); init(); }
    public SplitHistoryView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF0D3F12);
        int pad = dp(12);
        setPadding(pad, pad, pad, pad);

        addView(buildHeader(), lp(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 0));

        ScrollView scroll = new ScrollView(getContext());
        listColumn = new LinearLayout(getContext());
        listColumn.setOrientation(VERTICAL);
        scroll.addView(listColumn, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        // Cap height so the IME's panel slot doesn't push the keys offscreen.
        addView(scroll, lp(LayoutParams.MATCH_PARENT, dp(200), dp(8)));

        empty = text(13, 0xFFCCE9C7, false);
        empty.setText("No splits yet — tap a payment app's chip and save one.");
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(GONE);
        addView(empty, lp(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, dp(8)));

        addView(buildActions(), lp(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, dp(10)));
    }

    private LinearLayout buildHeader() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        headline = text(16, 0xFFFFFFFF, /*bold*/ true);
        headline.setText("Saved splits");
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        row.addView(headline, hLp);

        TextView report = text(13, 0xFFB7E0BD, false);
        report.setText("Report ↗");
        report.setPadding(dp(6), dp(4), dp(6), dp(4));
        report.setClickable(true);
        report.setOnClickListener(v -> { if (listener != null) listener.onOpenReport(); });
        row.addView(report);

        return row;
    }

    private LinearLayout buildActions() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);

        clearButton = pill("Clear");
        clearButton.setOnClickListener(v -> { if (listener != null) listener.onClear(); });
        Button done = pill("Done");
        done.setBackgroundColor(0xFF1F6F2A);
        done.setOnClickListener(v -> { if (listener != null) listener.onDismiss(); });

        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(40), 1f);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(40), 1f);
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

    private LinearLayout buildRow(SplitHistory.Entry e, long now) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(VERTICAL);
        int pad = dp(10);
        row.setPadding(pad, pad, pad, pad);
        row.setBackgroundColor(0xFF15803D);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(v -> { if (listener != null) listener.onCopy(e); });

        TextView amount = text(16, 0xFFFFFFFF, true);
        amount.setText("₹" + formatAmount(e.amount));
        row.addView(amount);

        double per = e.people > 0 ? e.amount / e.people : e.amount;
        TextView meta = text(12, 0xFFCCE9C7, false);
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
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private Button pill(String label) {
        Button b = new Button(getContext());
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xFF0C0C0C);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        return b;
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

    private static String formatAmount(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
