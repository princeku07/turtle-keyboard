package com.prince.turtlekeyboard.ime.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * Thin animated shimmer bar shown above the keys while a slash command awaits
 * an AI result. {@link #stop()} sets visibility to GONE so it consumes no layout.
 */
public class ShimmerView extends View {

    private static final int BASE_COLOR = 0xFFD9E8DF;
    private static final int HIGHLIGHT_COLOR = 0xFF15803D;
    private static final long CYCLE_MS = 1100L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    private float progress;

    public ShimmerView(Context c) { super(c); }
    public ShimmerView(Context c, @Nullable AttributeSet a) { super(c, a); }

    public void start() {
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(CYCLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        setVisibility(GONE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        paint.setShader(null);
        paint.setColor(BASE_COLOR);
        canvas.drawRect(0, 0, w, h, paint);

        float bandW = w * 0.35f;
        float cx = -bandW + (w + 2 * bandW) * progress;
        LinearGradient lg = new LinearGradient(
                cx - bandW, 0, cx + bandW, 0,
                new int[]{HIGHLIGHT_COLOR & 0x00FFFFFF, HIGHLIGHT_COLOR, HIGHLIGHT_COLOR & 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        paint.setShader(lg);
        canvas.drawRect(0, 0, w, h, paint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
