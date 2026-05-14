package com.prince.turtlekeyboard.integration.poll;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.prince.kbd.core.KeyValueStore;
import com.prince.kbd.core.SharedPrefsKeyValueStore;
import com.prince.kbd.core.SheetContext;
import com.prince.kbd.core.SheetView;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Poll sheet — fetches state from {@code turtle-worker/poll/<id>}, renders question +
 * option rows with vote counts, lets the user cast one vote (server dedups by an
 * opaque per-install device id stored in {@code prefs.scoped("device").id}). On vote
 * success the sheet refetches so bumped counts are visible immediately.
 */
public class PollSheetView implements SheetView {

    private static final String TAG = "PollSheetView";

    // -- design tokens (mirror landing-page CSS variables / SplitActivity) --
    private static final int INK   = 0xFF0C0C0C;
    private static final int LIME  = 0xFF15803D;
    private static final int PINK  = 0xFFFF4FA3;
    private static final int BLUE  = 0xFF5B6CFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MUTED = 0xFF6B6B6B;
    private static final int RED   = 0xFFB91C1C;

    /** Accent colors cycled across option rows. More than 6 options would wrap. */
    private static final int[] OPTION_ACCENTS = {
            LIME, PINK, BLUE, 0xFFFF7A1A /* orange */, 0xFF8B5CF6 /* purple */, 0xFFEAB308 /* amber */
    };

    private Context ctx;
    private SheetContext sheetCtx;
    private LinearLayout root;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public View buildView(SheetContext sheet) {
        this.sheetCtx = sheet;
        this.ctx = sheet.androidContext();

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(20), padTop = dp(8), padBottom = dp(28);
        root.setPadding(padH, padTop, padH, padBottom);

        renderLoading();
        fetchPoll();
        return root;
    }

    @Override
    public void onDismiss() {
        io.shutdownNow();
    }

    // -- networking ---------------------------------------------------------

    private void fetchPoll() {
        final String id = sheetCtx.artifactId();
        io.execute(() -> {
            try {
                PollClient.Poll poll = PollClient.readPoll(id);
                main.post(() -> renderPoll(poll));
            } catch (Exception e) {
                Log.w(TAG, "fetchPoll failed", e);
                main.post(() -> renderError("Couldn't load poll. Check your connection and try again."));
            }
        });
    }

    private void submitVote(final int optionIndex) {
        final String id = sheetCtx.artifactId();
        final String deviceId = ensureDeviceId();
        markVoting();
        io.execute(() -> {
            try {
                PollClient.vote(id, optionIndex, deviceId);
                PollClient.Poll fresh = PollClient.readPoll(id);
                main.post(() -> renderPoll(fresh));
            } catch (Exception e) {
                Log.w(TAG, "submitVote failed", e);
                String message = (e.getMessage() != null && e.getMessage().contains("already_voted"))
                        ? "You've already voted on this poll."
                        : "Vote failed. Try again.";
                main.post(() -> {
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
                    // Re-fetch so the UI matches server state regardless of error cause.
                    fetchPoll();
                });
            }
        });
    }

    /** Per-install opaque id used by the Worker for vote dedup. Generated lazily; not
     *  PII, not shared with any other backend. */
    private String ensureDeviceId() {
        KeyValueStore store = new SharedPrefsKeyValueStore(
                ctx, SharedPrefsKeyValueStore.DEFAULT_FILE).scoped("device");
        String id = store.getString("id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            store.putString("id", id);
        }
        return id;
    }

    // -- render -------------------------------------------------------------

    private void renderLoading() {
        root.removeAllViews();
        root.addView(buildDragHandle());
        TextView t = new TextView(ctx);
        t.setText("Loading poll…");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(MUTED);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(28);
        lp.bottomMargin = dp(28);
        t.setLayoutParams(lp);
        root.addView(t);
    }

    private void renderError(String message) {
        root.removeAllViews();
        root.addView(buildDragHandle());
        TextView t = new TextView(ctx);
        t.setText(message);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(RED);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(28);
        lp.bottomMargin = dp(28);
        t.setLayoutParams(lp);
        root.addView(t);
    }

    /** Switch all visible Vote buttons to "…" + disabled. Called from main thread. */
    private void markVoting() {
        if (root == null) return;
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (!(v instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) v;
            for (int j = 0; j < row.getChildCount(); j++) {
                View c = row.getChildAt(j);
                if (c instanceof TextView && "Vote".contentEquals(((TextView) c).getText())) {
                    ((TextView) c).setText("…");
                    c.setEnabled(false);
                }
            }
        }
    }

    private void renderPoll(PollClient.Poll poll) {
        root.removeAllViews();
        root.addView(buildDragHandle());

        TextView label = new TextView(ctx);
        label.setText("POLL");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setTextColor(MUTED);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.18f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(8);
        label.setLayoutParams(labelLp);
        root.addView(label);

        TextView question = new TextView(ctx);
        question.setText(poll.question);
        question.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        question.setTextColor(INK);
        question.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qLp.topMargin = dp(4);
        question.setLayoutParams(qLp);
        root.addView(question);

        int total = 0;
        for (PollClient.Option o : poll.options) total += o.votes;

        TextView votes = new TextView(ctx);
        votes.setText(total + " vote" + (total == 1 ? "" : "s"));
        votes.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        votes.setTextColor(MUTED);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = dp(4);
        votes.setLayoutParams(vLp);
        root.addView(votes);

        for (int i = 0; i < poll.options.size(); i++) {
            int accent = OPTION_ACCENTS[i % OPTION_ACCENTS.length];
            root.addView(buildOptionRow(i, poll.options.get(i), total, accent));
        }

        TextView footer = new TextView(ctx);
        footer.setText("Poll #" + poll.id + "  ·  Turtle 🐢");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        footer.setTextColor(MUTED);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fLp.topMargin = dp(20);
        footer.setLayoutParams(fLp);
        root.addView(footer);
    }

    private View buildDragHandle() {
        View handle = new View(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MUTED);
        bg.setCornerRadius(dp(2));
        handle.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(4));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(8);
        handle.setLayoutParams(lp);
        return handle;
    }

    private View buildOptionRow(final int index, PollClient.Option option, int totalVotes, int accent) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(brutalistCard(WHITE));
        int p = dp(14);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.rightMargin = dp(4);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);

        TextView t = new TextView(ctx);
        t.setText(option.label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(INK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        int pct = totalVotes > 0 ? (option.votes * 100) / totalVotes : 0;
        TextView pctChip = new TextView(ctx);
        pctChip.setText(pct + "%");
        pctChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pctChip.setTextColor(WHITE);
        pctChip.setTypeface(Typeface.DEFAULT_BOLD);
        pctChip.setBackground(pill(accent));
        pctChip.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.rightMargin = dp(10);
        pctChip.setLayoutParams(chipLp);
        row.addView(pctChip);

        TextView vote = new TextView(ctx);
        vote.setText("Vote");
        vote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        vote.setTextColor(WHITE);
        vote.setTypeface(Typeface.DEFAULT_BOLD);
        vote.setBackground(pill(INK));
        vote.setPadding(dp(14), dp(6), dp(14), dp(6));
        vote.setGravity(Gravity.CENTER);
        vote.setOnClickListener(v -> submitVote(index));
        row.addView(vote);

        return row;
    }

    private Drawable brutalistCard(int fill) {
        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(INK);
        shadow.setCornerRadius(dp(2));
        GradientDrawable card = new GradientDrawable();
        card.setColor(fill);
        card.setStroke(dp(2), INK);
        card.setCornerRadius(dp(2));
        LayerDrawable l = new LayerDrawable(new Drawable[]{shadow, card});
        l.setLayerInset(0, dp(4), dp(4), 0, 0);
        l.setLayerInset(1, 0, 0, dp(4), dp(4));
        return l;
    }

    private Drawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(999));
        d.setStroke(dp(1), INK);
        return d;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }
}
