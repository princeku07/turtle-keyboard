package com.prince.turtlekeyboard.theme;

import android.graphics.Color;

/** Color tokens consumed by the keyboard UI; swap via {@link #turtleLight()} / {@link #turtleDark()}. */
public class KeyboardTheme {
    public final int background;
    public final int keyText;
    public final int accent;
    public final int suggestionText;
    public final int bannerBg;
    public final int bannerText;
    public final int chipFill;
    public final int divider;

    public final int keyFace;
    public final int functionFace;
    public final int pressedFace;
    public final int hintText;
    public final int enterFill;
    public final int enterIcon;

    public KeyboardTheme(int background, int keyText, int accent,
                         int suggestionText, int bannerBg, int bannerText,
                         int chipFill, int divider,
                         int keyFace, int functionFace, int pressedFace,
                         int hintText, int enterFill, int enterIcon) {
        this.background = background;
        this.keyText = keyText;
        this.accent = accent;
        this.suggestionText = suggestionText;
        this.bannerBg = bannerBg;
        this.bannerText = bannerText;
        this.chipFill = chipFill;
        this.divider = divider;
        this.keyFace = keyFace;
        this.functionFace = functionFace;
        this.pressedFace = pressedFace;
        this.hintText = hintText;
        this.enterFill = enterFill;
        this.enterIcon = enterIcon;
    }

    /** Black surface, lifted dark key faces, light glyphs, lime Enter accent. */
    public static KeyboardTheme turtleLight() {
        return new KeyboardTheme(
                Color.parseColor("#000000"),
                Color.parseColor("#F5F5F5"),
                Color.parseColor("#15803D"),
                Color.parseColor("#0C0C0C"),
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#0C0C0C"),
                Color.parseColor("#E8F5EE"),
                Color.parseColor("#D9E8DF"),
                Color.parseColor("#1E1E1E"),
                Color.parseColor("#141414"),
                Color.parseColor("#2E2E2E"),
                Color.parseColor("#888888"),
                Color.parseColor("#15803D"),
                Color.parseColor("#FFFFFF"));
    }

    /** Black surface, olive-green lifted key faces, off-white glyphs, muted green Enter. */
    public static KeyboardTheme turtleDark() {
        return new KeyboardTheme(
                Color.parseColor("#000000"),
                Color.parseColor("#E8EDE9"),
                Color.parseColor("#4F8E5C"),
                Color.parseColor("#E8EDE9"),
                Color.parseColor("#363D38"),
                Color.parseColor("#E8EDE9"),
                Color.parseColor("#3F4942"),
                Color.parseColor("#1F2521"),
                Color.parseColor("#4A554D"),
                Color.parseColor("#2F362F"),
                Color.parseColor("#5C6A5F"),
                Color.parseColor("#9AA39C"),
                Color.parseColor("#4F8E5C"),
                Color.parseColor("#FFFFFF"));
    }
}
