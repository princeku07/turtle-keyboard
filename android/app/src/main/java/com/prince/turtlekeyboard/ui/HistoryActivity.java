package com.prince.turtlekeyboard.ui;

import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.prince.turtlekeyboard.ai.ImageHistory;
import com.prince.turtlekeyboard.ime.view.ThumbnailLoader;

import java.io.File;
import java.util.List;

/** Grid of past image/GIF/sticker outputs; tapping a tile opens {@link HistoryPreviewActivity}. */
public class HistoryActivity extends AppCompatActivity {

    private List<ImageHistory.Entry> entries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("History");
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-scan on resume so deletions in HistoryPreviewActivity drop their tiles.
        if (entries != null) rebuild();
    }

    private void rebuild() {
        entries = HistoryPreviewActivity.filterDisplayable(ImageHistory.list(this));

        if (entries.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Generated images and GIFs land here.\nTry /sticker, /cap, or /gif in the keyboard.");
            tv.setGravity(android.view.Gravity.CENTER);
            int p = dp(24);
            tv.setPadding(p, p, p, p);
            setContentView(tv);
            return;
        }

        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        int g = dp(8);
        grid.setHorizontalSpacing(g);
        grid.setVerticalSpacing(g);
        int p = dp(12);
        grid.setPadding(p, p, p, p);
        grid.setAdapter(new HistoryAdapter());
        grid.setOnItemClickListener((parent, view, position, id) -> openPreview(position));
        setContentView(grid);
    }

    private void openPreview(int position) {
        ImageHistory.Entry e = entries.get(position);
        Intent i = new Intent(this, HistoryPreviewActivity.class);
        // Preview re-derives the filtered list and picks the page by timestamp.
        i.putExtra(HistoryPreviewActivity.EXTRA_TIMESTAMP, e.ts);
        startActivity(i);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private class HistoryAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int p) { return entries.get(p); }
        @Override public long getItemId(int p) { return entries.get(p).ts; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            HistoryTile tile;
            if (convertView instanceof HistoryTile) {
                tile = (HistoryTile) convertView;
            } else {
                tile = new HistoryTile(HistoryActivity.this);
                tile.setLayoutParams(new GridView.LayoutParams(
                        GridView.LayoutParams.MATCH_PARENT, dp(112)));
            }
            tile.bind(entries.get(position).file);
            return tile;
        }
    }

    /** Rounded thumbnail tile with a GIF/IMG/STICKER badge; decoding is off-thread via {@link ThumbnailLoader}. */
    private class HistoryTile extends FrameLayout {
        private final ImageView image;
        private final TextView badge;

        HistoryTile(android.content.Context ctx) {
            super(ctx);
            final int radius = dp(8);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            setClipToOutline(true);

            image = new ImageView(ctx);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(image, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));

            badge = new TextView(ctx);
            badge.setTextColor(0xFFFFFFFF);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            badge.setTypeface(badge.getTypeface(), Typeface.BOLD);
            badge.setLetterSpacing(0.08f);
            badge.setIncludeFontPadding(false);
            badge.setPadding(dp(6), dp(2), dp(6), dp(2));
            GradientDrawable pill = new GradientDrawable();
            pill.setShape(GradientDrawable.RECTANGLE);
            pill.setColor(0xCC000000);
            pill.setCornerRadius(dp(10));
            badge.setBackground(pill);
            LayoutParams blp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.BOTTOM | Gravity.END;
            blp.rightMargin = dp(6);
            blp.bottomMargin = dp(6);
            addView(badge, blp);
        }

        void bind(File file) {
            ThumbnailLoader.load(file, dp(112), image);
            String name = file.getName();
            String label;
            if (name.endsWith(".gif")) label = "GIF";
            else if (name.endsWith(".webp")) label = "STICKER";
            else label = "IMG";
            badge.setText(label);
        }
    }
}
