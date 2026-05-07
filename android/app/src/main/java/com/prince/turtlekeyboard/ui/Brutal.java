package com.prince.turtlekeyboard.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;

/**
 * Tiny design-system kit for the host app's programmatic UIs (per-app settings,
 * command pin editor). Keeps colors + shadow-card + brutal button factories in one
 * place so {@code AppPersonalizationActivity} and {@code CommandPinsActivity} render
 * identically. XML layouts use the styles in {@code res/values/styles.xml} which
 * mirror these.
 */
public final class Brutal {

    public static final int CREAM   = 0xFFF4EFE4;
    public static final int INK     = 0xFF0C0C0C;
    public static final int LIME    = 0xFF15803D;
    public static final int PINK    = 0xFFFF4FA3;
    public static final int BLUE    = 0xFF5B6CFF;
    public static final int ORANGE  = 0xFFFF7A1A;
    public static final int MUTED   = 0xFF6B6B6B;
    public static final int SURFACE = 0xFFFFFFFF;

    /** Card with offset ink shadow + ink stroke around a {@code surfaceColor} fill. */
    public static Drawable card(Context ctx, int surfaceColor) {
        return shadowedRect(ctx, surfaceColor, dp(ctx, 4));
    }

    /** Lime primary button background. Use {@link #INK} for the text color. */
    public static Drawable buttonPrimary(Context ctx) {
        return shadowedRect(ctx, LIME, dp(ctx, 4));
    }

    /** Cream/outline secondary button background. Use {@link #INK} for the text color. */
    public static Drawable buttonSecondary(Context ctx) {
        return shadowedRect(ctx, CREAM, dp(ctx, 4));
    }

    /** Pink accent button background. */
    public static Drawable buttonAccent(Context ctx) {
        return shadowedRect(ctx, PINK, dp(ctx, 4));
    }

    /** Builds the brutalist offset-shadow rectangle. The ink layer sits 4 dp down-right
     *  of the surface layer, producing the landing-page look without elevation tricks. */
    private static Drawable shadowedRect(Context ctx, int fill, int offset) {
        GradientDrawable shadow = new GradientDrawable();
        shadow.setShape(GradientDrawable.RECTANGLE);
        shadow.setColor(INK);
        shadow.setCornerRadius(dp(ctx, 2));

        GradientDrawable surface = new GradientDrawable();
        surface.setShape(GradientDrawable.RECTANGLE);
        surface.setColor(fill);
        surface.setStroke(dp(ctx, 2), INK);
        surface.setCornerRadius(dp(ctx, 2));

        LayerDrawable layered = new LayerDrawable(new Drawable[]{
                new InsetDrawable(shadow, offset, offset, 0, 0),
                new InsetDrawable(surface, 0, 0, offset, offset)
        });
        return layered;
    }

    public static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    private Brutal() {}
}
