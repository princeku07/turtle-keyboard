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
 * Horizontal strip of one-tap preset chips shown above the keyboard during
 * {@code /style} prompt mode (and reusable for other commands later). Tapping
 * a chip fires the supplied listener — the IME wires this to dispatch the
 * command directly with the chip's value as the prompt, skipping the typing
 * step entirely.
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

    /** Show the strip with the given chip labels. The {@code displayLabel} is
     *  what the user sees; the {@code value} is what gets passed to {@code onTap}.
     *  {@code labels} == null or empty hides the strip. */
    public void setPresets(@Nullable List<String> values, @Nullable OnPresetTap listener) {
        row.removeAllViews();
        if (values == null || values.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        for (String value : values) {
            row.addView(makeChip(displayLabel(value), value, listener));
        }
        // Reset scroll so the first chip is always visible when the strip re-appears.
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
        // No stroke — keep edges soft to match the gradient/aesthetic direction.
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

    /** Title-case the canonical lowercase preset key for display. */
    private static String displayLabel(String value) {
        if (value == null || value.isEmpty()) return "";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    public void applyTheme(KeyboardTheme theme) {
        // Surface and chip colours fixed by the dark gradient design — no-op.
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
