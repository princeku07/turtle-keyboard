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
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.prince.turtlekeyboard.R;

/**
 * Floating bulk-delete menu shown above the backspace key after a long hold.
 * Dismisses on outside touch or after {@link #AUTO_DISMISS_MS}.
 */
public class BackspaceMenuPopup {

    private static final long AUTO_DISMISS_MS = 1500L;

    public interface ActionListener {
        void onClearAll();
        void onDeleteWord();
        void onDeleteSentence();
    }

    private final Context context;
    private final PopupWindow window;
    private final PopupWindow dimWindow;
    private final View dimView;
    private final LinearLayout content;
    private final int verticalOffsetPx;
    private final Handler autoDismissHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoDismissRunnable = new Runnable() {
        @Override public void run() { dismiss(); }
    };
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

        // Dim layer sized to the IME root in showAbove(); keys underneath still receive touches.
        dimView = new View(context);
        dimView.setBackgroundColor(0x4D000000);
        dimWindow = new PopupWindow(dimView, 0, 0);
        dimWindow.setBackgroundDrawable(null);
        dimWindow.setTouchable(false);
        dimWindow.setFocusable(false);
        dimWindow.setOutsideTouchable(false);
        dimWindow.setClippingEnabled(false);
        dimWindow.setAnimationStyle(0);

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
        // Right-align to the key so chips end under the user's finger.
        int keyRight = loc[0] + kv.getPaddingLeft() + target.x + target.width;
        int x = Math.max(loc[0] + dp(4), keyRight - w);
        int y = loc[1] + kv.getPaddingTop() + target.y - h - verticalOffsetPx;

        View imeRoot = kv.getRootView();
        if (imeRoot != null) {
            int[] rootLoc = new int[2];
            imeRoot.getLocationInWindow(rootLoc);
            int rw = imeRoot.getWidth();
            int rh = imeRoot.getHeight();
            if (dimWindow.isShowing()) {
                dimWindow.update(rootLoc[0], rootLoc[1], rw, rh);
            } else {
                dimWindow.setWidth(rw);
                dimWindow.setHeight(rh);
                dimWindow.showAtLocation(kv, Gravity.NO_GRAVITY, rootLoc[0], rootLoc[1]);
            }
        }

        if (window.isShowing()) {
            window.update(x, y, w, h);
        } else {
            window.setWidth(w);
            window.setHeight(h);
            window.showAtLocation(kv, Gravity.NO_GRAVITY, x, y);
        }
        autoDismissHandler.removeCallbacks(autoDismissRunnable);
        autoDismissHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS);
    }

    public boolean isShowing() {
        return window.isShowing();
    }

    public void dismiss() {
        autoDismissHandler.removeCallbacks(autoDismissRunnable);
        if (window.isShowing()) window.dismiss();
        if (dimWindow.isShowing()) dimWindow.dismiss();
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
