package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.File;

import androidx.annotation.Nullable;

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

    private ImageView image;
    private Listener listener;

    public ImagePreviewView(Context c) { super(c); init(); }
    public ImagePreviewView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        setOrientation(VERTICAL);
        setVisibility(GONE);
        int pad = dp(8);
        setPadding(pad, pad, pad, pad);

        image = new ImageView(getContext());
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setMaxHeight(dp(180));
        LayoutParams ip = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        image.setLayoutParams(ip);
        addView(image);

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LayoutParams rp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(6);
        row.setLayoutParams(rp);

        row.addView(makeButton("✕", v -> {
            if (listener != null) listener.onCancel();
        }, 0.6f));
        row.addView(makeButton("Image", v -> share(ImageVariants.Type.IMAGE), 1f));
        row.addView(makeButton("Sticker", v -> share(ImageVariants.Type.STICKER), 1f));
        row.addView(makeButton("GIF", v -> share(ImageVariants.Type.GIF), 1f));

        addView(row);
    }

    private void share(ImageVariants.Type t) {
        if (listener != null) listener.onShare(t);
    }

    private Button makeButton(String label, android.view.View.OnClickListener onClick, float weight) {
        Button b = new Button(getContext());
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LayoutParams.WRAP_CONTENT, weight);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        b.setLayoutParams(lp);
        b.setOnClickListener(onClick);
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
        image.setImageBitmap(bmp);
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
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) b.recycle();
        }
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    public void applyTheme(KeyboardTheme theme) {
        setBackgroundColor(theme.bannerBg);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
