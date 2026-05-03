package com.prince.turtlekeyboard.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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

import com.prince.split.kbd.AppProfile;
import com.prince.split.kbd.AppProfileRegistry;
import com.prince.turtlekeyboard.integration.PersistentAppProfileRegistry;
import com.prince.turtlekeyboard.settings.Prefs;

import java.util.Set;

/**
 * Manages per-app personalization state. Two sections:
 *
 * <ul>
 *   <li><b>Enrolled apps</b> — apps the user accepted from the in-keyboard banner. Each
 *       row has an "Remove" affordance that unenrolls (the next visit re-prompts).</li>
 *   <li><b>Suppressed apps</b> — apps the user dismissed with ✕. Each row has an "Allow"
 *       affordance that clears the don't-ask flag so the banner can return.</li>
 * </ul>
 *
 * <p>Built programmatically — no XML — to keep the file self-contained and matching the
 * IME's other view code. List rebuilds on every {@code onResume} so changes from the
 * keyboard during the same session reflect immediately.
 */
public class AppPersonalizationActivity extends AppCompatActivity {

    private AppProfileRegistry profiles;
    private LinearLayout enrolledList;
    private LinearLayout suppressedList;
    private TextView enrolledEmpty;
    private TextView suppressedEmpty;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        profiles = new PersistentAppProfileRegistry(getApplicationContext(), new Prefs(this));

        ScrollView root = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        column.setPadding(pad, pad, pad, pad);
        root.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(title("App personalization"));
        column.addView(subtitle(
                "Apps the keyboard has learned about. Removing an app re-asks; allowing "
                + "an app brings back the in-keyboard prompt."));

        column.addView(sectionHeader("Enrolled"));
        enrolledList = section();
        column.addView(enrolledList);
        enrolledEmpty = empty("No apps enrolled yet — open the keyboard in any app and tap Add.");
        column.addView(enrolledEmpty);

        column.addView(sectionHeader("Suppressed"));
        suppressedList = section();
        column.addView(suppressedList);
        suppressedEmpty = empty("Nothing suppressed.");
        column.addView(suppressedEmpty);

        setContentView(root);
        setTitle("App personalization");
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
            enrolledList.addView(buildRow(pkg, "Remove", () -> {
                profiles.unenroll(pkg);
                rebuild();
            }));
        }
        enrolledEmpty.setVisibility(enrolled.isEmpty() ? View.VISIBLE : View.GONE);

        Set<String> suppressed = profiles.suppressedPackages();
        for (String pkg : suppressed) {
            suppressedList.addView(buildRow(pkg, "Allow", () -> {
                profiles.unsuppress(pkg);
                rebuild();
            }));
        }
        suppressedEmpty.setVisibility(suppressed.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private View buildRow(String pkg, String actionText, Runnable onAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padV = dp(10), padH = dp(12);
        row.setPadding(padH, padV, padH, padV);
        row.setBackground(roundedFill(0x11000000, dp(10)));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(8);
        row.setLayoutParams(rowLp);

        ImageView icon = new ImageView(this);
        Drawable d = iconFor(pkg);
        icon.setImageDrawable(d);
        icon.setVisibility(d == null ? View.GONE : View.VISIBLE);
        int iconPx = dp(28);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconPx, iconPx);
        iconLp.rightMargin = dp(12);
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
        nameView.setTextColor(0xFF111111);
        textCol.addView(nameView);

        TextView pkgView = new TextView(this);
        pkgView.setText(pkg);
        pkgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        pkgView.setTextColor(0x99000000);
        textCol.addView(pkgView);

        TextView action = new TextView(this);
        action.setText(actionText);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        action.setTextColor(Color.WHITE);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(14), dp(8), dp(14), dp(8));
        action.setBackground(roundedFill(0xFF1F6F2A, dp(14)));
        action.setClickable(true);
        action.setFocusable(true);
        action.setOnClickListener(v -> onAction.run());
        row.addView(action);

        return row;
    }

    private LinearLayout section() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        t.setTextColor(0xFF111111);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    private TextView subtitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTextColor(0x99000000);
        t.setPadding(0, 0, 0, dp(20));
        return t;
    }

    private TextView sectionHeader(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setTextColor(0xFF111111);
        t.setAllCaps(true);
        t.setPadding(0, dp(12), 0, dp(8));
        return t;
    }

    private TextView empty(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTextColor(0x77000000);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        return t;
    }

    private GradientDrawable roundedFill(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    @Nullable
    private Drawable iconFor(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
