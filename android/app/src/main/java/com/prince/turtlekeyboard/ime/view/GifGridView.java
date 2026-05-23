package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scrollable grid of generated GIFs surfaced as a tab inside {@link EmojiPanelView}.
 * Tapping a tile inserts the GIF via the keyboard's commitImage path. API 24–27
 * shows a static first-frame preview; API 28+ decodes the animated drawable lazily.
 */
public class GifGridView extends FrameLayout {

    private static final String TAG = "GifGridView";

    public interface OnGifPick { void onPick(File file); }

    private static final int COLUMNS = 3;

    private static final int TILE_SIDE_DP = 96;

    private static final ExecutorService DECODE_IO = Executors.newFixedThreadPool(2);

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final GridView grid;
    private final LinearLayout emptyState;
    private final GifAdapter adapter;
    @Nullable private OnGifPick pickListener;

    public GifGridView(Context context) {
        this(context, null);
    }

    public GifGridView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        int pad = dp(12);

        grid = new GridView(context);
        grid.setNumColumns(COLUMNS);
        grid.setHorizontalSpacing(dp(6));
        grid.setVerticalSpacing(dp(6));
        grid.setPadding(pad, pad, pad, pad);
        grid.setClipToPadding(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(new GradientDrawable());
        adapter = new GifAdapter();
        grid.setAdapter(adapter);
        // Restart animations on newly-visible tiles; recycled tiles get stop() on their next bind().
        grid.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int s) {}
            @Override public void onScroll(android.widget.AbsListView v, int firstVisibleItem,
                                           int visibleItemCount, int totalItemCount) {
                for (int i = 0; i < v.getChildCount(); i++) {
                    View c = v.getChildAt(i);
                    if (c instanceof GifTileView) ((GifTileView) c).ensurePlaying();
                }
            }
        });
        addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        emptyState = new LinearLayout(context);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(dp(24), dp(8), dp(24), dp(8));
        emptyState.setVisibility(GONE);

        TextView icon = new TextView(context);
        icon.setText("🎞️");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f);
        icon.setIncludeFontPadding(false);
        icon.setAlpha(0.75f);
        emptyState.addView(icon, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(context);
        heading.setText("No GIFs yet");
        heading.setTextColor(0xFFF5F5F5);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setIncludeFontPadding(false);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(12);
        emptyState.addView(heading, hLp);

        TextView subtitle = new TextView(context);
        subtitle.setText("Run /gif or /gift in any chat —\nyour animations land here.");
        subtitle.setTextColor(0xA0F5F5F5);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subtitle.setIncludeFontPadding(false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(4);
        emptyState.addView(subtitle, sLp);

        LayoutParams ehp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        ehp.gravity = Gravity.CENTER;
        addView(emptyState, ehp);
    }

    public void setData(List<File> gifs) {
        adapter.setData(gifs == null ? new ArrayList<>() : gifs);
        boolean empty = adapter.getCount() == 0;
        grid.setVisibility(empty ? GONE : VISIBLE);
        emptyState.setVisibility(empty ? VISIBLE : GONE);
    }

    public void setOnGifPick(@Nullable OnGifPick listener) {
        this.pickListener = listener;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private class GifAdapter extends BaseAdapter {
        private List<File> data = new ArrayList<>();

        void setData(List<File> next) {
            this.data = next;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int p) { return data.get(p); }
        @Override public long getItemId(int p) { return data.get(p).getName().hashCode(); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            GifTileView tile;
            if (convertView instanceof GifTileView) {
                tile = (GifTileView) convertView;
            } else {
                tile = new GifTileView(getContext());
                tile.setLayoutParams(new GridView.LayoutParams(
                        GridView.LayoutParams.MATCH_PARENT, dp(TILE_SIDE_DP)));
            }
            final File file = data.get(position);
            tile.bind(file);
            tile.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (pickListener != null) pickListener.onPick(file);
            });
            return tile;
        }
    }

    private class GifTileView extends FrameLayout {

        private final ImageView image;
        private final TextView badge;
        @Nullable private File currentFile;
        // Monotonic token; stale decode callbacks drop themselves.
        private long bindEpoch;
        @Nullable private Drawable animatedDrawable;

        GifTileView(Context ctx) {
            super(ctx);
            setClickable(true);
            setFocusable(true);
            // Outline on the container so the badge clips with the rounded corners.
            final int radius = dp(10);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            setClipToOutline(true);
            // Faint card behind the image so the tile is visible before its thumbnail decodes.
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setShape(GradientDrawable.RECTANGLE);
            cardBg.setColor(0x14FFFFFF);
            cardBg.setCornerRadius(radius);
            setBackground(cardBg);
            GradientDrawable rippleMask = new GradientDrawable();
            rippleMask.setShape(GradientDrawable.RECTANGLE);
            rippleMask.setColor(Color.WHITE);
            rippleMask.setCornerRadius(radius);
            setForeground(new RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF), null, rippleMask));

            image = new ImageView(ctx);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(image, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));

            badge = new TextView(ctx);
            badge.setText("GIF");
            badge.setTextColor(0xFFFFFFFF);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            badge.setTypeface(badge.getTypeface(), Typeface.BOLD);
            badge.setLetterSpacing(0.08f);
            badge.setIncludeFontPadding(false);
            badge.setPadding(dp(5), dp(2), dp(5), dp(2));
            GradientDrawable pill = new GradientDrawable();
            pill.setShape(GradientDrawable.RECTANGLE);
            pill.setColor(0xCC000000);
            pill.setCornerRadius(dp(8));
            badge.setBackground(pill);
            LayoutParams blp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.BOTTOM | Gravity.END;
            blp.rightMargin = dp(5);
            blp.bottomMargin = dp(5);
            addView(badge, blp);
        }

        void bind(File file) {
            if (file.equals(currentFile)) {
                ensurePlaying();
                return;
            }
            // Recycled tile must not bleed the previous animation onto the new file.
            stopAnimation();
            animatedDrawable = null;
            currentFile = file;
            final long epoch = ++bindEpoch;

            ThumbnailLoader.load(file, dp(TILE_SIDE_DP), image);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                final File captured = file;
                DECODE_IO.execute(() -> {
                    Drawable d = decodeAnimated(captured);
                    if (d == null) return;
                    MAIN.post(() -> {
                        if (epoch != bindEpoch) return;
                        animatedDrawable = d;
                        image.setImageDrawable(d);
                        ensurePlaying();
                    });
                });
            }
        }

        /** Idempotent; safe to call from both bind() and the scroll callback. */
        void ensurePlaying() {
            if (animatedDrawable instanceof AnimatedImageDrawable) {
                AnimatedImageDrawable aid = (AnimatedImageDrawable) animatedDrawable;
                if (!aid.isRunning()) aid.start();
            }
        }

        private void stopAnimation() {
            if (animatedDrawable instanceof AnimatedImageDrawable) {
                AnimatedImageDrawable aid = (AnimatedImageDrawable) animatedDrawable;
                if (aid.isRunning()) aid.stop();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopAnimation();
        }

        @android.annotation.TargetApi(Build.VERSION_CODES.P)
        @Nullable
        private Drawable decodeAnimated(File file) {
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(file);
                int targetPx = dp(TILE_SIDE_DP) * 2;
                Drawable d = ImageDecoder.decodeDrawable(src, (decoder, info, source) -> {
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    int max = Math.max(w, h);
                    if (max > targetPx) {
                        float r = (float) targetPx / max;
                        decoder.setTargetSize(
                                Math.max(1, Math.round(w * r)),
                                Math.max(1, Math.round(h * r)));
                    }
                });
                if (d instanceof AnimatedImageDrawable) {
                    ((AnimatedImageDrawable) d).setRepeatCount(
                            AnimatedImageDrawable.REPEAT_INFINITE);
                }
                return d;
            } catch (Throwable t) {
                Log.w(TAG, "animated decode failed " + file.getName(), t);
                return null;
            }
        }
    }
}
