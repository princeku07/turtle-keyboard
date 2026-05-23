package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.theme.KeyboardTheme;

import java.util.List;

/**
 * Horizontal strip of one-tap preset chips above the keyboard. Tapping a chip
 * fires the listener with the chip's value, letting the IME dispatch a command
 * without typing.
 */
public class PresetChipStripView extends HorizontalScrollView {

    public interface OnPresetTap { void onTap(String preset); }

    private static final int BG = 0xFF000000;
    private static final int CHIP_FILL = 0x22FFFFFF;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;

    private LinearLayout row;

    public PresetChipStripView(Context c) { super(c); init(); }
    public PresetChipStripView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        setHorizontalScrollBarEnabled(false);
        setVisibility(GONE);
        setBackgroundColor(BG);
        row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(12), padV = dp(6);
        row.setPadding(padH, padV, padH, padV);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    /** {@code null}/empty {@code values} hides the strip. */
    public void setPresets(@Nullable List<String> values, @Nullable OnPresetTap listener) {
        row.removeAllViews();
        if (values == null || values.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        for (String value : values) {
            row.addView(makeChip(displayLabel(value), value, listener));
        }
        scrollTo(0, 0);
        setVisibility(VISIBLE);
    }

    public void hide() {
        setVisibility(GONE);
        row.removeAllViews();
    }

    private TextView makeChip(String label, String value, @Nullable OnPresetTap l) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextColor(TEXT_PRIMARY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(CHIP_FILL);
        bg.setCornerRadius(dp(16));
        t.setBackground(bg);
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> { if (l != null) l.onTap(value); });
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private static String displayLabel(String value) {
        if (value == null || value.isEmpty()) return "";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    public void applyTheme(KeyboardTheme theme) {
        // No-op; chrome is fixed by the dark gradient design.
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
