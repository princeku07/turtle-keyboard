package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.command.CommandRegistry;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

import java.util.List;

/**
 * Quick Panel — replaces the keyboard's key area with a 2-column grid of slash commands
 * (PRD §6.6). Opened by double-tap-space.
 *
 * <p>A tap on a tile does <b>not</b> dispatch the command and does <b>not</b> write
 * "/&lt;name&gt;" to the host editor. The panel hands the command name back to the IME,
 * which routes through the same {@link com.prince.turtlekeyboard.command.CommandComposer}
 * the typed-slash flow uses — so picking from the grid is indistinguishable from typing
 * the command. The host app sees nothing until the final result is committed.
 *
 * <p>UX: emoji-forward tiles, slash-name primary line, descriptive label as subtitle, an
 * explicit "↓ Keyboard" dismiss bar at the bottom (since double-tap-space-to-close is not
 * discoverable on its own).
 */
public class QuickPanelView extends LinearLayout {

    public interface OnPickListener { void onPick(CommandRegistry.Entry entry); }
    public interface OnDismissListener { void onDismiss(); }

    private static final int COLUMNS = 2;

    private final ScrollView scroller;
    private final GridLayout grid;
    private final TextView dismissBar;
    @Nullable private KeyboardTheme theme;

    public QuickPanelView(Context context) {
        super(context);
        scroller = new ScrollView(context);
        grid = new GridLayout(context);
        dismissBar = new TextView(context);
        init();
    }

    public QuickPanelView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        scroller = new ScrollView(context);
        grid = new GridLayout(context);
        dismissBar = new TextView(context);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        setBackgroundColor(0xFFE6F4EE); // overridden by applyTheme

        // Grid scroller takes all remaining vertical space.
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        grid.setColumnCount(COLUMNS);
        grid.setUseDefaultMargins(false);
        int pad = dp(8);
        grid.setPadding(pad, pad, pad, pad);
        scroller.addView(grid, new ScrollView.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        LayoutParams scrollLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        addView(scroller, scrollLp);

        // Dismiss bar: full-width, fixed-height, single tap target back to keys.
        dismissBar.setText("↓ Keyboard");
        dismissBar.setTextColor(0xFF0C0C0C);
        dismissBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        dismissBar.setGravity(Gravity.CENTER);
        dismissBar.setClickable(true);
        dismissBar.setFocusable(true);
        int barPad = dp(10);
        dismissBar.setPadding(barPad, barPad, barPad, barPad);
        dismissBar.setBackgroundColor(0xFFFFFFFF);
        addView(dismissBar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void show(List<CommandRegistry.Entry> entries,
                     OnPickListener pickListener,
                     OnDismissListener dismissListener) {
        grid.removeAllViews();
        for (CommandRegistry.Entry e : entries) {
            grid.addView(makeTile(e, pickListener));
        }
        dismissBar.setOnClickListener(v -> {
            if (dismissListener != null) dismissListener.onDismiss();
        });
    }

    private View makeTile(CommandRegistry.Entry entry, OnPickListener listener) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(VERTICAL);
        tile.setGravity(Gravity.CENTER);
        int padV = dp(12), padH = dp(10);
        tile.setPadding(padH, padV, padH, padV);
        tile.setBackground(tileBg(0xFFFFFFFF));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> listener.onPick(entry));

        TextView emoji = new TextView(getContext());
        emoji.setText(entry.emoji == null ? "•" : entry.emoji);
        emoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        emoji.setGravity(Gravity.CENTER);
        tile.addView(emoji);

        TextView slash = new TextView(getContext());
        slash.setText("/" + entry.name);
        slash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        slash.setTextColor(0xFF0C0C0C);
        slash.setGravity(Gravity.CENTER);
        slash.setPadding(0, dp(4), 0, 0);
        tile.addView(slash);

        TextView sub = new TextView(getContext());
        sub.setText(entry.label);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        sub.setTextColor(0xCC0C0C0C);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(1), 0, 0);
        tile.addView(sub);

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        int margin = dp(4);
        lp.setMargins(margin, margin, margin, margin);
        tile.setLayoutParams(lp);
        return tile;
    }

    private GradientDrawable tileBg(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(14));
        g.setColor(fill);
        g.setStroke(dp(1), 0xFFD9E8DF);
        return g;
    }

    public void applyTheme(KeyboardTheme theme) {
        this.theme = theme;
        setBackgroundColor(theme.background);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            v.setBackground(tileBg(theme.bannerBg));
            if (v instanceof LinearLayout) {
                LinearLayout tile = (LinearLayout) v;
                for (int j = 0; j < tile.getChildCount(); j++) {
                    View child = tile.getChildAt(j);
                    if (child instanceof TextView) {
                        ((TextView) child).setTextColor(j == 2 ? 0xCC0C0C0C : theme.bannerText);
                    }
                }
            }
        }
        dismissBar.setBackgroundColor(theme.bannerBg);
        dismissBar.setTextColor(theme.bannerText);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
