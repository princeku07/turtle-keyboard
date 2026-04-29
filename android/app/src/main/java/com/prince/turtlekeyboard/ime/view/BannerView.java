package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

/** Transient status banner (slash command status, double-tap detected, errors). */
public class BannerView extends TextView {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hide = () -> setVisibility(GONE);

    public BannerView(Context context) { super(context); init(); }
    public BannerView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() { setVisibility(View.GONE); }

    public void show(String text) {
        setText(text);
        setVisibility(VISIBLE);
        main.removeCallbacks(hide);
    }

    public void showAndAutoHide(String text, long ms) {
        show(text);
        main.postDelayed(hide, ms);
    }

    public void clear() {
        main.removeCallbacks(hide);
        setVisibility(GONE);
    }
}
