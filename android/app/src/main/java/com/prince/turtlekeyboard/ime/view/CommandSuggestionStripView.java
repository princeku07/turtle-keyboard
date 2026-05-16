package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
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
 * Autocomplete strip for slash commands. Mounts above the keys while the user
 * is composing a command name (composer NAME mode); each keystroke updates the
 * matches. Tapping a pill hands the command name back to the listener — the
 * IME calls {@code composer.enterPromptMode(name)}, identical to a Quick Panel
 * pick, so the user lands in the inline prompt panel without the host editor
 * seeing the slash text.
 *
 * <p>The leftmost pill is a small ✕ close button. Tapping it fires the
 * dismiss listener, which the IME wires to {@code composer.cancel()} so the
 * user can bail out of command mode without having to backspace the slash.
 *
 * <p>Matches are affinity-ranked: in Slack, {@code /standup} sits ahead of
 * {@code /search} even though both start with {@code /s}.
 */
public class CommandSuggestionStripView extends HorizontalScrollView {

    public interface OnPickListener { void onPick(CommandRegistry.Entry entry); }
    public interface OnDismissListener { void onDismiss(); }

    private static final int BG = 0xFF000000;
    private static final int CHIP_FILL = 0x22FFFFFF;
    private static final int CLOSE_FILL = 0x33FFFFFF;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;

    private final LinearLayout row;
    @Nullable private OnPickListener pickListener;
    @Nullable private OnDismissListener dismissListener;

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
        setBackgroundColor(BG);
        int padH = dp(8), padV = dp(6);
        row.setPadding(padH, padV, padH, padV);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void setOnPickListener(OnPickListener listener) { this.pickListener = listener; }

    public void setOnDismissListener(OnDismissListener listener) { this.dismissListener = listener; }

    public void show(List<CommandRegistry.Entry> entries) {
        row.removeAllViews();
        if (entries == null || entries.isEmpty()) { setVisibility(GONE); return; }
        row.addView(makeClosePill());
        for (CommandRegistry.Entry e : entries) row.addView(makePill(e));
        scrollTo(0, 0);
        setVisibility(VISIBLE);
    }

    public void hide() { setVisibility(GONE); }

    private TextView makeClosePill() {
        TextView pill = new TextView(getContext());
        pill.setText("✕");
        pill.setTextColor(TEXT_PRIMARY);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        pill.setGravity(Gravity.CENTER);
        int padH = dp(10), padV = dp(6);
        pill.setPadding(padH, padV, padH, padV);
        pill.setBackground(pillBg(CLOSE_FILL));
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setOnClickListener(v -> { if (dismissListener != null) dismissListener.onDismiss(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        pill.setLayoutParams(lp);
        return pill;
    }

    private TextView makePill(CommandRegistry.Entry e) {
        TextView pill = new TextView(getContext());
        String text = (e.emoji == null ? "" : e.emoji + "  ") + "/" + e.name;
        pill.setText(text);
        pill.setTextColor(TEXT_PRIMARY);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        pill.setGravity(Gravity.CENTER);
        int padH = dp(12), padV = dp(6);
        pill.setPadding(padH, padV, padH, padV);
        pill.setBackground(pillBg(CHIP_FILL));
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
        // Surface and chip colours are fixed by the dark gradient design;
        // nothing to wire to the theme here. Method kept for symmetry with
        // sibling chip views.
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
