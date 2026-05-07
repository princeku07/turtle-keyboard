package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.ai.ImageHistory;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

import java.io.File;
import java.util.List;

/**
 * In-keyboard grid of generated images. Mounts in the {@code quickPanelHost} so it
 * temporarily replaces the key area, mirroring the Quick Panel pattern. Tap a tile
 * → the IME inserts that image into the focused field via {@code commitContent}.
 */
public class HistoryPanelView extends FrameLayout {

    public interface OnPick { void onPick(File file); }
    public interface OnClose { void onClose(); }

    private LinearLayout container;
    private TextView title;
    private TextView close;
    private GridView grid;
    private TextView emptyHint;
    private List<ImageHistory.Entry> entries;
    private KeyboardTheme theme;

    public HistoryPanelView(Context c) { super(c); init(); }
    public HistoryPanelView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        addView(container, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Header: "History" label + × close.
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int hp = dp(12);
        header.setPadding(hp, dp(8), hp, dp(8));
        title = new TextView(getContext());
        title.setText("🗂️ History");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        close = new TextView(getContext());
        close.setText("×");
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        close.setTypeface(close.getTypeface(), Typeface.BOLD);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x33FFFFFF);
        close.setBackground(bg);
        close.setClickable(true);
        close.setFocusable(true);
        header.addView(close, new LinearLayout.LayoutParams(dp(24), dp(24)));
        container.addView(header,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        // Grid: lazily populated in show().
        grid = new GridView(getContext());
        grid.setNumColumns(3);
        int g = dp(6);
        grid.setHorizontalSpacing(g);
        grid.setVerticalSpacing(g);
        grid.setPadding(hp, 0, hp, hp);
        grid.setVisibility(GONE);
        container.addView(grid,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyHint = new TextView(getContext());
        emptyHint.setText("No images yet.\nTry /style ghibli on a recent photo.");
        emptyHint.setTextColor(0xCCFFFFFF);
        emptyHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setVisibility(GONE);
        container.addView(emptyHint, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    public void show(List<ImageHistory.Entry> entries, OnPick onPick, OnClose onClose) {
        this.entries = entries;
        close.setOnClickListener(v -> { if (onClose != null) onClose.onClose(); });
        if (entries == null || entries.isEmpty()) {
            grid.setVisibility(GONE);
            emptyHint.setVisibility(VISIBLE);
            return;
        }
        emptyHint.setVisibility(GONE);
        grid.setVisibility(VISIBLE);
        grid.setAdapter(new ThumbAdapter());
        grid.setOnItemClickListener((parent, v, position, id) -> {
            if (onPick != null) onPick.onPick(this.entries.get(position).file);
        });
    }

    public void applyTheme(KeyboardTheme theme) {
        this.theme = theme;
        setBackgroundColor(theme.bannerBg);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private class ThumbAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int p) { return entries.get(p); }
        @Override public long getItemId(int p) { return entries.get(p).ts; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView iv;
            if (convertView instanceof ImageView) {
                iv = (ImageView) convertView;
            } else {
                iv = new ImageView(getContext());
                iv.setLayoutParams(new GridView.LayoutParams(
                        GridView.LayoutParams.MATCH_PARENT, dp(96)));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                final int radius = dp(6);
                iv.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                    }
                });
                iv.setClipToOutline(true);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            iv.setImageBitmap(BitmapFactory.decodeFile(
                    entries.get(position).file.getAbsolutePath(), opts));
            return iv;
        }
    }
}
