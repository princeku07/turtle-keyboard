package com.prince.turtlekeyboard.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * ImageView with pinch-to-zoom, double-tap toggle (fit ↔ 2x), and drag-to-pan when
 * zoomed. Runs in MATRIX scale type so transforms compose cleanly and GIFs keep
 * animating. Single tap (un-zoomed) fires {@link OnSingleTapListener}; zoomed taps
 * are absorbed so they don't close the preview after a pan release.
 */
public class ZoomableImageView extends AppCompatImageView {

    public interface OnSingleTapListener { void onSingleTap(); }

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 5f;
    private static final float DOUBLE_TAP_SCALE = 2f;

    /** User zoom multiplier on top of fit-to-view base scale; 1.0 = fit. */
    private float currentScale = 1f;
    private boolean initialized;

    private final Matrix matrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;

    /** Shared tiled-checker bitmap for the transparency background. */
    @Nullable private static Bitmap sCheckerTile;
    @Nullable private Paint checkerPaint;
    private final RectF mappedDrawableRect = new RectF();
    private final RectF drawableRect = new RectF();
    private boolean transparencyCheckerEnabled;

    /** True during a scale gesture; suppresses single-finger pan so the second finger doesn't translate. */
    private boolean scaling;
    private float lastTouchX, lastTouchY;
    private int activePointerId = -1;

    @Nullable private OnSingleTapListener onSingleTapListener;

    public ZoomableImageView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        super.setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(ctx, new ScaleListener());
        tapDetector = new GestureDetector(ctx, new TapListener());
    }

    public void setOnSingleTapListener(@Nullable OnSingleTapListener l) {
        this.onSingleTapListener = l;
    }

    /** Draws a tiled checker behind the bitmap (clipped to its transformed rect). */
    public void setTransparencyCheckerEnabled(boolean enabled) {
        if (this.transparencyCheckerEnabled == enabled) return;
        this.transparencyCheckerEnabled = enabled;
        if (enabled && checkerPaint == null) {
            checkerPaint = new Paint();
            checkerPaint.setShader(new BitmapShader(checkerTile(),
                    Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }
        invalidate();
    }

    private static Bitmap checkerTile() {
        if (sCheckerTile != null) return sCheckerTile;
        // Fixed pixel size keeps the pattern consistent across zoom levels.
        final int s = 16;
        Bitmap bm = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bm);
        Paint p = new Paint();
        p.setColor(0xFFCFCFCF);
        c.drawRect(0,     0,     s * 2f, s * 2f, p);
        p.setColor(0xFF9C9C9C);
        c.drawRect(0,     0,     s,      s,      p);
        c.drawRect(s,     s,     s * 2f, s * 2f, p);
        sCheckerTile = bm;
        return bm;
    }

    @Override
    public void setScaleType(ScaleType scaleType) {
        // Ignored: always MATRIX so gestures compose cleanly.
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitToView();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (transparencyCheckerEnabled && checkerPaint != null) {
            Drawable d = getDrawable();
            if (d != null) {
                // Clip checker to the drawable's transformed rect so letterbox stays the activity bg.
                drawableRect.set(0f, 0f,
                        d.getIntrinsicWidth(), d.getIntrinsicHeight());
                getImageMatrix().mapRect(mappedDrawableRect, drawableRect);
                canvas.save();
                canvas.clipRect(mappedDrawableRect);
                canvas.drawRect(mappedDrawableRect, checkerPaint);
                canvas.restore();
            }
        }
        super.onDraw(canvas);
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        initialized = false;
        post(this::fitToView);
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        initialized = false;
        post(this::fitToView);
    }

    /** Recomputes the base matrix to CENTER_INSIDE the drawable and resets zoom to 1x. */
    private void fitToView() {
        Drawable d = getDrawable();
        if (d == null) return;
        int vw = getWidth();
        int vh = getHeight();
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (vw == 0 || vh == 0 || dw <= 0 || dh <= 0) return;

        float scale = Math.min((float) vw / dw, (float) vh / dh);
        float tx = (vw - dw * scale) / 2f;
        float ty = (vh - dh * scale) / 2f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(tx, ty);
        setImageMatrix(matrix);
        currentScale = 1f;
        initialized = true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!initialized) return false;
        scaleDetector.onTouchEvent(ev);
        tapDetector.onTouchEvent(ev);
        handlePan(ev);

        // Block parent intercept while zoomed/scaling, or vertical drags will hand off mid-pan.
        if (getParent() != null) {
            boolean consumeParent = currentScale > 1f || scaling;
            getParent().requestDisallowInterceptTouchEvent(consumeParent);
        }
        return true;
    }

    private void handlePan(MotionEvent ev) {
        if (scaling) return;
        if (currentScale <= 1f) return;

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lastTouchX = ev.getX();
                lastTouchY = ev.getY();
                activePointerId = ev.getPointerId(0);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int idx = ev.findPointerIndex(activePointerId);
                if (idx < 0) break;
                float x = ev.getX(idx);
                float y = ev.getY(idx);
                matrix.postTranslate(x - lastTouchX, y - lastTouchY);
                clampPan();
                setImageMatrix(matrix);
                lastTouchX = x;
                lastTouchY = y;
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                activePointerId = -1;
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                // Tracked finger lifted; switch to whichever remains for a smooth next move.
                int upIdx = ev.getActionIndex();
                int upId = ev.getPointerId(upIdx);
                if (upId == activePointerId) {
                    int newIdx = upIdx == 0 ? 1 : 0;
                    lastTouchX = ev.getX(newIdx);
                    lastTouchY = ev.getY(newIdx);
                    activePointerId = ev.getPointerId(newIdx);
                }
                break;
            }
        }
    }

    /** Re-translates the matrix so drawable edges stay inside the view; fits → centered. */
    private void clampPan() {
        Drawable d = getDrawable();
        if (d == null) return;
        matrix.getValues(matrixValues);
        float sx = matrixValues[Matrix.MSCALE_X];
        float tx = matrixValues[Matrix.MTRANS_X];
        float ty = matrixValues[Matrix.MTRANS_Y];

        float dw = d.getIntrinsicWidth() * sx;
        float dh = d.getIntrinsicHeight() * sx; // uniform scale, sx == sy
        float vw = getWidth();
        float vh = getHeight();

        float fixX = 0f, fixY = 0f;
        if (dw <= vw) {
            fixX = (vw - dw) / 2f - tx;
        } else if (tx > 0f) {
            fixX = -tx;
        } else if (tx + dw < vw) {
            fixX = vw - tx - dw;
        }
        if (dh <= vh) {
            fixY = (vh - dh) / 2f - ty;
        } else if (ty > 0f) {
            fixY = -ty;
        } else if (ty + dh < vh) {
            fixY = vh - ty - dh;
        }
        matrix.postTranslate(fixX, fixY);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScaleBegin(ScaleGestureDetector det) {
            scaling = true;
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector det) {
            float factor = det.getScaleFactor();
            float next = currentScale * factor;
            if (next < MIN_SCALE) {
                factor = MIN_SCALE / currentScale;
                next = MIN_SCALE;
            } else if (next > MAX_SCALE) {
                factor = MAX_SCALE / currentScale;
                next = MAX_SCALE;
            }
            matrix.postScale(factor, factor, det.getFocusX(), det.getFocusY());
            currentScale = next;
            clampPan();
            setImageMatrix(matrix);
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector det) {
            scaling = false;
        }
    }

    private class TapListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (currentScale > 1f) {
                fitToView();
            } else {
                matrix.postScale(DOUBLE_TAP_SCALE, DOUBLE_TAP_SCALE, e.getX(), e.getY());
                currentScale = DOUBLE_TAP_SCALE;
                clampPan();
                setImageMatrix(matrix);
            }
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            // Forward dismiss-style taps only when un-zoomed; zoomed taps are pan releases.
            if (currentScale <= 1f && onSingleTapListener != null) {
                onSingleTapListener.onSingleTap();
            }
            return true;
        }
    }
}
