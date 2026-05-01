package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
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
        image.setImageDrawable(null);
        listener = null;
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
