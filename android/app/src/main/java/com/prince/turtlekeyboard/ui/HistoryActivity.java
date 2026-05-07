package com.prince.turtlekeyboard.ui;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.prince.turtlekeyboard.ai.ImageHistory;

import java.util.List;

/**
 * Grid of past {@code /cap} and {@code /edit} outputs. Tapping a tile opens a
 * system share sheet so the user can drop it into any app. Empty state shows
 * a hint instead of a blank screen.
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
            tv.setText("Generated images will appear here.\nTry /cap a samurai cat in the keyboard.");
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
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("image/png");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share image"));
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
            ImageView iv;
            if (convertView instanceof ImageView) {
                iv = (ImageView) convertView;
            } else {
                iv = new ImageView(HistoryActivity.this);
                iv.setLayoutParams(new GridView.LayoutParams(
                        GridView.LayoutParams.MATCH_PARENT, dp(112)));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            }
            // Sub-sample on decode — 100 thumbnails at full PNG res would OOM.
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            iv.setImageBitmap(BitmapFactory.decodeFile(
                    entries.get(position).file.getAbsolutePath(), opts));
            return iv;
        }
    }
}
