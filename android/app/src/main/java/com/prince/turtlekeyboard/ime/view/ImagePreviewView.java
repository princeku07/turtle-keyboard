package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

import androidx.annotation.Nullable;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.prince.turtlekeyboard.ai.ImageVariants;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

/**
 * In-keyboard preview for an image result. Shows the rendered bitmap with a row of
 * share-type buttons (Image / Sticker / GIF) plus Cancel. The user confirms format
 * before the keyboard commits anything to the host field.
 */
public class ImagePreviewView extends LinearLayout {

    public interface Listener {
        void onShare(ImageVariants.Type type);
        void onCancel();
    }

    // Dark gradient palette — matches CommandPanelView and the voice stage.
    private static final int BG = 0xFF000000;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;
    private static final int CHIP_FILL = 0x22FFFFFF;
    private static final int CHIP_FILL_SUBTLE = 0x14FFFFFF;

    private ImageView image;
    private Listener listener;

    public ImagePreviewView(Context c) { super(c); init(); }
    public ImagePreviewView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        setOrientation(VERTICAL);
        setVisibility(GONE);
        int pad = dp(8);
        setPadding(pad, pad, pad, pad);
        setBackgroundColor(BG);

        image = new ImageView(getContext());
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setMaxHeight(dp(180));
        LayoutParams ip = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        image.setLayoutParams(ip);
        addView(image);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams rp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        row.setLayoutParams(rp);

        // Close pill is subtler and a fixed width — the three share types each
        // get equal weight so they read as a unified action row.
        row.addView(makePill("✕",       v -> { if (listener != null) listener.onCancel(); }, CHIP_FILL_SUBTLE, 0.6f));
        row.addView(makePill("Image",   v -> share(ImageVariants.Type.IMAGE),                 CHIP_FILL,        1f));
        row.addView(makePill("Sticker", v -> share(ImageVariants.Type.STICKER),               CHIP_FILL,        1f));
        row.addView(makePill("GIF",     v -> share(ImageVariants.Type.GIF),                   CHIP_FILL,        1f));

        addView(row);
    }

    private void share(ImageVariants.Type t) {
        if (listener != null) listener.onShare(t);
    }

    /** Translucent-white pill matching the dark gradient design. No outline, no
     *  offset shadow — soft edges only. */
    private TextView makePill(String label, View.OnClickListener onClick, int fill, float weight) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        b.setTextColor(TEXT_PRIMARY);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(fill);
        pill.setCornerRadius(dp(999));
        b.setBackground(pill);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LayoutParams.WRAP_CONTENT, weight);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    public boolean show(File file, Listener l) {
        this.listener = l;
        // Release any previous preview's bitmap before decoding a new one — show() can
        // be called again without an intervening hide() if the user dispatches a second
        // image command while the preview is still up.
        recycleCurrent();
        // Subsample on decode so the preview's ImageView (≤ display width × 180dp tall)
        // doesn't pull a full-res bitmap onto the heap. Even with /cap's 512px outputs
        // this saves a redundant decode pass after the gen-time peak.
        String path = file.getAbsolutePath();
        int targetPx = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        int w = bounds.outWidth, h = bounds.outHeight;
        while (w > 0 && h > 0 && w / 2 >= targetPx && h / 2 >= targetPx) {
            w /= 2; h /= 2; sample *= 2;
        }
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(path, decode);
        if (bmp == null) {
            this.listener = null;
            return false;
        }
        RoundedBitmapDrawable rounded = RoundedBitmapDrawableFactory.create(getResources(), bmp);
        rounded.setCornerRadius(dp(12));
        rounded.setAntiAlias(true);
        image.setImageDrawable(rounded);
        setVisibility(VISIBLE);
        return true;
    }

    public void hide() {
        setVisibility(GONE);
        recycleCurrent();
        listener = null;
    }

    private void recycleCurrent() {
        Drawable d = image.getDrawable();
        image.setImageDrawable(null);
        Bitmap b = null;
        if (d instanceof RoundedBitmapDrawable) {
            b = ((RoundedBitmapDrawable) d).getBitmap();
        } else if (d instanceof android.graphics.drawable.BitmapDrawable) {
            b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
        }
        if (b != null && !b.isRecycled()) b.recycle();
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    public void applyTheme(KeyboardTheme theme) {
        // Surface and chip colours fixed by the dark gradient design.
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
