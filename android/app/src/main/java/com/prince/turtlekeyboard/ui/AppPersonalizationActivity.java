package com.prince.turtlekeyboard.ui;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.prince.kbd.core.AppProfile;
import com.prince.kbd.core.AppProfileRegistry;
import com.prince.turtlekeyboard.integration.PersistentAppProfileRegistry;
import com.prince.turtlekeyboard.settings.Prefs;

import java.util.Set;

/** Per-app personalization screen with enrolled (Customize/Remove) and suppressed (Allow) lists. */
public class AppPersonalizationActivity extends AppCompatActivity {

    private AppProfileRegistry profiles;
    private LinearLayout enrolledList;
    private LinearLayout suppressedList;
    private TextView enrolledEmpty;
    private TextView suppressedEmpty;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        profiles = new PersistentAppProfileRegistry(getApplicationContext(), new Prefs(this).root());

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(Brutal.CREAM);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = Brutal.dp(this, 20);
        column.setPadding(pad, pad, pad, pad);
        root.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(hero("App personalization"));
        column.addView(subhero(
                "Apps the keyboard has learned about. Customize per-app pinned commands "
                + "or remove an app to re-prompt next visit."));

        column.addView(card(buildEnrolledSection()));
        column.addView(card(buildSuppressedSection()));

        setContentView(root);
        setTitle("App personalization");
    }

    private LinearLayout buildEnrolledSection() {
        LinearLayout col = sectionColumn();
        col.addView(sectionLabel("Enrolled"));
        enrolledList = section();
        col.addView(enrolledList);
        enrolledEmpty = empty("No apps enrolled yet — open the keyboard in any app and tap Add.");
        col.addView(enrolledEmpty);
        return col;
    }

    private LinearLayout buildSuppressedSection() {
        LinearLayout col = sectionColumn();
        col.addView(sectionLabel("Suppressed"));
        suppressedList = section();
        col.addView(suppressedList);
        suppressedEmpty = empty("Nothing suppressed.");
        col.addView(suppressedEmpty);
        return col;
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    private void rebuild() {
        enrolledList.removeAllViews();
        suppressedList.removeAllViews();

        Set<String> enrolled = profiles.enrolledPackages();
        for (String pkg : enrolled) {
            enrolledList.addView(buildRow(pkg, "Customize", "Remove",
                    () -> startActivity(CommandPinsActivity.intentFor(this, pkg)),
                    () -> {
                        profiles.unenroll(pkg);
                        rebuild();
                    }));
        }
        enrolledEmpty.setVisibility(enrolled.isEmpty() ? View.VISIBLE : View.GONE);

        Set<String> suppressed = profiles.suppressedPackages();
        for (String pkg : suppressed) {
            suppressedList.addView(buildRow(pkg, null, "Allow", null, () -> {
                profiles.unsuppress(pkg);
                rebuild();
            }));
        }
        suppressedEmpty.setVisibility(suppressed.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private View buildRow(String pkg,
                          @Nullable String secondaryText,
                          String primaryText,
                          @Nullable Runnable onSecondary,
                          Runnable onPrimary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padV = Brutal.dp(this, 10), padH = Brutal.dp(this, 4);
        row.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rowLp);

        ImageView icon = new ImageView(this);
        Drawable d = iconFor(pkg);
        icon.setImageDrawable(d);
        icon.setVisibility(d == null ? View.GONE : View.VISIBLE);
        int iconPx = Brutal.dp(this, 32);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconPx, iconPx);
        iconLp.rightMargin = Brutal.dp(this, 12);
        row.addView(icon, iconLp);

        AppProfile profile = profiles.get(pkg);
        String name = profile != null ? profile.displayName : pkg;

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(textCol, textLp);

        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        nameView.setTextColor(Brutal.INK);
        textCol.addView(nameView);

        TextView pkgView = new TextView(this);
        pkgView.setText(pkg);
        pkgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        pkgView.setTextColor(Brutal.MUTED);
        textCol.addView(pkgView);

        if (secondaryText != null && onSecondary != null) {
            row.addView(brutalButton(secondaryText, false, onSecondary), buttonLp());
        }
        row.addView(brutalButton(primaryText, true, onPrimary), buttonLp());
        return row;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Brutal.dp(this, 6);
        return lp;
    }

    private TextView brutalButton(String text, boolean primary, Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTextColor(Brutal.INK);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Brutal.dp(this, 14), Brutal.dp(this, 8),
                Brutal.dp(this, 14), Brutal.dp(this, 8));
        t.setBackground(primary ? Brutal.buttonPrimary(this) : Brutal.buttonSecondary(this));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    // -- layout helpers ------------------------------------------------------

    private LinearLayout card(View child) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(Brutal.card(this, Brutal.SURFACE));
        int pad = Brutal.dp(this, 16);
        wrap.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Brutal.dp(this, 14);
        wrap.setLayoutParams(lp);
        wrap.addView(child);
        return wrap;
    }

    private LinearLayout sectionColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    private LinearLayout section() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView hero(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
        t.setTextColor(Brutal.INK);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, 0, 0, Brutal.dp(this, 4));
        return t;
    }

    private TextView subhero(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTextColor(Brutal.MUTED);
        t.setLineSpacing(0, 1.3f);
        t.setPadding(0, 0, 0, Brutal.dp(this, 18));
        return t;
    }

    private TextView sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        t.setTextColor(Brutal.MUTED);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, 0, 0, Brutal.dp(this, 8));
        return t;
    }

    private TextView empty(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTextColor(Brutal.MUTED);
        t.setPadding(Brutal.dp(this, 4), Brutal.dp(this, 8),
                Brutal.dp(this, 4), Brutal.dp(this, 8));
        return t;
    }

    @Nullable
    private Drawable iconFor(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
