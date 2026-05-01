package com.prince.turtlekeyboard.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.prince.split.SplitHistory;
import com.prince.turtlekeyboard.settings.Prefs;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Reports / detail view for splits. Launched from the in-keyboard panel via
 * {@code IntegrationContext.openScreen("split-detail")}. Shows aggregates the keyboard
 * doesn't have room for (lifetime total, this-month total) plus the full history with
 * per-row share + copy.
 */
public class SplitActivity extends Activity {

    private SplitHistory history;
    private Prefs prefs;
    private TextView totalLifetime;
    private TextView totalMonth;
    private TextView countLine;
    private LinearLayout listColumn;
    private TextView empty;
    private final DateFormat fullDate = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        history = new SplitHistory(prefs);
        setContentView(buildLayout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read on every entry so a save made from the keyboard (while this Activity is
        // backgrounded) reflects on return.
        render();
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF4EFE4);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView title = headline("Splits");
        root.addView(title);

        // Stats card
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.VERTICAL);
        stats.setBackgroundColor(0xFFFFFFFF);
        int p = dp(16);
        stats.setPadding(p, p, p, p);
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        statsLp.topMargin = dp(16);
        stats.setLayoutParams(statsLp);

        countLine = body("0 splits saved");
        totalLifetime = headline("");
        TextView lifetimeLabel = caption("lifetime");
        totalMonth = headline("");
        TextView monthLabel = caption("this month");

        stats.addView(countLine);
        addSpacer(stats, dp(12));
        stats.addView(totalLifetime);
        stats.addView(lifetimeLabel);
        addSpacer(stats, dp(12));
        stats.addView(totalMonth);
        stats.addView(monthLabel);
        root.addView(stats);

        // List
        TextView listHeader = body("History");
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(24);
        listHeader.setLayoutParams(hLp);
        listHeader.setTypeface(listHeader.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(listHeader);

        listColumn = new LinearLayout(this);
        listColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        llLp.topMargin = dp(8);
        listColumn.setLayoutParams(llLp);
        root.addView(listColumn);

        empty = caption("No splits yet — type /splits in any text field to add some.");
        empty.setVisibility(View.GONE);
        empty.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        eLp.topMargin = dp(24);
        empty.setLayoutParams(eLp);
        root.addView(empty);

        return scroll;
    }

    private void render() {
        List<SplitHistory.Entry> entries = history.all();
        countLine.setText(entries.size()
                + (entries.size() == 1 ? " split saved" : " splits saved"));

        double lifetime = 0;
        double month = 0;
        long monthStart = startOfThisMonth();
        for (SplitHistory.Entry e : entries) {
            lifetime += e.amount;
            if (e.timestampMs >= monthStart) month += e.amount;
        }
        totalLifetime.setText("₹" + formatAmount(lifetime));
        totalMonth.setText("₹" + formatAmount(month));

        listColumn.removeAllViews();
        if (entries.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            return;
        }
        empty.setVisibility(View.GONE);
        long now = System.currentTimeMillis();
        for (SplitHistory.Entry e : entries) {
            listColumn.addView(buildRow(e, now));
        }
    }

    private View buildRow(SplitHistory.Entry e, long now) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundColor(0xFFFFFFFF);
        int pad = dp(14);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView amount = headline("₹" + formatAmount(e.amount));
        amount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        row.addView(amount);

        double per = e.people > 0 ? e.amount / e.people : e.amount;
        TextView breakdown = body(e.people + (e.people == 1 ? " person · ₹" : " people · ₹")
                + formatAmount(per) + " each");
        breakdown.setTextColor(0xFF15803D);
        row.addView(breakdown);

        TextView when = caption(fullDate.format(new Date(e.timestampMs))
                + "  ·  " + DateUtils.getRelativeTimeSpanString(
                        e.timestampMs, now, DateUtils.MINUTE_IN_MILLIS));
        row.addView(when);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        aLp.topMargin = dp(8);
        actions.setLayoutParams(aLp);
        Button copy = pill("Copy");
        copy.setOnClickListener(v -> { copySummary(e); toast("Copied"); });
        Button share = pill("Share");
        share.setOnClickListener(v -> shareSummary(e));
        actions.addView(copy);
        addSpacer(actions, dp(8));
        actions.addView(share);
        row.addView(actions);

        return row;
    }

    private String summary(SplitHistory.Entry e) {
        double per = e.people > 0 ? e.amount / e.people : e.amount;
        return "Splitting ₹" + formatAmount(e.amount) + " between " + e.people
                + (e.people == 1 ? " person" : " people")
                + " — ₹" + formatAmount(per) + " each.";
    }

    private void copySummary(SplitHistory.Entry e) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Split", summary(e)));
    }

    private void shareSummary(SplitHistory.Entry e) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, summary(e));
        startActivity(Intent.createChooser(send, "Share split"));
    }

    // -- factories ------------------------------------------------------------

    private TextView headline(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        t.setTextColor(0xFF0C0C0C);
        return t;
    }
    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(0xFF333333);
        return t;
    }
    private TextView caption(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(0xFF888888);
        return t;
    }
    private Button pill(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setBackgroundColor(0xFF15803D);
        b.setTextColor(0xFFFFFFFF);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        return b;
    }
    private void addSpacer(LinearLayout container, int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, height));
        container.addView(v);
    }
    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // -- helpers --------------------------------------------------------------

    private static long startOfThisMonth() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
    private static String formatAmount(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
