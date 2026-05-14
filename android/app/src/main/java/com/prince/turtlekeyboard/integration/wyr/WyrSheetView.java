package com.prince.turtlekeyboard.integration.wyr;

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

import com.prince.kbd.core.KeyValueStore;
import com.prince.kbd.core.SharedPrefsKeyValueStore;
import com.prince.kbd.core.SheetContext;
import com.prince.kbd.core.SheetView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Would-you-rather sheet — async turn-based couples game.
 *
 * <p>State machine (rendered in-place by swapping {@code root}'s children):
 * <ul>
 *   <li><b>LOADING</b> — fetching artifact from Worker.</li>
 *   <li><b>PLAYING</b> — one question at a time, user taps A or B to advance.</li>
 *   <li><b>SUBMITTING</b> — uploading the 5 answers after the last tap.</li>
 *   <li><b>WAITING</b> — submitted; partner hasn't played yet. Refresh to re-check.</li>
 *   <li><b>RESULTS</b> — both submitted; show match count + per-question side-by-side.</li>
 *   <li><b>ERROR</b> — load/submit failed; tap to retry.</li>
 * </ul>
 *
 * <p>Per-install device id (lazily generated into {@code prefs.scoped("device").id})
 * identifies the player. First unique device claims "Player 1", second "Player 2";
 * any third+ tap is treated as a spectator showing RESULTS-or-WAITING depending on
 * progress.
 */
public class WyrSheetView implements SheetView {

    private static final String TAG = "WyrSheetView";

    // design tokens
    private static final int INK   = 0xFF0C0C0C;
    private static final int LIME  = 0xFF15803D;
    private static final int PINK  = 0xFFFF4FA3;
    private static final int BLUE  = 0xFF5B6CFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MUTED = 0xFF6B6B6B;
    private static final int RED   = 0xFFB91C1C;
    private static final int CREAM = 0xFFF4EFE4;

    private Context ctx;
    private SheetContext sheetCtx;
    private LinearLayout root;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private String deviceId;
    private WyrClient.Wyr wyr;
    private final List<String> localAnswers = new ArrayList<>();
    private int currentQuestionIndex = 0;

    @Override
    public View buildView(SheetContext sheet) {
        this.sheetCtx = sheet;
        this.ctx = sheet.androidContext();
        this.deviceId = ensureDeviceId();

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(20), padTop = dp(8), padBottom = dp(28);
        root.setPadding(padH, padTop, padH, padBottom);

        renderLoading();
        fetch();
        return root;
    }

    @Override
    public void onDismiss() {
        io.shutdownNow();
    }

    // -- networking ---------------------------------------------------------

    private void fetch() {
        final String id = sheetCtx.artifactId();
        io.execute(() -> {
            try {
                WyrClient.Wyr fresh = WyrClient.read(id);
                main.post(() -> onState(fresh));
            } catch (Exception e) {
                Log.w(TAG, "fetch failed", e);
                main.post(() -> renderError("Couldn't load the game. Check your connection and tap to retry."));
            }
        });
    }

    private void submit() {
        if (wyr == null) return;
        final String id = wyr.id;
        final List<String> answers = new ArrayList<>(localAnswers);
        renderSubmitting();
        io.execute(() -> {
            try {
                WyrClient.Wyr fresh = WyrClient.submitAnswers(id, answers, deviceId);
                main.post(() -> onState(fresh));
            } catch (Exception e) {
                Log.w(TAG, "submit failed", e);
                final String msg = (e.getMessage() != null && e.getMessage().contains("already_voted"))
                        ? "You've already played this game."
                        : "Submit failed. Tap to retry.";
                main.post(() -> {
                    renderError(msg);
                    // Re-fetch in the background so we recover the true state next render.
                    fetch();
                });
            }
        });
    }

    /** Single decision-point for state machine routing after a Worker response. */
    private void onState(WyrClient.Wyr fresh) {
        this.wyr = fresh;
        boolean iAnswered = fresh.players.containsKey(deviceId);
        boolean partnerAnswered = false;
        for (String otherDevice : fresh.players.keySet()) {
            if (!otherDevice.equals(deviceId)) { partnerAnswered = true; break; }
        }

        if (iAnswered && partnerAnswered) {
            renderResults();
        } else if (iAnswered) {
            renderWaiting();
        } else {
            // Spectator (game already full with two others) or fresh player.
            if (fresh.players.size() >= 2) {
                renderSpectator();
            } else {
                currentQuestionIndex = 0;
                localAnswers.clear();
                renderPlaying();
            }
        }
    }

    /** Per-install opaque device id used by the Worker for player identity + dedup. */
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

    // -- renders ------------------------------------------------------------

    private void renderLoading() {
        clearAndPad();
        addCenteredText("Loading game…", MUTED);
    }

    private void renderSubmitting() {
        clearAndPad();
        addCenteredText("Submitting your picks…", MUTED);
    }

    private void renderWaiting() {
        clearAndPad();
        addEyebrow("WAITING");
        addBigText("Your partner hasn't played yet", INK);
        addBodyText("They'll see your matches when they finish.", MUTED);
        addPrimaryButton("Refresh", LIME, v -> fetch());
    }

    private void renderSpectator() {
        clearAndPad();
        addEyebrow("FULL GAME");
        addBigText("Two players already played this game", INK);
        addBodyText("You can see the results below.", MUTED);
        // Show results computed from the two players' answers.
        renderResultsContent();
    }

    private void renderError(String message) {
        clearAndPad();
        TextView t = new TextView(ctx);
        t.setText(message);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(RED);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = matchWidthWrap();
        lp.topMargin = dp(28);
        lp.bottomMargin = dp(16);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> fetch());
        root.addView(t);
    }

    private void renderPlaying() {
        clearAndPad();
        if (wyr == null || wyr.questions.isEmpty()) {
            renderError("No questions in this game. Tap to retry.");
            return;
        }
        if (currentQuestionIndex >= wyr.questions.size()) {
            // Last answer locked in — submit.
            submit();
            return;
        }

        // Progress dots.
        addProgressDots(currentQuestionIndex, wyr.questions.size());

        // Eyebrow.
        addEyebrow("WOULD YOU RATHER");

        WyrClient.Question q = wyr.questions.get(currentQuestionIndex);

        // Option A card.
        root.addView(buildOptionCard(q.a, LIME, () -> chooseAnswer("A")));
        // Spacer + OR
        addCenteredText("or", MUTED);
        // Option B card.
        root.addView(buildOptionCard(q.b, PINK, () -> chooseAnswer("B")));
    }

    private void chooseAnswer(String choice) {
        localAnswers.add(choice);
        currentQuestionIndex++;
        renderPlaying();
    }

    private void renderResults() {
        clearAndPad();
        renderResultsContent();
    }

    private void renderResultsContent() {
        if (wyr == null) return;
        // Find the two players' answers.
        List<String> mine = wyr.players.get(deviceId);
        List<String> partner = null;
        for (Map.Entry<String, List<String>> e : wyr.players.entrySet()) {
            if (!e.getKey().equals(deviceId)) { partner = e.getValue(); break; }
        }
        if (mine == null) {
            // Spectator viewing the game; just compare the two players we have.
            java.util.Iterator<List<String>> it = wyr.players.values().iterator();
            if (it.hasNext()) mine = it.next();
            if (it.hasNext()) partner = it.next();
        }
        if (mine == null || partner == null) {
            renderError("Couldn't compute results.");
            return;
        }

        int matches = 0;
        int total = Math.min(mine.size(), partner.size());
        for (int i = 0; i < total; i++) {
            if (mine.get(i).equals(partner.get(i))) matches++;
        }

        addEyebrow("MATCHES");
        addBigText(matches + " / " + total, LIME);
        addBodyText(matchCommentary(matches, total), MUTED);

        // Per-question breakdown.
        for (int i = 0; i < total; i++) {
            WyrClient.Question q = wyr.questions.get(i);
            String myPick = "A".equals(mine.get(i)) ? q.a : q.b;
            String theirPick = "A".equals(partner.get(i)) ? q.a : q.b;
            boolean isMatch = mine.get(i).equals(partner.get(i));
            root.addView(buildResultRow(i + 1, myPick, theirPick, isMatch));
        }
    }

    private String matchCommentary(int matches, int total) {
        if (total == 0) return "";
        float ratio = (float) matches / total;
        if (ratio == 1f) return "Identical taste. Suspicious. 🐢";
        if (ratio >= 0.8f) return "Strong vibes.";
        if (ratio >= 0.6f) return "Mostly on the same page.";
        if (ratio >= 0.4f) return "Healthy disagreement.";
        if (ratio >= 0.2f) return "Opposites attract?";
        return "You two are different people, and that's fine.";
    }

    // -- view helpers -------------------------------------------------------

    private void clearAndPad() {
        root.removeAllViews();
        addDragHandle();
    }

    private void addDragHandle() {
        View handle = new View(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MUTED);
        bg.setCornerRadius(dp(2));
        handle.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(4));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(8);
        handle.setLayoutParams(lp);
        root.addView(handle);
    }

    private void addEyebrow(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(MUTED);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.18f);
        LinearLayout.LayoutParams lp = wrapWrap();
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        root.addView(t);
    }

    private void addBigText(String text, int color) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = matchWidthWrap();
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        root.addView(t);
    }

    private void addBodyText(String text, int color) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(color);
        LinearLayout.LayoutParams lp = matchWidthWrap();
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(12);
        t.setLayoutParams(lp);
        root.addView(t);
    }

    private void addCenteredText(String text, int color) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = matchWidthWrap();
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        t.setLayoutParams(lp);
        root.addView(t);
    }

    private void addPrimaryButton(String text, int color, View.OnClickListener onClick) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(pill(color));
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = matchWidthWrap();
        lp.topMargin = dp(16);
        b.setLayoutParams(lp);
        root.addView(b);
    }

    private void addProgressDots(int currentIdx, int total) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = matchWidthWrap();
        rowLp.topMargin = dp(4);
        rowLp.bottomMargin = dp(12);
        row.setLayoutParams(rowLp);
        for (int i = 0; i < total; i++) {
            View dot = new View(ctx);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(i < currentIdx ? INK : (i == currentIdx ? LIME : 0xFFD4D4D4));
            dot.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(10), dp(10));
            lp.leftMargin = dp(4);
            lp.rightMargin = dp(4);
            dot.setLayoutParams(lp);
            row.addView(dot);
        }
        root.addView(row);
    }

    private View buildOptionCard(String text, int accent, Runnable onTap) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(brutalistCard(WHITE));
        int p = dp(24);
        card.setPadding(p, p, p, p);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> onTap.run());
        LinearLayout.LayoutParams lp = matchWidthWrap();
        lp.topMargin = dp(8);
        lp.rightMargin = dp(4);
        lp.bottomMargin = dp(4);
        card.setLayoutParams(lp);

        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTextColor(INK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = wrapWrap();
        t.setLayoutParams(tLp);
        card.addView(t);

        // Tiny accent strip at the bottom.
        View strip = new View(ctx);
        GradientDrawable stripBg = new GradientDrawable();
        stripBg.setColor(accent);
        stripBg.setCornerRadius(dp(2));
        strip.setBackground(stripBg);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(dp(40), dp(3));
        sLp.topMargin = dp(8);
        strip.setLayoutParams(sLp);
        card.addView(strip);
        return card;
    }

    private View buildResultRow(int n, String myPick, String theirPick, boolean isMatch) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(brutalistCard(WHITE));
        int p = dp(12);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = matchWidthWrap();
        lp.topMargin = dp(10);
        lp.rightMargin = dp(4);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);

        // Number badge.
        TextView num = new TextView(ctx);
        num.setText("#" + n);
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        num.setTextColor(MUTED);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams numLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        numLp.rightMargin = dp(8);
        num.setLayoutParams(numLp);
        row.addView(num);

        // Picks: "you · partner" or just "you = partner" if matched.
        TextView pick = new TextView(ctx);
        if (isMatch) {
            pick.setText("Both picked " + myPick);
            pick.setTextColor(LIME);
        } else {
            pick.setText("You: " + myPick + "  ·  Them: " + theirPick);
            pick.setTextColor(INK);
        }
        pick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pick.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pick.setLayoutParams(pLp);
        row.addView(pick);

        TextView icon = new TextView(ctx);
        icon.setText(isMatch ? "✓" : "·");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        icon.setTextColor(isMatch ? LIME : MUTED);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(icon);

        return row;
    }

    private LinearLayout.LayoutParams matchWidthWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
