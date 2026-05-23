package com.prince.turtlekeyboard.ime.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.theme.KeyboardTheme;

/**
 * Banner-row shown while voice dictation is active: an RMS-driven bar wave on
 * the left and the in-flight transcript on the right.
 */
public class VoiceListeningView extends LinearLayout {

    private BarsView bars;
    private TextView label;

    public VoiceListeningView(Context c) { super(c); init(); }
    public VoiceListeningView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(12);
        int padV = dp(8);
        setPadding(padH, padV, padH, padV);
        setVisibility(GONE);

        bars = new BarsView(getContext());
        LayoutParams bp = new LayoutParams(dp(48), dp(22));
        bp.rightMargin = dp(10);
        addView(bars, bp);

        label = new TextView(getContext());
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setText("Listening…");
        LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        addView(label, lp);
    }

    public void start() {
        label.setText("Listening…");
        setVisibility(VISIBLE);
        bars.start();
    }

    public void stop() {
        bars.stop();
        setVisibility(GONE);
    }

    /** Null/empty reverts to "Listening…". */
    public void setTranscript(@Nullable String text) {
        label.setText(text == null || text.isEmpty() ? "Listening…" : text);
    }

    public void setRms(float dB) { bars.setRms(dB); }

    public void applyTheme(KeyboardTheme theme) {
        setBackgroundColor(theme.bannerBg);
        label.setTextColor(theme.bannerText);
        bars.setColor(theme.accent);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class BarsView extends View {

        private static final int BAR_COUNT = 5;
        private static final long TICK_MS = 16L;
        private static final float MIN_FRAC = 0.18f;
        private static final float MAX_FRAC = 1.00f;
        private static final float IDLE_AMP = 0.22f;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private ValueAnimator ticker;
        private long startedAt;
        private int color = 0xFF15803D;

        private float loudness = 0f;

        BarsView(Context c) { super(c); }

        void setColor(int c) { this.color = c; invalidate(); }

        void start() {
            startedAt = System.currentTimeMillis();
            loudness = 0f;
            if (ticker != null && ticker.isRunning()) return;
            ticker = ValueAnimator.ofFloat(0f, 1f);
            ticker.setDuration(60_000L);
            ticker.setRepeatCount(ValueAnimator.INFINITE);
            ticker.setInterpolator(new LinearInterpolator());
            ticker.addUpdateListener(a -> {
                // Decay loudness so bars settle when speech pauses.
                loudness *= 0.92f;
                invalidate();
            });
            ticker.start();
        }

        void stop() {
            if (ticker != null) { ticker.cancel(); ticker = null; }
            loudness = 0f;
            invalidate();
        }

        // SpeechRecognizer RMS is roughly -2..10 dB in practice.
        void setRms(float dB) {
            float normalized = (dB + 2f) / 12f;
            if (normalized < 0f) normalized = 0f;
            if (normalized > 1f) normalized = 1f;
            // Max-on-rise + decay-on-tick gives a springy attack with smooth fall-off.
            if (normalized > loudness) loudness = normalized;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;

            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);

            float gap = w * 0.10f;
            float barW = (w - gap * (BAR_COUNT - 1)) / BAR_COUNT;
            float radius = barW * 0.4f;
            float t = (System.currentTimeMillis() - startedAt) / 1000f;

            for (int i = 0; i < BAR_COUNT; i++) {
                float phase = i * 0.9f;
                float idle = IDLE_AMP * (0.5f + 0.5f * (float) Math.sin(t * 5.0 + phase));
                float frac = MIN_FRAC + (MAX_FRAC - MIN_FRAC)
                        * Math.min(1f, idle + loudness * (0.6f + 0.4f * (float) Math.sin(t * 9.0 + phase)));
                float bh = h * frac;
                float left = i * (barW + gap);
                float top = (h - bh) / 2f;
                rect.set(left, top, left + barW, top + bh);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (ticker != null) { ticker.cancel(); ticker = null; }
        }
    }
}
