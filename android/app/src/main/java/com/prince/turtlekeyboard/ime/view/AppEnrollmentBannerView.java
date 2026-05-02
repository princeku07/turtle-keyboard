package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.theme.KeyboardTheme;

/**
 * Slim banner offered the first time the user types in an unknown app. Layout (left→right):
 * app icon · "Add &lt;App Name&gt;?" · ✓ · ✕. The ✓ enrolls the app for personalization
 * (affinity ranking, future per-app shortcut bundles); ✕ suppresses future prompts for
 * this package. Tapping anywhere on the row except ✕ also enrolls.
 */
public class AppEnrollmentBannerView extends LinearLayout {

    public interface Listener {
        void onAccept();
        void onDismiss();
    }

    private final ImageView icon;
    private final TextView label;
    private final TextView accept;
    private final TextView close;

    public AppEnrollmentBannerView(Context context) { this(context, null); }

    public AppEnrollmentBannerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        icon = new ImageView(context);
        label = new TextView(context);
        accept = new TextView(context);
        close = new TextView(context);
        init();
    }

    private void init() {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setVisibility(GONE);
        setBackgroundColor(0xFF0D3F12);
        int padH = dp(12), padV = dp(8);
        setPadding(padH, padV, padH, padV);

        int iconPx = dp(20);
        LayoutParams iconLp = new LayoutParams(iconPx, iconPx);
        iconLp.rightMargin = dp(10);
        addView(icon, iconLp);

        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        label.setSingleLine(true);
        LayoutParams labelLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        addView(label, labelLp);

        styleActionButton(accept, "✓ Add", 0x33FFFFFF);
        addView(accept, actionLp());

        styleActionButton(close, "✕", 0x22000000);
        LayoutParams closeLp = actionLp();
        closeLp.leftMargin = dp(8);
        addView(close, closeLp);
    }

    private void styleActionButton(TextView v, CharSequence text, int fill) {
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(6), dp(12), dp(6));
        v.setBackground(pillBg(fill));
        v.setClickable(true);
        v.setFocusable(true);
    }

    private LayoutParams actionLp() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable pillBg(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(14));
        g.setColor(fill);
        return g;
    }

    public void show(@Nullable Drawable iconDrawable, String appName, Listener listener) {
        icon.setImageDrawable(iconDrawable);
        icon.setVisibility(iconDrawable == null ? GONE : VISIBLE);
        label.setText("Add " + appName + "?");
        View.OnClickListener accepter = v -> { if (listener != null) listener.onAccept(); };
        accept.setOnClickListener(accepter);
        // Tapping the row body also enrolls — bigger target for the affirmative path.
        setOnClickListener(accepter);
        close.setOnClickListener(v -> { if (listener != null) listener.onDismiss(); });
        setVisibility(VISIBLE);
    }

    public void hide() { setVisibility(GONE); }

    public void applyTheme(KeyboardTheme theme) {
        setBackgroundColor(theme.bannerBg);
        label.setTextColor(theme.bannerText);
        accept.setTextColor(theme.bannerText);
        close.setTextColor(theme.bannerText);
        accept.setBackground(pillBg((theme.accent & 0x00FFFFFF) | 0x55000000));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
