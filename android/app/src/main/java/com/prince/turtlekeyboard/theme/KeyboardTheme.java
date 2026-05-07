package com.prince.turtlekeyboard.theme;

import android.graphics.Color;

/**
 * Color tokens consumed by the keyboard UI. Two factory presets — {@link #turtleLight()}
 * and {@link #turtleDark()} — define every value the views need so a single setter call
 * can recolor the whole IME at runtime.
 */
public class KeyboardTheme {
    /** Surface visible behind/around the keys. */
    public final int background;
    /** Glyph color painted on letter-key faces. */
    public final int keyText;
    /** Brand green — used for the Enter button and primary pills. */
    public final int accent;
    public final int suggestionText;
    /** Surface color for strips, banners, and panels above the keys. */
    public final int bannerBg;
    public final int bannerText;
    /** Soft fill used for chips/pills sitting on {@link #bannerBg}. */
    public final int chipFill;
    /** Hairline color for dividers on light surfaces. */
    public final int divider;

    /** Face color for the "letter" keys (q, a, z, …). */
    public final int keyFace;
    /** Face color for "function" keys (shift, backspace, ?123, comma, period, emoji, space). */
    public final int functionFace;
    /** Pressed-state wash painted over a key while the user is touching it. */
    public final int pressedFace;
    /** Tiny digit hint drawn in the top-right of letter keys. */
    public final int hintText;
    /** Solid fill for the circular Enter key. */
    public final int enterFill;
    /** Stroke color for the ↵ icon drawn inside the Enter circle. */
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

    /** Light theme: mint surface, white key faces, ink glyphs, lime Enter. */
    public static KeyboardTheme turtleLight() {
        return new KeyboardTheme(
                Color.parseColor("#E6F4EE"),  // background
                Color.parseColor("#0C0C0C"),  // keyText
                Color.parseColor("#15803D"),  // accent
                Color.parseColor("#0C0C0C"),  // suggestionText
                Color.parseColor("#FFFFFF"),  // bannerBg
                Color.parseColor("#0C0C0C"),  // bannerText
                Color.parseColor("#E8F5EE"),  // chipFill
                Color.parseColor("#D9E8DF"),  // divider
                Color.parseColor("#FFFFFF"),  // keyFace — white letter keys
                Color.parseColor("#C8E8D5"),  // functionFace — light mint function keys
                Color.parseColor("#A6D9BC"),  // pressedFace — deeper mint while touched
                Color.parseColor("#6B6B6B"),  // hintText — muted ink
                Color.parseColor("#15803D"),  // enterFill — brand green
                Color.parseColor("#FFFFFF")); // enterIcon — white arrow
    }

    /** Dark theme: olive-gray surface, lifted key faces, off-white glyphs, vivid green Enter. */
    public static KeyboardTheme turtleDark() {
        return new KeyboardTheme(
                Color.parseColor("#2C332E"),  // background
                Color.parseColor("#E8EDE9"),  // keyText
                Color.parseColor("#4F8E5C"),  // accent
                Color.parseColor("#E8EDE9"),  // suggestionText
                Color.parseColor("#363D38"),  // bannerBg — slightly lifted from bg
                Color.parseColor("#E8EDE9"),  // bannerText
                Color.parseColor("#3F4942"),  // chipFill
                Color.parseColor("#1F2521"),  // divider
                Color.parseColor("#4A554D"),  // keyFace — lifted letter keys
                Color.parseColor("#2F362F"),  // functionFace — recessed function keys
                Color.parseColor("#5C6A5F"),  // pressedFace
                Color.parseColor("#9AA39C"),  // hintText — muted off-white
                Color.parseColor("#4F8E5C"),  // enterFill — vivid muted green
                Color.parseColor("#FFFFFF")); // enterIcon — white arrow
    }
}
