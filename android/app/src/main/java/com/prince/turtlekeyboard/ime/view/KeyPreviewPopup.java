package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.prince.turtlekeyboard.R;

/**
 * Custom key preview floating above the pressed key. Anchored in window
 * coordinates because the framework's built-in preview drifts when the
 * KeyboardView isn't the IME root.
 */
public class KeyPreviewPopup {

    private final Context context;
    private final PopupWindow window;
    private final TextView label;
    private final int previewHeightPx;
    private final int minPreviewWidthPx;
    private final int verticalOffsetPx;
    private static final long LINGER_MS = 40L;
    private int shownPrimaryCode = Integer.MIN_VALUE;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Cache the active Keyboard's key-by-code lookup and per-code measured preview
    // width so each press doesn't pay an O(n) scan + a TextView.measure() call.
    // Rebuilt on Keyboard identity change (qwerty/symbols/dialpad swap).
    private Keyboard cachedFor;
    private final SparseArray<Keyboard.Key> keyByCode = new SparseArray<>();
    private final SparseIntArray previewWidthByCode = new SparseIntArray();
    private final Runnable dismissRunnable = new Runnable() {
        @Override public void run() {
            if (window.isShowing()) window.dismiss();
            shownPrimaryCode = Integer.MIN_VALUE;
        }
    };

    public KeyPreviewPopup(Context context) {
        this.context = context;
        label = new TextView(context);
        label.setBackgroundResource(R.drawable.key_preview_background);
        label.setTextColor(Color.parseColor("#ffffff"));
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
        if (kv == null) return;
        Keyboard kb = kv.getKeyboard();
        if (kb == null) return;
        if (kb != cachedFor) rebuildCache(kb);
        Keyboard.Key key = keyByCode.get(primaryCode);
        if (key == null || key.label == null) {
            dismissNow();
            return;
        }
        main.removeCallbacks(dismissRunnable);

        // Same-key re-tap: skip the reflow that would flash a visible gap on rapid double-taps.
        if (window.isShowing() && primaryCode == shownPrimaryCode) {
            return;
        }

        label.setText(key.label);
        int previewW = previewWidthByCode.get(primaryCode);
        if (previewW <= 0) {
            int wSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            int hSpec = View.MeasureSpec.makeMeasureSpec(previewHeightPx, View.MeasureSpec.EXACTLY);
            label.measure(wSpec, hSpec);
            previewW = Math.max(label.getMeasuredWidth(), Math.max(minPreviewWidthPx, key.width));
            previewWidthByCode.put(primaryCode, previewW);
        }

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
        shownPrimaryCode = primaryCode;
    }

    private void rebuildCache(Keyboard kb) {
        keyByCode.clear();
        previewWidthByCode.clear();
        for (Keyboard.Key k : kb.getKeys()) {
            if (k.codes != null && k.codes.length > 0) keyByCode.put(k.codes[0], k);
        }
        cachedFor = kb;
    }

    public void dismiss() {
        main.removeCallbacks(dismissRunnable);
        main.postDelayed(dismissRunnable, LINGER_MS);
    }

    public void dismissNow() {
        main.removeCallbacks(dismissRunnable);
        if (window.isShowing()) window.dismiss();
        shownPrimaryCode = Integer.MIN_VALUE;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics());
    }
}
