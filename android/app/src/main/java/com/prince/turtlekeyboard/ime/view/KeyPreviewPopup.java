package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.prince.turtlekeyboard.R;

import java.util.List;

/**
 * Custom key preview that floats directly above the pressed key. Uses a PopupWindow
 * anchored in window coordinates so positioning is correct regardless of where the
 * KeyboardView sits inside its parent (the framework's built-in preview drifts when
 * the KeyboardView isn't the IME root).
 */
public class KeyPreviewPopup {

    private final Context context;
    private final PopupWindow window;
    private final TextView label;
    private final int previewHeightPx;
    private final int minPreviewWidthPx;
    private final int verticalOffsetPx;
    private static final long LINGER_MS = 150L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable dismissRunnable = new Runnable() {
        @Override public void run() {
            if (window.isShowing()) window.dismiss();
        }
    };

    public KeyPreviewPopup(Context context) {
        this.context = context;
        label = new TextView(context);
        label.setBackgroundResource(R.drawable.key_preview_background);
        label.setTextColor(Color.parseColor("#0C0C0C"));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);
        int padH = dp(8);
        label.setPadding(padH, 0, padH, 0);

        window = new PopupWindow(label,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setTouchable(false);
        window.setFocusable(false);
        window.setClippingEnabled(false);
        window.setAnimationStyle(0);

        previewHeightPx = dp(56);
        minPreviewWidthPx = dp(44);
        verticalOffsetPx = dp(4);
    }

    public void show(KeyboardView kv, int primaryCode) {
        if (kv == null || kv.getKeyboard() == null) return;
        Keyboard.Key key = findKey(kv.getKeyboard().getKeys(), primaryCode);
        if (key == null || key.label == null) {
            dismissNow();
            return;
        }
        main.removeCallbacks(dismissRunnable);

        label.setText(key.label);
        // Measure to get the natural width given the label.
        int wSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int hSpec = View.MeasureSpec.makeMeasureSpec(previewHeightPx, View.MeasureSpec.EXACTLY);
        label.measure(wSpec, hSpec);
        int previewW = Math.max(label.getMeasuredWidth(), Math.max(minPreviewWidthPx, key.width));

        int[] loc = new int[2];
        kv.getLocationInWindow(loc);

        int keyCenterX = loc[0] + kv.getPaddingLeft() + key.x + key.width / 2;
        int x = keyCenterX - previewW / 2;
        int y = loc[1] + kv.getPaddingTop() + key.y - previewHeightPx - verticalOffsetPx;

        if (window.isShowing()) {
            window.update(x, y, previewW, previewHeightPx);
        } else {
            window.setWidth(previewW);
            window.setHeight(previewHeightPx);
            window.showAtLocation(kv, Gravity.NO_GRAVITY, x, y);
        }
    }

    public void dismiss() {
        main.removeCallbacks(dismissRunnable);
        main.postDelayed(dismissRunnable, LINGER_MS);
    }

    public void dismissNow() {
        main.removeCallbacks(dismissRunnable);
        if (window.isShowing()) window.dismiss();
    }

    private static Keyboard.Key findKey(List<Keyboard.Key> keys, int primaryCode) {
        for (Keyboard.Key k : keys) {
            if (k.codes != null && k.codes.length > 0 && k.codes[0] == primaryCode) return k;
        }
        return null;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics());
    }
}
