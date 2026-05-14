package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Persistent chip that surfaces a contextual integration above the keys
 * (e.g. "GPay" while typing in Google Pay, "GPay · Split ₹500" once an amount is detected).
 * Distinct from {@link BannerView}: the chip sticks as long as the host context is valid;
 * the banner is transient.
 */
public class IntegrationChipView extends TextView {

    public interface OnTapListener { void onTap(); }

    @Nullable private OnTapListener tapListener;
    private final int iconPx;

    public IntegrationChipView(Context context) { super(context); init(); }
    public IntegrationChipView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    {
        iconPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 20, getResources().getDisplayMetrics());
    }

    private void init() {
        setVisibility(GONE);
        setGravity(Gravity.CENTER_VERTICAL);
        setTextColor(0xFFF5F5F5);
        // Translucent white wash on the now-black canvas; reads as a soft chip
        // rather than a hard rectangle. No corner radius — this view spans the
        // full width above the keys, so a flat fill is correct.
        setBackgroundColor(0x22FFFFFF);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int padV = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics());
        int padH = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 14, getResources().getDisplayMetrics());
        int gap = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        setPadding(padH, padV, padH, padV);
        setCompoundDrawablePadding(gap);
        setOnClickListener(v -> { if (tapListener != null) tapListener.onTap(); });
    }

    public void setOnTapListener(@Nullable OnTapListener l) { this.tapListener = l; }

    /**
     * Show the chip with a label and an optional leading icon (e.g. the host app icon).
     * Pass {@code null} for the icon to clear it.
     */
    public void show(String label, @Nullable Drawable icon) {
        if (icon != null) icon.setBounds(0, 0, iconPx, iconPx);
        setCompoundDrawables(icon, null, null, null);
        setText(label);
        setVisibility(VISIBLE);
    }

    public void hide() {
        setCompoundDrawables(null, null, null, null);
        setVisibility(View.GONE);
    }
}
