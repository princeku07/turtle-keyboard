package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.command.CommandRegistry;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

import java.util.List;

/**
 * Autocomplete strip for slash commands. Mounts above the keys while the user is composing
 * a command name (composer NAME mode); each keystroke updates the matches. Tapping a pill
 * hands the command name back to the listener — the IME calls
 * {@code composer.enterPromptMode(name)}, identical to a Quick Panel pick, so the user
 * lands in the inline prompt panel without the host editor seeing the slash text.
 *
 * <p>Matches are affinity-ranked: in Slack, {@code /standup} sits ahead of {@code /search}
 * even though both start with {@code /s}.
 */
public class CommandSuggestionStripView extends HorizontalScrollView {

    public interface OnPickListener { void onPick(CommandRegistry.Entry entry); }

    private final LinearLayout row;
    @Nullable private OnPickListener pickListener;

    public CommandSuggestionStripView(Context context) { super(context); row = newRow(); init(); }
    public CommandSuggestionStripView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs); row = newRow(); init();
    }

    private LinearLayout newRow() {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private void init() {
        setHorizontalScrollBarEnabled(false);
        setVisibility(GONE);
        setBackgroundColor(0xFFFFFFFF); // overridden by applyTheme
        int padH = dp(8), padV = dp(6);
        row.setPadding(padH, padV, padH, padV);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void setOnPickListener(OnPickListener listener) { this.pickListener = listener; }

    public void show(List<CommandRegistry.Entry> entries) {
        row.removeAllViews();
        if (entries == null || entries.isEmpty()) { setVisibility(GONE); return; }
        for (CommandRegistry.Entry e : entries) row.addView(makePill(e));
        scrollTo(0, 0);
        setVisibility(VISIBLE);
    }

    public void hide() { setVisibility(GONE); }

    private TextView makePill(CommandRegistry.Entry e) {
        TextView pill = new TextView(getContext());
        String text = (e.emoji == null ? "" : e.emoji + "  ") + "/" + e.name;
        pill.setText(text);
        pill.setTextColor(0xFF0C0C0C);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        pill.setGravity(Gravity.CENTER);
        int padH = dp(12), padV = dp(6);
        pill.setPadding(padH, padV, padH, padV);
        pill.setBackground(pillBg(0xFFE8F5EE));
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setOnClickListener(v -> { if (pickListener != null) pickListener.onPick(e); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        pill.setLayoutParams(lp);
        return pill;
    }

    private GradientDrawable pillBg(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(14));
        g.setColor(fill);
        return g;
    }

    public void applyTheme(KeyboardTheme theme) {
        setBackgroundColor(theme.bannerBg);
        for (int i = 0; i < row.getChildCount(); i++) {
            android.view.View v = row.getChildAt(i);
            if (v instanceof TextView) {
                ((TextView) v).setTextColor(theme.bannerText);
                v.setBackground(pillBg(theme.chipFill));
            }
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
