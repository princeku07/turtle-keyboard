package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.theme.KeyboardTheme;

/**
 * Inline panel shown above the keyboard while the user is typing a command argument
 * (e.g. the URL after "/search "). Mirrors the keystrokes captured by the composer and
 * exposes a "Go" button that dispatches the command.
 */
public class CommandPanelView extends LinearLayout {

    public interface OnGoListener { void onGo(); }

    private TextView labelView;
    private TextView queryView;
    private TextView goButton;
    private OnGoListener onGo;
    private String hint = "type and tap →";

    public CommandPanelView(Context context) { super(context); init(); }
    public CommandPanelView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setOrientation(HORIZONTAL);
        setVisibility(GONE);
        setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(12), padV = dp(8);
        setPadding(padH, padV, padH, padV);

        labelView = new TextView(getContext());
        labelView.setTextColor(Color.WHITE);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        labelView.setTypeface(labelView.getTypeface(), Typeface.BOLD);
        addView(labelView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        queryView = new TextView(getContext());
        queryView.setTextColor(Color.WHITE);
        queryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        queryView.setSingleLine(true);
        queryView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        queryView.setPadding(dp(10), 0, dp(10), 0);
        LayoutParams qLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        addView(queryView, qLp);

        goButton = new TextView(getContext());
        goButton.setText("➤");
        goButton.setTextColor(Color.WHITE);
        goButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        goButton.setTypeface(goButton.getTypeface(), Typeface.BOLD);
        goButton.setGravity(Gravity.CENTER);
        goButton.setPadding(dp(14), dp(6), dp(14), dp(6));
        goButton.setBackgroundColor(Color.parseColor("#2E7D32"));
        goButton.setClickable(true);
        goButton.setFocusable(true);
        goButton.setOnClickListener(v -> { if (onGo != null) onGo.onGo(); });
        addView(goButton, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    public void show(String label, String hint, String query) {
        labelView.setText(label);
        this.hint = hint == null ? "" : hint;
        renderQuery(query);
        setVisibility(VISIBLE);
    }

    public void update(String query) {
        renderQuery(query);
    }

    public void hide() {
        setVisibility(GONE);
    }

    private void renderQuery(String q) {
        if (q == null || q.isEmpty()) {
            queryView.setText(hint);
            queryView.setAlpha(0.55f);
        } else {
            queryView.setText(q);
            queryView.setAlpha(1f);
        }
    }

    public void setOnGoListener(OnGoListener l) { onGo = l; }

    public void applyTheme(KeyboardTheme theme) {
        setBackgroundColor(theme.bannerBg);
        labelView.setTextColor(theme.bannerText);
        queryView.setTextColor(theme.bannerText);
        goButton.setBackgroundColor(theme.accent);
        goButton.setTextColor(theme.background);
    }
}
