package com.prince.turtlekeyboard.ui;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.ai.ImageHistory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Swipeable preview for {@link ImageHistory} entries. A {@link ViewPager2} of
 * {@link ZoomableImageView} pages drives the chrome, share, and delete actions.
 * Animated GIFs use {@link AnimatedImageDrawable} on API 28+, else first-frame still.
 */
public class HistoryPreviewActivity extends AppCompatActivity {

    private static final String TAG = "HistoryPreview";

    /** Timestamp of the tapped entry; preview re-derives the list and picks the matching page. */
    public static final String EXTRA_TIMESTAMP = "timestamp";

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_PROMPT = "prompt";

    private List<ImageHistory.Entry> entries;
    private ViewPager2 pager;
    private PreviewAdapter adapter;

    private TextView tagCommand, tagType, tagTime, promptText;
    private View promptCard;
    private Button shareBtn, deleteBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history_preview);

        entries = filterDisplayable(ImageHistory.list(this));
        if (entries.isEmpty()) {
            Toast.makeText(this, "No previews available", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        long startTs = getIntent().getLongExtra(EXTRA_TIMESTAMP, 0L);
        int startIndex = indexOfTimestamp(startTs);

        tagCommand = findViewById(R.id.tag_command);
        tagType = findViewById(R.id.tag_type);
        tagTime = findViewById(R.id.tag_time);
        promptCard = findViewById(R.id.prompt_card);
        promptText = findViewById(R.id.prompt_text);
        shareBtn = findViewById(R.id.btn_share);
        deleteBtn = findViewById(R.id.btn_delete);
        pager = findViewById(R.id.preview_pager);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());

        // Suppress OS close animation so pixels don't snap after the swipe-out animator.
        SwipeDismissLayout root = findViewById(R.id.swipe_root);
        root.setOnDismissListener(() -> {
            finish();
            overridePendingTransition(0, 0);
        });

        adapter = new PreviewAdapter();
        pager.setAdapter(adapter);
        pager.setCurrentItem(startIndex, false);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                updateChromeFor(position);
            }
        });
        updateChromeFor(startIndex);

        shareBtn.setOnClickListener(v -> shareCurrent());
        deleteBtn.setOnClickListener(v -> confirmDelete());
    }

    // ===== Public filter (shared with HistoryActivity) =====

    /** Drops gif/gift entries that point at a still image (sprite-sheet debug artifacts). */
    public static List<ImageHistory.Entry> filterDisplayable(List<ImageHistory.Entry> all) {
        List<ImageHistory.Entry> out = new ArrayList<>(all.size());
        for (ImageHistory.Entry e : all) {
            String cmd = e.command == null ? "" : e.command.trim().toLowerCase();
            boolean gifCmd = cmd.equals("gif") || cmd.equals("gift");
            boolean isGifFile = e.file.getName().endsWith(".gif");
            if (gifCmd && !isGifFile) continue;
            out.add(e);
        }
        return out;
    }

    // ===== Chrome ↔ page sync =====

    private void updateChromeFor(int position) {
        if (position < 0 || position >= entries.size()) return;
        ImageHistory.Entry e = entries.get(position);

        tagCommand.setText(e.command != null && !e.command.isEmpty()
                ? "/" + e.command.replaceFirst("^/", "")
                : "Preview");
        tagType.setText(typeLabelFor(e.file));
        tagTime.setText(e.ts > 0 ? relativeTime(e.ts) : "");

        if (e.prompt != null && !e.prompt.trim().isEmpty()) {
            promptCard.setVisibility(View.VISIBLE);
            promptText.setText(e.prompt.trim());
        } else {
            promptCard.setVisibility(View.GONE);
        }
    }

    private int indexOfTimestamp(long ts) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).ts == ts) return i;
        }
        return 0;
    }

    // ===== Actions =====

    private void shareCurrent() {
        int pos = pager.getCurrentItem();
        if (pos < 0 || pos >= entries.size()) return;
        ImageHistory.Entry e = entries.get(pos);

        Uri uri = FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", e.file);
        // image/webp routes through sticker pipelines; image/gif stays animated; image/png is a photo.
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(mimeFor(e.file));
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share"));
    }

    private static String typeLabelFor(File file) {
        String name = file.getName();
        if (name.endsWith(".gif")) return "GIF";
        if (name.endsWith(".webp")) return "STICKER";
        return "IMG";
    }

    private static String mimeFor(File file) {
        String name = file.getName();
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private void confirmDelete() {
        int pos = pager.getCurrentItem();
        if (pos < 0 || pos >= entries.size()) return;
        ImageHistory.Entry e = entries.get(pos);
        String kind;
        String name = e.file.getName();
        if (name.endsWith(".gif")) kind = "GIF";
        else if (name.endsWith(".webp")) kind = "sticker";
        else kind = "image";

        new AlertDialog.Builder(this)
                .setTitle("Delete this " + kind + "?")
                .setMessage("This removes it from your local history. Anywhere you've already shared it stays intact.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> performDelete(pos))
                .show();
    }

    private void performDelete(int pos) {
        ImageHistory.Entry e = entries.get(pos);
        if (e.file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            e.file.delete();
        }
        File sidecar = new File(e.file.getParentFile(),
                e.file.getName().replaceAll("\\.[^.]+$", ".txt"));
        if (sidecar.exists()) {
            //noinspection ResultOfMethodCallIgnored
            sidecar.delete();
        }

        entries.remove(pos);
        if (entries.isEmpty()) {
            Toast.makeText(this, "Deleted — history empty", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        adapter.notifyItemRemoved(pos);
        // ViewPager2 keeps the same index (now the next or previous entry); re-paint chrome.
        int newPos = Math.min(pos, entries.size() - 1);
        updateChromeFor(newPos);
        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
    }

    // ===== Adapter =====

    private class PreviewAdapter extends RecyclerView.Adapter<PreviewAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final ZoomableImageView image;
            Holder(ZoomableImageView v) {
                super(v);
                image = v;
                image.setOnSingleTapListener(HistoryPreviewActivity.this::finish);
            }
        }

        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ZoomableImageView v = new ZoomableImageView(parent.getContext(), null);
            v.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            // Checker behind alpha PNGs so transparent pixels don't read as the dark activity bg.
            v.setTransparencyCheckerEnabled(true);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            ImageHistory.Entry e = entries.get(position);
            boolean isGif = e.file.getName().endsWith(".gif");
            loadInto(h.image, e.file, isGif);
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            super.onViewRecycled(h);
            // Drop pixels on recycle so long swipes don't pile up bitmap memory.
            h.image.setImageDrawable(null);
        }

        @Override public int getItemCount() { return entries.size(); }
    }

    private void loadInto(ZoomableImageView target, File source, boolean isGif) {
        if (isGif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(source);
                Drawable d = ImageDecoder.decodeDrawable(src);
                target.setImageDrawable(d);
                if (d instanceof AnimatedImageDrawable) {
                    AnimatedImageDrawable aid = (AnimatedImageDrawable) d;
                    aid.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    aid.start();
                }
                return;
            } catch (Exception e) {
                Log.w(TAG, "AnimatedImageDrawable decode failed; falling back to bitmap", e);
            }
        }
        try {
            target.setImageBitmap(BitmapFactory.decodeFile(source.getAbsolutePath()));
        } catch (Exception e) {
            Log.w(TAG, "Bitmap decode failed", e);
        }
    }

    // ===== Helpers =====

    /** "2m ago", "5h ago", "3d ago". Keeps the badge short. */
    private static String relativeTime(long ts) {
        long delta = Math.max(0, System.currentTimeMillis() - ts);
        long s = delta / 1000;
        if (s < 60) return s + "s ago";
        long m = s / 60;
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 24) return h + "h ago";
        long d = h / 24;
        if (d < 30) return d + "d ago";
        long mo = d / 30;
        if (mo < 12) return mo + "mo ago";
        return (mo / 12) + "y ago";
    }
}
