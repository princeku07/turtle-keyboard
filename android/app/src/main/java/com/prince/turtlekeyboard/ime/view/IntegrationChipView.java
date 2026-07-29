package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * Persistent chip that surfaces a contextual integration above the keys. Sticks
 * as long as the host context is valid (unlike the transient {@link BannerView}).
 */
public class IntegrationChipView extends AppCompatTextView {

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

    /** Pass {@code null} for {@code icon} to clear it. */
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
