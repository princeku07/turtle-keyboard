package com.prince.turtlekeyboard.integration.poll;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.prince.kbd.core.SheetContext;
import com.prince.kbd.core.SheetView;

/**
 * Poll sheet — listens to {@code polls/{id}} + its voters subcollection via
 * {@link RealtimePollClient#subscribePoll} and re-renders on every state change, so
 * vote counts update live across all connected viewers. Casts one vote per signed-in
 * Firebase user (dedup key is the uid, not an opaque device id like the Worker era).
 *
 * <p>Pre-migration (Worker era), this sheet polled HTTP on open + after each vote.
 * Realtime subscription replaces both — no manual refetch on success, and on vote
 * failure we redraw {@link #lastPoll} so the disabled "…" buttons revert.
 */
public class PollSheetView implements SheetView {

    private static final String TAG = "PollSheetView";

    // -- design tokens (mirror KeyboardTheme.turtleLight() — black canvas, green accent) --
    private static final int BG        = 0xFF000000;
    private static final int SURFACE   = 0xFF1E1E1E;
    private static final int SURFACE_2 = 0xFF141414;
    private static final int ACCENT    = 0xFF15803D;  // lime — primary
    private static final int TEXT      = 0xFFF5F5F5;  // off-white glyphs
    private static final int MUTED     = 0xFF888888;
    private static final int BORDER    = 0xFF2E2E2E;
    private static final int RED       = 0xFFB91C1C;

    /** Accent colors cycled across option rows. More than 6 options would wrap.
     *  All chosen to read clearly against the dark surface — saturated mid-tones
     *  rather than pastels. */
    private static final int[] OPTION_ACCENTS = {
            ACCENT,         // lime
            0xFFFF4FA3,     // pink
            0xFF5B6CFF,     // blue
            0xFFFF7A1A,     // orange
            0xFFA78BFA,     // lavender
            0xFFFBBF24,     // amber
    };

    private Context ctx;
    private SheetContext sheetCtx;
    private LinearLayout root;

    @Nullable private RealtimePollClient.Cancellable subscription;

    /** Last successfully rendered poll. Used to redraw on vote failure so the disabled
     *  "…" buttons revert without an extra round-trip — the listener doesn't fire when
     *  a write fails, so there's no live state to redraw from. */
    @Nullable private RealtimePollClient.Poll lastPoll;

    @Override
    public View buildView(SheetContext sheet) {
        this.sheetCtx = sheet;
        this.ctx = sheet.androidContext();

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(20), padTop = dp(8), padBottom = dp(28);
        root.setPadding(padH, padTop, padH, padBottom);

        renderLoading();
        subscribe();
        return root;
    }

    @Override
    public void onDismiss() {
        if (subscription != null) {
            subscription.cancel();
            subscription = null;
        }
    }

    // -- subscription --------------------------------------------------------

    private void subscribe() {
        // Sheet is launched by App Link from anywhere — no guaranteed sign-in context.
        // We don't try to launch sign-in from here (Activity lifecycle is wrong, and
        // the bridge needs MainActivity-grade hosting); just bounce the user toward
        // the host app.
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            renderError("Open Turtle and sign in to view this poll.");
            return;
        }
        final String id = sheetCtx.artifactId();
        subscription = RealtimePollClient.subscribePoll(id, new RealtimePollClient.PollListener() {
            @Override public void onPoll(RealtimePollClient.Poll poll) {
                lastPoll = poll;
                renderPoll(poll);
            }
            @Override public void onError(String reason) {
                if ("poll_not_found".equals(reason) || "poll_expired".equals(reason)) {
                    // Both render the same — from the user's perspective, an expired
                    // poll and a never-existed poll are indistinguishable. The TTL
                    // sweeper deletes expired polls, so most "not found" results are
                    // expired ones the sweeper got to first.
                    renderError("This poll has ended or doesn't exist.");
                } else if ("permission_denied".equals(reason)) {
                    renderError("Open Turtle and sign in to view this poll.");
                } else {
                    renderError("Couldn't load poll. Check your connection and try again.");
                }
            }
        });
    }

    private void submitVote(final int optionIndex) {
        final String id = sheetCtx.artifactId();
        markVoting();
        RealtimePollClient.vote(id, optionIndex, new RealtimePollClient.VoteCallback() {
            @Override public void onSuccess() {
                // Snapshot listener will fire with the new count — no manual refresh.
            }
            @Override public void onError(String code, String message) {
                String msg;
                switch (code) {
                    case "already_voted":
                        msg = "You've already voted on this poll.";
                        break;
                    case "not_signed_in":
                        msg = "Open Turtle and sign in to vote.";
                        break;
                    case "network":
                        msg = "Vote failed — check your connection.";
                        break;
                    default:
                        msg = "Vote failed. Try again.";
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                // Listener won't fire on a failed write — redraw from cached state so
                // the disabled "…" buttons aren't stuck.
                if (lastPoll != null) renderPoll(lastPoll);
            }
        });
    }

    // -- render --------------------------------------------------------------

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

    private void renderPoll(RealtimePollClient.Poll poll) {
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
        question.setTextColor(TEXT);
        question.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qLp.topMargin = dp(4);
        question.setLayoutParams(qLp);
        root.addView(question);

        int total = 0;
        for (RealtimePollClient.Option o : poll.options) total += o.votes;

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

    private View buildOptionRow(final int index, RealtimePollClient.Option option,
                                int totalVotes, int accent) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(card(SURFACE));
        int p = dp(14);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);

        TextView t = new TextView(ctx);
        t.setText(option.label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        int pct = totalVotes > 0 ? (option.votes * 100) / totalVotes : 0;
        TextView pctChip = new TextView(ctx);
        pctChip.setText(pct + "%");
        pctChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pctChip.setTextColor(TEXT);
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
        vote.setTextColor(TEXT);
        vote.setTypeface(Typeface.DEFAULT_BOLD);
        vote.setBackground(pill(ACCENT));
        vote.setPadding(dp(14), dp(6), dp(14), dp(6));
        vote.setGravity(Gravity.CENTER);
        vote.setOnClickListener(v -> submitVote(index));
        row.addView(vote);

        return row;
    }

    /** Card surface for option rows. Rounded rectangle, lifted dark fill,
     *  subtle 1px border — the keyboard's actual aesthetic. Dropped the
     *  brutalist offset shadow (INK on a now-black sheet was invisible). */
    private Drawable card(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), BORDER);
        d.setCornerRadius(dp(10));
        return d;
    }

    private Drawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(999));
        // No border — on a colored pill, a border just adds visual noise
        // against the dark surface behind it.
        return d;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }
}
