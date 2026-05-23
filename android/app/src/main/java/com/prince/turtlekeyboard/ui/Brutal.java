package com.prince.turtlekeyboard.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/**
 * Tiny design-system kit for the host app's programmatic UIs. Token names retain
 * their original brutalist palette ids but resolve to the dark theme values.
 */
public final class Brutal {

    public static final int CREAM   = 0xFFF5F5F5;
    public static final int INK     = 0xFF000000;
    public static final int LIME    = 0xFF22C55E;
    public static final int PINK    = 0xFFFF4FA3;
    public static final int BLUE    = 0xFF5B6CFF;
    public static final int ORANGE  = 0xFFFF7A1A;
    public static final int MUTED   = 0xFF9A9A9A;
    public static final int SURFACE = 0xFF0F0F0F;

    public static Drawable card(Context ctx, int surfaceColor) {
        return shadowedRect(ctx, surfaceColor, dp(ctx, 4));
    }

    public static Drawable buttonPrimary(Context ctx) {
        return shadowedRect(ctx, LIME, dp(ctx, 4));
    }

    public static Drawable buttonSecondary(Context ctx) {
        return shadowedRect(ctx, CREAM, dp(ctx, 4));
    }

    public static Drawable buttonAccent(Context ctx) {
        return shadowedRect(ctx, PINK, dp(ctx, 4));
    }

    /** Rounded dark rectangle with a hairline border. {@code offset} is kept for call-site compat. */
    private static Drawable shadowedRect(Context ctx, int fill, int offset) {
        GradientDrawable surface = new GradientDrawable();
        surface.setShape(GradientDrawable.RECTANGLE);
        surface.setColor(fill);
        int borderColor = (fill == LIME) ? LIME : 0xFF1F1F1F;
        surface.setStroke(dp(ctx, 1), borderColor);
        surface.setCornerRadius(dp(ctx, 12));
        return surface;
    }

    public static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    private Brutal() {}
}
