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
 * Slim banner shown the first time the user types in an unknown app, offering to
 * enrol it for personalization. Tapping the row or the check accepts; the X suppresses
 * future prompts for that package.
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
        setBackgroundColor(0xFFFFFFFF);
        int padH = dp(12), padV = dp(8);
        setPadding(padH, padV, padH, padV);

        int iconPx = dp(20);
        LayoutParams iconLp = new LayoutParams(iconPx, iconPx);
        iconLp.rightMargin = dp(10);
        addView(icon, iconLp);

        label.setTextColor(0xFF0C0C0C);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        label.setSingleLine(true);
        LayoutParams labelLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        addView(label, labelLp);

        styleActionButton(accept, "✓ Add", 0xFF15803D, 0xFFFFFFFF);
        addView(accept, actionLp());

        styleActionButton(close, "✕", 0xFFE8F5EE, 0xFF0C0C0C);
        LayoutParams closeLp = actionLp();
        closeLp.leftMargin = dp(8);
        addView(close, closeLp);
    }

    private void styleActionButton(TextView v, CharSequence text, int fill, int textColor) {
        v.setText(text);
        v.setTextColor(textColor);
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
        setOnClickListener(accepter);
        close.setOnClickListener(v -> { if (listener != null) listener.onDismiss(); });
        setVisibility(VISIBLE);
    }

    public void hide() { setVisibility(GONE); }

    public void applyTheme(KeyboardTheme theme) {
        setBackgroundColor(theme.bannerBg);
        label.setTextColor(theme.bannerText);
        accept.setTextColor(0xFFFFFFFF);
        accept.setBackground(pillBg(theme.accent));
        close.setTextColor(theme.bannerText);
        close.setBackground(pillBg(theme.chipFill));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
