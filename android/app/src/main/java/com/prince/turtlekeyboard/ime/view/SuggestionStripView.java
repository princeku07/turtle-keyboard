package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

import java.util.List;

/** Three-slot suggestion strip with vertical dividers between slots. Clicking a slot fires
 *  the listener with the chosen string. */
public class SuggestionStripView extends LinearLayout {

    public interface OnPickListener {
        void onPick(String suggestion);
    }

    private OnPickListener listener;
    private KeyboardTheme theme;

    public SuggestionStripView(Context context) { super(context); init(); }
    public SuggestionStripView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        int padH = dp(4);
        setPadding(padH, 0, padH, 0);
    }

    public void setOnPickListener(OnPickListener l) { this.listener = l; }

    public void applyTheme(KeyboardTheme theme) {
        this.theme = theme;
        setBackgroundColor(theme.background);
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof TextView) ((TextView) v).setTextColor(theme.suggestionText);
        }
    }

    public void setSuggestions(List<String> suggestions) {
        removeAllViews();
        if (suggestions == null || suggestions.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        for (int i = 0; i < suggestions.size(); i++) {
            if (i > 0) addDivider();
            addSlot(suggestions.get(i));
        }
    }

    public void clear() { setSuggestions(null); }

    private void addSlot(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);
        tv.setTextColor(theme != null ? theme.suggestionText : Color.WHITE);
        tv.setBackgroundResource(R.drawable.suggestion_pick_ripple);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onPick(text);
        });
        LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        addView(tv, lp);
    }

    private void addDivider() {
        View v = new View(getContext());
        v.setBackgroundColor(0x33FFFFFF);
        LayoutParams lp = new LayoutParams(dp(1), dp(22));
        lp.gravity = Gravity.CENTER_VERTICAL;
        addView(v, lp);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
