package com.prince.turtlekeyboard.theme;

import android.graphics.Color;

public class KeyboardTheme {
    public final int background;
    public final int keyText;
    public final int accent;
    public final int suggestionText;
    public final int bannerBg;
    public final int bannerText;

    public KeyboardTheme(int background, int keyText, int accent,
                         int suggestionText, int bannerBg, int bannerText) {
        this.background = background;
        this.keyText = keyText;
        this.accent = accent;
        this.suggestionText = suggestionText;
        this.bannerBg = bannerBg;
        this.bannerText = bannerText;
    }

    public static KeyboardTheme turtleDark() {
        return new KeyboardTheme(
                Color.parseColor("#1B5E20"),
                Color.WHITE,
                Color.parseColor("#A5D6A7"),
                Color.WHITE,
                Color.parseColor("#0D3F12"),
                Color.WHITE);
    }
}
