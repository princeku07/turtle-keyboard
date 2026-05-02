package com.prince.split.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.prince.split.SharedPreferencesSplitStore;
import com.prince.split.SplitAuth;
import com.prince.split.SplitCloudSync;
import com.prince.split.SplitContract;
import com.prince.split.SplitHistory;
import com.prince.split.SplitKeys;
import com.prince.split.SplitStore;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Reports / detail view for splits. Visual style follows the repo's neo-brutalist palette
 * (cream background, ink borders, lime + pink accents, offset shadow cards) so the SDK's
 * surface stays cohesive with the landing page and the keyboard panel.
 */
public class SplitActivity extends AppCompatActivity {

    // -- design tokens (mirror landing-page CSS variables) -------------------
    private static final int CREAM = 0xFFF4EFE4;
    private static final int INK   = 0xFF0C0C0C;
    private static final int LIME  = 0xFF15803D;
    private static final int PINK  = 0xFFFF4FA3;
    private static final int BLUE  = 0xFF5B6CFF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MUTED = 0xFF6B6B6B;

    private SplitStore store;
    private SplitHistory history;
    private SplitAuth auth;

    private TextView totalLifetime;
    private TextView totalMonth;
    private TextView countLine;
    private LinearLayout listColumn;
    private View emptyCard;
    private TextView profileEmail;
    private TextView profileAvatar;
    private TextView sheetLinkBtn;
    private TextView inviteBtn;
    private TextView clearMineBtn;
    private TextView clearAllBtn;
    private TextView roleBadge;

    private final DateFormat fullDate = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SharedPreferencesSplitStore(this, SplitContract.STORAGE_FILE);
        history = new SplitHistory(store);
        auth = new SplitAuth(this, store);
        setContentView(buildLayout());
        setTitle("Splits");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Heal email/owner stamping for installs that signed in pre-invite-feature.
        auth.fetchAndStoreEmailIfMissing();
        render();
        SplitCloudSync.fetchAndMerge(this, store, changed -> { if (changed) render(); });
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(CREAM);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Page heading
        TextView heading = new TextView(this);
        heading.setText("Splits");
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38);
        heading.setTextColor(INK);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(heading);

        TextView sub = new TextView(this);
        sub.setText("Saved on this device · synced to your Drive");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(MUTED);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        sub.setLayoutParams(subLp);
        root.addView(sub);

        root.addView(buildProfileCard());
        root.addView(buildStatsRow());

        // History header
        TextView listHeader = new TextView(this);
        listHeader.setText("HISTORY");
        listHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        listHeader.setTextColor(INK);
        listHeader.setTypeface(Typeface.DEFAULT_BOLD);
        listHeader.setLetterSpacing(0.18f);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(28);
        hLp.bottomMargin = dp(10);
        listHeader.setLayoutParams(hLp);
        root.addView(listHeader);

        listColumn = new LinearLayout(this);
        listColumn.setOrientation(LinearLayout.VERTICAL);
        listColumn.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        root.addView(listColumn);

        emptyCard = buildEmptyCard();
        emptyCard.setVisibility(View.GONE);
        root.addView(emptyCard);

        return scroll;
    }

    // -- profile card --------------------------------------------------------

    private View buildProfileCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(brutalistCard(WHITE));
        int p = dp(16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        lp.rightMargin = dp(4); // leave room for offset shadow
        lp.bottomMargin = dp(4);
        card.setLayoutParams(lp);

        // Top row: avatar + email + sign-out
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        profileAvatar = new TextView(this);
        profileAvatar.setBackground(circleDrawable(LIME));
        profileAvatar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        profileAvatar.setTextColor(WHITE);
        profileAvatar.setTypeface(Typeface.DEFAULT_BOLD);
        profileAvatar.setGravity(Gravity.CENTER);
        profileAvatar.setText("·");
        int sz = dp(44);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(sz, sz);
        profileAvatar.setLayoutParams(aLp);
        topRow.addView(profileAvatar);

        addHSpacer(topRow, dp(12));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));

        TextView label = new TextView(this);
        label.setText("SIGNED IN AS");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        label.setTextColor(MUTED);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.15f);
        col.addView(label);

        profileEmail = new TextView(this);
        profileEmail.setText("");
        profileEmail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        profileEmail.setTextColor(INK);
        profileEmail.setTypeface(Typeface.DEFAULT_BOLD);
        profileEmail.setSingleLine(true);
        profileEmail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(profileEmail);

        topRow.addView(col);

        TextView signOut = new TextView(this);
        signOut.setText("Sign out");
        signOut.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        signOut.setTextColor(INK);
        signOut.setTypeface(Typeface.DEFAULT_BOLD);
        signOut.setPaintFlags(signOut.getPaintFlags()
                | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        signOut.setOnClickListener(v -> confirmSignOut());
        signOut.setPadding(dp(8), dp(4), dp(4), dp(4));
        topRow.addView(signOut);

        card.addView(topRow);

        // Role badge ("Owner" or "Member")
        roleBadge = new TextView(this);
        roleBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        roleBadge.setTypeface(Typeface.DEFAULT_BOLD);
        roleBadge.setLetterSpacing(0.18f);
        roleBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rbLp.topMargin = dp(12);
        roleBadge.setLayoutParams(rbLp);
        card.addView(roleBadge);

        // Sheet link row (below)
        sheetLinkBtn = new TextView(this);
        sheetLinkBtn.setText("📊  Open in Google Sheets  →");
        sheetLinkBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sheetLinkBtn.setTextColor(INK);
        sheetLinkBtn.setTypeface(Typeface.DEFAULT_BOLD);
        sheetLinkBtn.setBackground(pillDrawable(0xFFEAF7EE)); // soft lime tint
        sheetLinkBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        sheetLinkBtn.setGravity(Gravity.CENTER);
        sheetLinkBtn.setOnClickListener(v -> openSheet());
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        sbLp.topMargin = dp(10);
        sheetLinkBtn.setLayoutParams(sbLp);
        card.addView(sheetLinkBtn);

        // Owner action: open / close anyone-with-link membership.
        inviteBtn = new TextView(this);
        inviteBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        inviteBtn.setTypeface(Typeface.DEFAULT_BOLD);
        inviteBtn.setPadding(dp(14), dp(10), dp(14), dp(10));
        inviteBtn.setGravity(Gravity.CENTER);
        inviteBtn.setOnClickListener(v -> onInviteTapped());
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        ibLp.topMargin = dp(8);
        inviteBtn.setLayoutParams(ibLp);
        card.addView(inviteBtn);

        // Clear-row pair (clear mine, clear all)
        LinearLayout clearRow = new LinearLayout(this);
        clearRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        crLp.topMargin = dp(8);
        clearRow.setLayoutParams(crLp);

        clearMineBtn = new TextView(this);
        clearMineBtn.setText("Clear my rows");
        clearMineBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clearMineBtn.setTypeface(Typeface.DEFAULT_BOLD);
        clearMineBtn.setTextColor(INK);
        clearMineBtn.setBackground(pillDrawable(WHITE));
        clearMineBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        clearMineBtn.setGravity(Gravity.CENTER);
        clearMineBtn.setOnClickListener(v -> confirmClearMine());
        LinearLayout.LayoutParams cmLp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        clearMineBtn.setLayoutParams(cmLp);
        clearRow.addView(clearMineBtn);

        addHSpacer(clearRow, dp(6));

        clearAllBtn = new TextView(this);
        clearAllBtn.setText("Clear all (owner)");
        clearAllBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clearAllBtn.setTypeface(Typeface.DEFAULT_BOLD);
        clearAllBtn.setTextColor(WHITE);
        clearAllBtn.setBackground(pillDrawable(0xFFB91C1C));
        clearAllBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        clearAllBtn.setGravity(Gravity.CENTER);
        clearAllBtn.setOnClickListener(v -> confirmClearAll());
        LinearLayout.LayoutParams caLp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f);
        clearAllBtn.setLayoutParams(caLp);
        clearRow.addView(clearAllBtn);

        card.addView(clearRow);

        return card;
    }

    // -- stats row -----------------------------------------------------------

    private View buildStatsRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        row.setLayoutParams(lp);

        // This month — pink accent
        LinearLayout monthCard = statCard(PINK, WHITE);
        TextView monthLabel = new TextView(this);
        monthLabel.setText("THIS MONTH");
        monthLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        monthLabel.setTextColor(WHITE);
        monthLabel.setTypeface(Typeface.DEFAULT_BOLD);
        monthLabel.setLetterSpacing(0.18f);
        monthCard.addView(monthLabel);
        totalMonth = new TextView(this);
        totalMonth.setText("₹0");
        totalMonth.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        totalMonth.setTextColor(WHITE);
        totalMonth.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tmLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tmLp.topMargin = dp(6);
        totalMonth.setLayoutParams(tmLp);
        monthCard.addView(totalMonth);
        countLine = new TextView(this);
        countLine.setText("0 splits");
        countLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        countLine.setTextColor(WHITE);
        countLine.setAlpha(0.85f);
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        clLp.topMargin = dp(2);
        countLine.setLayoutParams(clLp);
        monthCard.addView(countLine);
        row.addView(wrapWithRightShadow(monthCard, 1f));

        addHSpacer(row, dp(10));

        // Lifetime — white card
        LinearLayout lifeCard = statCard(WHITE, INK);
        TextView lifeLabel = new TextView(this);
        lifeLabel.setText("LIFETIME");
        lifeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        lifeLabel.setTextColor(MUTED);
        lifeLabel.setTypeface(Typeface.DEFAULT_BOLD);
        lifeLabel.setLetterSpacing(0.18f);
        lifeCard.addView(lifeLabel);
        totalLifetime = new TextView(this);
        totalLifetime.setText("₹0");
        totalLifetime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        totalLifetime.setTextColor(INK);
        totalLifetime.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tlLp.topMargin = dp(6);
        totalLifetime.setLayoutParams(tlLp);
        lifeCard.addView(totalLifetime);
        TextView lifeFoot = new TextView(this);
        lifeFoot.setText("total saved");
        lifeFoot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lifeFoot.setTextColor(MUTED);
        LinearLayout.LayoutParams lfLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lfLp.topMargin = dp(2);
        lifeFoot.setLayoutParams(lfLp);
        lifeCard.addView(lifeFoot);
        row.addView(wrapWithRightShadow(lifeCard, 1f));

        return row;
    }

    private LinearLayout statCard(int fill, int textColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(brutalistCard(fill));
        int p = dp(14);
        card.setPadding(p, p, p, p);
        return card;
    }

    /** Wraps a card so its bottom-right has the offset-shadow gap. Equal-weight columns. */
    private View wrapWithRightShadow(View card, float weight) {
        LinearLayout wrap = new LinearLayout(this);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, weight);
        wrap.setLayoutParams(wLp);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = dp(4);
        cLp.bottomMargin = dp(4);
        card.setLayoutParams(cLp);
        wrap.addView(card);
        return wrap;
    }

    // -- empty state ---------------------------------------------------------

    private View buildEmptyCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(brutalistCard(WHITE));
        card.setGravity(Gravity.CENTER);
        int p = dp(28);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.rightMargin = dp(4);
        lp.bottomMargin = dp(4);
        card.setLayoutParams(lp);

        TextView icon = new TextView(this);
        icon.setText("💸");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon);

        TextView title = new TextView(this);
        title.setText("No splits yet");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(8);
        title.setLayoutParams(tLp);
        card.addView(title);

        TextView body = new TextView(this);
        body.setText("Type /split <amount> in any text field to save your first one.");
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setTextColor(MUTED);
        body.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(6);
        body.setLayoutParams(bLp);
        card.addView(body);

        return card;
    }

    // -- render --------------------------------------------------------------

    private void render() {
        // Profile bits
        String email = auth.accountEmail();
        profileEmail.setText(email != null ? email : "your Google account");
        profileAvatar.setText(initial(email));
        profileAvatar.setBackground(circleDrawable(avatarColor(email)));
        boolean haveSheet = !store.getString(SplitKeys.SHEET_ID, "").isEmpty();
        sheetLinkBtn.setEnabled(haveSheet);
        sheetLinkBtn.setAlpha(haveSheet ? 1f : 0.45f);

        // Role + visibility
        boolean isOwner = SplitCloudSync.isOwner(store);
        if (isOwner) {
            roleBadge.setText("OWNER");
            roleBadge.setTextColor(WHITE);
            roleBadge.setBackground(pillDrawable(LIME));
        } else {
            roleBadge.setText("MEMBER");
            roleBadge.setTextColor(INK);
            roleBadge.setBackground(pillDrawable(0xFFEEF1FF));
        }
        // Invite button is owner-only; label flips based on current membership state.
        boolean canInvite = isOwner && haveSheet;
        inviteBtn.setVisibility(canInvite ? View.VISIBLE : View.GONE);
        if (canInvite) {
            boolean open = SplitCloudSync.isMembershipOpen(store);
            if (open) {
                inviteBtn.setText("⏹  Stop accepting members");
                inviteBtn.setTextColor(WHITE);
                inviteBtn.setBackground(pillDrawable(0xFFB91C1C));
            } else {
                inviteBtn.setText("➕  Invite a member");
                inviteBtn.setTextColor(WHITE);
                inviteBtn.setBackground(pillDrawable(LIME));
            }
        }
        clearAllBtn.setVisibility(isOwner ? View.VISIBLE : View.GONE);

        // Stats + list
        List<SplitHistory.Entry> entries = history.all();
        int monthCount = 0;
        double lifetime = 0;
        double month = 0;
        long monthStart = startOfThisMonth();
        for (SplitHistory.Entry e : entries) {
            lifetime += e.amount;
            if (e.timestampMs >= monthStart) { month += e.amount; monthCount++; }
        }
        totalLifetime.setText("₹" + formatAmount(lifetime));
        totalMonth.setText("₹" + formatAmount(month));
        countLine.setText(monthCount + (monthCount == 1 ? " split" : " splits"));

        listColumn.removeAllViews();
        if (entries.isEmpty()) {
            emptyCard.setVisibility(View.VISIBLE);
            return;
        }
        emptyCard.setVisibility(View.GONE);
        long now = System.currentTimeMillis();
        for (SplitHistory.Entry e : entries) {
            listColumn.addView(buildRow(e, now));
        }
    }

    private View buildRow(SplitHistory.Entry e, long now) {
        // Wrap so the offset shadow has room on the right + bottom.
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        wLp.bottomMargin = dp(12);
        wrap.setLayoutParams(wLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(brutalistCard(WHITE));
        int pad = dp(14);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rLp.rightMargin = dp(4);
        rLp.bottomMargin = dp(4);
        row.setLayoutParams(rLp);

        // Top: amount on left, count chip on right
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView amount = new TextView(this);
        amount.setText("₹" + formatAmount(e.amount));
        amount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        amount.setTextColor(INK);
        amount.setTypeface(Typeface.DEFAULT_BOLD);
        amount.setLayoutParams(new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));
        top.addView(amount);

        TextView chip = new TextView(this);
        chip.setText(e.people + (e.people == 1 ? " person" : " people"));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        chip.setTextColor(INK);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setBackground(pillDrawable(0xFFEEF1FF));
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.addView(chip);
        row.addView(top);

        // Per-person breakdown
        double per = e.people > 0 ? e.amount / e.people : e.amount;
        TextView breakdown = new TextView(this);
        breakdown.setText("₹" + formatAmount(per) + " each");
        breakdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        breakdown.setTextColor(LIME);
        breakdown.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(2);
        breakdown.setLayoutParams(bLp);
        row.addView(breakdown);

        // When
        TextView when = new TextView(this);
        when.setText(fullDate.format(new Date(e.timestampMs))
                + "  ·  " + DateUtils.getRelativeTimeSpanString(
                        e.timestampMs, now, DateUtils.MINUTE_IN_MILLIS));
        when.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        when.setTextColor(MUTED);
        LinearLayout.LayoutParams wnLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        wnLp.topMargin = dp(2);
        when.setLayoutParams(wnLp);
        row.addView(when);

        // Action row
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        aLp.topMargin = dp(12);
        actions.setLayoutParams(aLp);

        TextView copy = textActionButton("Copy", LIME);
        copy.setOnClickListener(v -> { copySummary(e); toast("Copied"); });
        actions.addView(copy);
        addHSpacer(actions, dp(8));
        TextView share = textActionButton("Share", BLUE);
        share.setOnClickListener(v -> shareSummary(e));
        actions.addView(share);
        row.addView(actions);

        wrap.addView(row);
        return wrap;
    }

    private TextView textActionButton(String text, int color) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(pillDrawable(color));
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private void openSheet() {
        String sheetId = store.getString(SplitKeys.SHEET_ID, "");
        if (sheetId.isEmpty()) {
            toast("Sheet hasn't been created yet — open the app once while signed in.");
            return;
        }
        String url = "https://docs.google.com/spreadsheets/d/" + sheetId + "/edit";
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Sheet URL", url));
            toast("Link copied — open in your browser");
        }
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(this)
                .setTitle("Sign out?")
                .setMessage("Your splits stay saved on this device. Cloud sync stops until "
                        + "you sign in again.")
                .setPositiveButton("Sign out", (d, w) -> {
                    auth.signOut();
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // -- invite + clear ------------------------------------------------------

    /** Owner taps the invite button. Opens membership and shows the QR, or stops it. */
    private void onInviteTapped() {
        if (SplitCloudSync.isMembershipOpen(store)) {
            confirmStopMembership();
            return;
        }
        inviteBtn.setEnabled(false);
        SplitCloudSync.openMembership(this, store, deepLink -> runOnUiThread(() -> {
            inviteBtn.setEnabled(true);
            render();
            if (deepLink == null) {
                toast("Could not open invite — check connection and try again.");
                return;
            }
            showInviteQr(deepLink);
        }));
    }

    private void confirmStopMembership() {
        new AlertDialog.Builder(this)
                .setTitle("Stop accepting members?")
                .setMessage("New scans of the invite QR will no longer connect. Members "
                        + "who already joined keep access — remove them in Drive if needed.")
                .setPositiveButton("Stop", (d, w) -> {
                    inviteBtn.setEnabled(false);
                    SplitCloudSync.closeMembership(this, store, ok -> runOnUiThread(() -> {
                        inviteBtn.setEnabled(true);
                        render();
                        toast(ok ? "Membership closed" : "Could not close — try again");
                    }));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Owner-only: renders the join QR + share/copy actions. */
    private void showInviteQr(final String deepLink) {
        Bitmap qr = QrRenderer.render(deepLink, dp(240));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = dp(20);
        content.setPadding(p, p, p, p);

        TextView head = new TextView(this);
        head.setText("Scan to join");
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        head.setTextColor(INK);
        content.addView(head);

        TextView sub = new TextView(this);
        sub.setText("Anyone scanning this QR with their phone camera will be added as a "
                + "writer. Tap \"Stop accepting members\" when you're done sharing.");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(MUTED);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(12);
        sub.setLayoutParams(subLp);
        content.addView(sub);

        if (qr != null) {
            ImageView img = new ImageView(this);
            img.setImageBitmap(qr);
            int sz = dp(240);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(sz, sz);
            img.setLayoutParams(iLp);
            content.addView(img);
        }

        TextView linkLine = new TextView(this);
        linkLine.setText(deepLink);
        linkLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        linkLine.setTextColor(MUTED);
        linkLine.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lLp.topMargin = dp(12);
        linkLine.setLayoutParams(lLp);
        content.addView(linkLine);

        new AlertDialog.Builder(this)
                .setView(content)
                .setPositiveButton("Share link", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, deepLink);
                    startActivity(Intent.createChooser(send, "Invite to split"));
                })
                .setNeutralButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Invite", deepLink));
                    toast("Invite link copied");
                })
                .setNegativeButton("Done", null)
                .show();
    }

    private void confirmClearMine() {
        new AlertDialog.Builder(this)
                .setTitle("Clear your rows?")
                .setMessage("Removes only the splits this device added. Other members' "
                        + "rows stay intact. This can't be undone.")
                .setPositiveButton("Clear", (d, w) -> {
                    history.clear();
                    SplitCloudSync.pushClear(this, store);
                    render();
                    toast("Cleared");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle("Clear everyone's rows?")
                .setMessage("This wipes the entire shared history for every member. "
                        + "Only the owner can do this. This can't be undone.")
                .setPositiveButton("Clear all", (d, w) -> {
                    history.clear();
                    SplitCloudSync.pushClearAll(this, store);
                    render();
                    toast("Cleared all rows");
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    // -- drawables -----------------------------------------------------------

    /** Layered card: ink rectangle offset 4dp bottom-right, fill rectangle on top. */
    private Drawable brutalistCard(int fill) {
        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(INK);
        shadow.setCornerRadius(dp(2));
        GradientDrawable card = new GradientDrawable();
        card.setColor(fill);
        card.setStroke(dp(2), INK);
        card.setCornerRadius(dp(2));
        LayerDrawable layered = new LayerDrawable(new Drawable[]{shadow, card});
        layered.setLayerInset(0, dp(4), dp(4), 0, 0);
        layered.setLayerInset(1, 0, 0, dp(4), dp(4));
        return layered;
    }

    private Drawable pillDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(999));
        d.setStroke(dp(1), INK);
        return d;
    }

    private Drawable circleDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(2), INK);
        return d;
    }

    // -- helpers -------------------------------------------------------------

    private static String initial(String email) {
        if (email == null || email.isEmpty()) return "·";
        char c = email.charAt(0);
        return String.valueOf(Character.toUpperCase(c));
    }

    /** Picks a stable accent color for the avatar from the email hash. */
    private static int avatarColor(String email) {
        int[] palette = { LIME, PINK, BLUE, 0xFFFF7A1A /* orange */ };
        if (email == null || email.isEmpty()) return MUTED;
        return palette[Math.floorMod(email.hashCode(), palette.length)];
    }

    private void addHSpacer(LinearLayout container, int width) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        container.addView(v);
    }
    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

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
