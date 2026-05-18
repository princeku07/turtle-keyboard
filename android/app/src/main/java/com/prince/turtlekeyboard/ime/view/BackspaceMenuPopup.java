package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.prince.turtlekeyboard.R;

/**
 * Floating menu shown above the backspace key after a long hold (~600ms). Offers
 * three bulk-delete actions; tapping one fires the callback and dismisses.
 * Outside touches dismiss without firing anything.
 */
public class BackspaceMenuPopup {

    public interface ActionListener {
        void onClearAll();
        void onDeleteWord();
        void onDeleteSentence();
    }

    private final Context context;
    private final PopupWindow window;
    private final LinearLayout content;
    private final int verticalOffsetPx;
    private ActionListener listener;

    public BackspaceMenuPopup(Context context) {
        this.context = context;
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setBackgroundResource(R.drawable.key_preview_background);
        int padH = dp(4);
        int padV = dp(4);
        content.setPadding(padH, padV, padH, padV);

        content.addView(makeButton("Clear", new Runnable() {
            @Override public void run() { if (listener != null) listener.onClearAll(); }
        }));
        content.addView(makeDivider());
        content.addView(makeButton("Word", new Runnable() {
            @Override public void run() { if (listener != null) listener.onDeleteWord(); }
        }));
        content.addView(makeDivider());
        content.addView(makeButton("Sentence", new Runnable() {
            @Override public void run() { if (listener != null) listener.onDeleteSentence(); }
        }));

        window = new PopupWindow(content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        // Non-null background is required for outsideTouchable to actually dismiss.
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setTouchable(true);
        window.setFocusable(false);
        window.setOutsideTouchable(true);
        window.setClippingEnabled(false);
        window.setAnimationStyle(0);

        verticalOffsetPx = dp(8);
    }

    public void setActionListener(ActionListener l) {
        this.listener = l;
    }

    /** Show centered above the key with the given primary code in the active keyboard. */
    public void showAbove(KeyboardView kv, int primaryCode) {
        if (kv == null || kv.getKeyboard() == null) return;
        Keyboard.Key target = null;
        for (Keyboard.Key k : kv.getKeyboard().getKeys()) {
            if (k.codes != null && k.codes.length > 0 && k.codes[0] == primaryCode) {
                target = k;
                break;
            }
        }
        if (target == null) return;

        int wSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        content.measure(wSpec, hSpec);
        int w = content.getMeasuredWidth();
        int h = content.getMeasuredHeight();

        int[] loc = new int[2];
        kv.getLocationInWindow(loc);
        // Right-align the menu to the key's right edge so the bulk-delete chips read
        // from left to right ending under the user's finger.
        int keyRight = loc[0] + kv.getPaddingLeft() + target.x + target.width;
        int x = Math.max(loc[0] + dp(4), keyRight - w);
        int y = loc[1] + kv.getPaddingTop() + target.y - h - verticalOffsetPx;

        if (window.isShowing()) {
            window.update(x, y, w, h);
        } else {
            window.setWidth(w);
            window.setHeight(h);
            window.showAtLocation(kv, Gravity.NO_GRAVITY, x, y);
        }
    }

    public boolean isShowing() {
        return window.isShowing();
    }

    public void dismiss() {
        if (window.isShowing()) window.dismiss();
    }

    private TextView makeButton(String label, final Runnable onTap) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(Color.parseColor("#F5F5F5"));
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        int padH = dp(14);
        int padV = dp(10);
        tv.setPadding(padH, padV, padH, padV);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onTap.run();
                dismiss();
            }
        });
        return tv;
    }

    private View makeDivider() {
        View v = new View(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT);
        int margin = dp(6);
        lp.topMargin = margin;
        lp.bottomMargin = margin;
        v.setLayoutParams(lp);
        v.setBackgroundColor(0x33FFFFFF);
        return v;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics());
    }
}
