package com.prince.turtlekeyboard.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * Root container that turns a near-vertical downward drag into a fading slide-away
 * dismiss. Intercepts only when {@code dy > slop} and {@code dy > |dx| * 1.5}, so
 * horizontal swipes reach the embedded ViewPager2 and a panning zoomed child keeps
 * its touches. Release threshold: 25% screen height or vertical fling > 1500 dp/s.
 */
public class SwipeDismissLayout extends LinearLayout {

    public interface OnDismissListener { void onDismiss(); }

    private static final float DISMISS_DISTANCE_FRACTION = 0.25f;
    private static final float DISMISS_VELOCITY_DP_PER_S = 1500f;
    private static final long ANIM_RESET_MS = 180L;
    private static final long ANIM_DISMISS_MS = 220L;

    private final int touchSlop;
    private final float dismissVelocityPxPerS;

    private float startX, startY;
    private boolean dragging;
    @Nullable private VelocityTracker tracker;
    @Nullable private OnDismissListener dismissListener;

    public SwipeDismissLayout(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        dismissVelocityPxPerS = DISMISS_VELOCITY_DP_PER_S
                * ctx.getResources().getDisplayMetrics().density;
    }

    public void setOnDismissListener(@Nullable OnDismissListener l) {
        this.dismissListener = l;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = ev.getX();
                startY = ev.getY();
                dragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (dragging) return true;
                float dx = ev.getX() - startX;
                float dy = ev.getY() - startY;
                // 1.5x angle bias keeps near-diagonal pans from dismissing.
                if (dy > touchSlop && dy > Math.abs(dx) * 1.5f) {
                    dragging = true;
                    ensureTracker().addMovement(ev);
                    return true;
                }
                return false;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        ensureTracker().addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Reached only when no interactive child claimed the tap; still track so the gesture works.
                startX = ev.getX();
                startY = ev.getY();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = Math.max(0f, ev.getY() - startY);
                setTranslationY(dy);
                // Floor alpha at 0.2 so mid-flick content stays visible.
                float progress = Math.min(1f, dy / Math.max(1, getHeight() * 0.6f));
                setAlpha(Math.max(0.2f, 1f - progress));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float dy = getTranslationY();
                tracker.computeCurrentVelocity(1000);
                float vy = tracker.getYVelocity();
                releaseTracker();
                dragging = false;

                float dismissDistance = getHeight() * DISMISS_DISTANCE_FRACTION;
                if (dy > dismissDistance || vy > dismissVelocityPxPerS) {
                    animateDismiss();
                } else {
                    animateReset();
                }
                return true;
            }
        }
        return true;
    }

    private void animateReset() {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(this, "translationY", getTranslationY(), 0f),
                ObjectAnimator.ofFloat(this, "alpha", getAlpha(), 1f));
        set.setDuration(ANIM_RESET_MS);
        set.start();
    }

    private void animateDismiss() {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(this, "translationY", getTranslationY(), getHeight()),
                ObjectAnimator.ofFloat(this, "alpha", getAlpha(), 0f));
        set.setDuration(ANIM_DISMISS_MS);
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (dismissListener != null) dismissListener.onDismiss();
            }
        });
        set.start();
    }

    private VelocityTracker ensureTracker() {
        if (tracker == null) tracker = VelocityTracker.obtain();
        return tracker;
    }

    private void releaseTracker() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releaseTracker();
    }
}
