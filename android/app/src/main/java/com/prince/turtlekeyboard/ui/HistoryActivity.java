package com.prince.turtlekeyboard.ui;

import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import androidx.core.content.FileProvider;

import com.prince.turtlekeyboard.ai.ImageHistory;
import com.prince.turtlekeyboard.ime.view.ThumbnailLoader;

import java.io.File;
import java.util.List;

/**
 * Grid of past {@code /cap}, {@code /edit}, {@code /style}, {@code /sticker},
 * {@code /gif} and {@code /gift} outputs. Tapping a tile opens a system share
 * sheet so the user can drop it into any app. Empty state shows a hint instead
 * of a blank screen.
 *
 * <p>Each tile carries a small bottom-right type tag ({@code GIF} for animated
 * outputs, {@code IMG} for stills) so the user can tell at a glance what kind
 * of media each entry is without opening it.
 */
public class HistoryActivity extends AppCompatActivity {

    private List<ImageHistory.Entry> entries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("History");
        entries = ImageHistory.list(this);

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
        grid.setOnItemClickListener((parent, view, position, id) -> shareEntry(position));
        setContentView(grid);
    }

    private void shareEntry(int position) {
        ImageHistory.Entry e = entries.get(position);
        Uri uri = FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", e.file);
        // Pick mime by extension — ImageHistory now stores both .png and
        // .gif under the same command tag, so a fixed image/png would let
        // share targets refuse / mis-handle animated GIFs.
        String mime = e.file.getName().endsWith(".gif") ? "image/gif" : "image/png";
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(mime);
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share"));
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

    /** Single history cell — rounded thumbnail with a {@code GIF}/{@code IMG}
     *  badge at the bottom-right corner. Uses the shared {@link ThumbnailLoader}
     *  so this screen no longer decodes 100 PNGs on the main thread the way
     *  the original sync {@code BitmapFactory.decodeFile} did. */
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
            badge.setText(file.getName().endsWith(".gif") ? "GIF" : "IMG");
        }
    }
}
