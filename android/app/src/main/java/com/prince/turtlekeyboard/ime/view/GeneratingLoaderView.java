package com.prince.turtlekeyboard.ime.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Random;

/**
 * Compact dark loading panel shown above the keys while a slash-command is
 * awaiting an AI result (replaces the thin {@link ShimmerView} line).
 *
 * <p>Aesthetic — black surface with a soft off-centre radial glow tinted blue,
 * grainy film overlay, centred status text that gently pulses. Matches the
 * voice stage / prompt panel direction without the cost of a {@link
 * android.opengl.GLSurfaceView}: a simple {@link RadialGradient} + tiled noise
 * is enough for a static-feeling backdrop.
 */
public class GeneratingLoaderView extends FrameLayout {

    private static final int BG = 0xFF000000;
    private static final int GLOW_CORE = 0xCC2F5AA8;   // brighter blue core so the drift reads
    private static final int GLOW_RIM = 0x00000000;    // fully transparent at the rim
    private static final int TEXT_DIM = 0xFF6A6A6A;    // base text shade
    private static final int TEXT_BRIGHT = 0xFFFFFFFF; // shimmer peak

    private static final int GRAIN_TILE = 96;
    private static final float GRAIN_ALPHA = 0.07f;
    private static final long SHIMMER_CYCLE_MS = 1500L;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Nullable private Bitmap grain;
    @Nullable private ValueAnimator shimmer;
    @Nullable private ValueAnimator drift;
    @Nullable private LinearGradient shimmerShader;
    private final Matrix shimmerMatrix = new Matrix();
    private long driftStartedAt;

    private TextView message;

    public GeneratingLoaderView(Context c) { super(c); init(); }
    public GeneratingLoaderView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        setVisibility(GONE);
        setBackgroundColor(BG);
        setWillNotDraw(false);
        // Block touches falling through to whatever sits behind while loading.
        setClickable(true);
        setFocusable(true);

        message = new TextView(getContext());
        message.setText("Generating image…");
        message.setTextColor(TEXT_DIM);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        message.setGravity(Gravity.CENTER);
        message.setIncludeFontPadding(false);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        addView(message, lp);
    }

    public void show(@Nullable String text) {
        message.setText(text == null || text.isEmpty() ? "Generating image…" : text);
        setVisibility(VISIBLE);
        startShimmer();
        startDrift();
    }

    public void hide() {
        setVisibility(GONE);
        stopShimmer();
        stopDrift();
    }

    /** Classic text shimmer — a bright peak sweeps across the message left-to-
     *  right. The base text colour is dimmed; a {@link LinearGradient} painted
     *  through the TextView's paint draws the brighter "shine" wherever the
     *  shader's local matrix currently lands. */
    private void startShimmer() {
        if (shimmer != null && shimmer.isRunning()) return;
        // Defer until the TextView has laid out (we need its measured width
        // to size the gradient).
        message.post(this::installShimmerShader);
    }

    private void installShimmerShader() {
        int textW = message.getWidth();
        if (textW <= 0) {
            // Layout not ready yet — try again on the next frame.
            message.post(this::installShimmerShader);
            return;
        }
        // Gradient spans roughly twice the text width with the bright peak in
        // the middle. Translating its matrix makes the peak slide through.
        shimmerShader = new LinearGradient(
                0f, 0f, textW, 0f,
                new int[]{ TEXT_DIM, TEXT_BRIGHT, TEXT_DIM },
                new float[]{ 0.30f, 0.50f, 0.70f },
                Shader.TileMode.CLAMP);
        message.getPaint().setShader(shimmerShader);

        shimmer = ValueAnimator.ofFloat(-1.2f, 1.2f);
        shimmer.setDuration(SHIMMER_CYCLE_MS);
        shimmer.setRepeatCount(ValueAnimator.INFINITE);
        shimmer.setInterpolator(new LinearInterpolator());
        shimmer.addUpdateListener(a -> {
            float fraction = (float) a.getAnimatedValue();
            shimmerMatrix.setTranslate(textW * fraction, 0f);
            if (shimmerShader != null) shimmerShader.setLocalMatrix(shimmerMatrix);
            message.invalidate();
        });
        shimmer.start();
    }

    private void stopShimmer() {
        if (shimmer != null) { shimmer.cancel(); shimmer = null; }
        message.getPaint().setShader(null);
        shimmerShader = null;
        message.invalidate();
    }

    /** Slow ticker that nudges the gradient centre on a Lissajous-style orbit
     *  so the glow drifts while loading. Same idea as the voice blob, just
     *  much subtler — single sub-surface, gentle amplitude. */
    private void startDrift() {
        driftStartedAt = System.currentTimeMillis();
        if (drift != null && drift.isRunning()) return;
        drift = ValueAnimator.ofFloat(0f, 1f);
        drift.setDuration(60_000L);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setInterpolator(new LinearInterpolator());
        drift.addUpdateListener(a -> invalidate());
        drift.start();
    }

    private void stopDrift() {
        if (drift != null) { drift.cancel(); drift = null; }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopShimmer();
        stopDrift();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Grain is pre-baked once per size change; cheap to redo if the
        // panel ever gets a different geometry.
        if (w > 0 && h > 0) grain = makeGrainTile(GRAIN_TILE);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // Soft off-centre radial glow biased toward the upper-left so the
        // panel doesn't read as a symmetric "spotlight". Fades fully to the
        // black surface at the rim — no hard edges. While loading, the centre
        // and radius drift on independent low-frequency sines so the glow
        // breathes rather than sits still.
        float t = (System.currentTimeMillis() - driftStartedAt) / 1000f;
        // Larger amplitudes + faster periods so the gradient drift reads at a
        // glance instead of feeling like a slow background tide.
        float ampX = w * 0.38f;
        float ampY = h * 0.55f;
        float cx = w * 0.40f + ampX * (float) Math.sin(t * 1.10f);
        float cy = h * 0.50f + ampY * (float) Math.cos(t * 0.85f + 0.6f);
        float r = (float) Math.hypot(w, h) * (0.55f + 0.18f * (float) Math.sin(t * 0.70f));
        RadialGradient g = new RadialGradient(cx, cy, r,
                new int[]{ GLOW_CORE, GLOW_RIM },
                new float[]{ 0f, 1f },
                Shader.TileMode.CLAMP);
        glowPaint.setShader(g);
        c.drawRect(0, 0, w, h, glowPaint);

        if (grain != null) {
            BitmapShader bs = new BitmapShader(grain,
                    Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            grainPaint.setShader(bs);
            grainPaint.setAlpha((int) (255 * GRAIN_ALPHA));
            c.drawRect(0, 0, w, h, grainPaint);
        }
    }

    private Bitmap makeGrainTile(int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[size * size];
        Random rng = new Random(0xDEC0DEL);
        for (int i = 0; i < pixels.length; i++) {
            int n = rng.nextInt(256);
            int alpha = (n < 20) ? 90 : (n > 235) ? 110 : 0;
            int gray = (n < 128) ? 0x00 : 0xFF;
            pixels[i] = (alpha << 24) | (gray << 16) | (gray << 8) | gray;
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size);
        return bmp;
    }
}
