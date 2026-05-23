package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * Passive "you are here" indicator at the top-left of the keyboard when typing in
 * an enrolled host app. Unlike {@link IntegrationChipView}, this is not interactive
 * and shows for every enrolled app regardless of integration state.
 */
public class HostAppBadgeView extends LinearLayout {

    private final ImageView icon;

    public HostAppBadgeView(Context context) { this(context, null); }

    public HostAppBadgeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        setVisibility(GONE);
        int padH = dp(8), padV = dp(4);
        setPadding(padH, padV, padH, padV);

        icon = new ImageView(context);
        int iconPx = dp(18);
        LayoutParams lp = new LayoutParams(iconPx, iconPx);
        icon.setBackground(roundedFill(0xFFE8F5EE, dp(9)));
        icon.setPadding(dp(2), dp(2), dp(2), dp(2));
        addView(icon, lp);
    }

    public void show(Drawable iconDrawable) {
        if (iconDrawable == null) { hide(); return; }
        icon.setImageDrawable(iconDrawable);
        setVisibility(VISIBLE);
    }

    public void hide() { setVisibility(GONE); }

    private GradientDrawable roundedFill(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
