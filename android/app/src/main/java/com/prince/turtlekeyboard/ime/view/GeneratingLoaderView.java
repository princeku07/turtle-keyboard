package com.prince.turtlekeyboard.ime.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Random;

/**
 * Compact loading strip shown above the keys while a slash-command awaits an
 * AI result. Renders three overlapping sinusoidal waves stroked with an aurora
 * gradient that flows continuously, with a matching shimmer on the status text.
 */
public class GeneratingLoaderView extends FrameLayout {

    private static final int BG = 0xFF05081A;

    private static final int C_DEEP_BLUE = 0xFF101D55;
    private static final int C_BLUE_MID  = 0xFF1E5081;
    private static final int C_CYAN      = 0xFF2A7B91;
    private static final int C_PEAK      = 0xFF6A9C8C;
    private static final int C_MINT      = 0xFF2E8362;
    private static final int C_TEAL      = 0xFF12685A;

    private static final int GRAIN_TILE = 96;
    private static final float GRAIN_ALPHA = 0.04f;

    // C_DEEP_BLUE at both endpoints so REPEAT tiling joins seamlessly.
    private static final int[] FLOW_COLORS = {
            C_DEEP_BLUE, C_BLUE_MID, C_CYAN, C_PEAK,
            C_MINT, C_TEAL, C_BLUE_MID, C_DEEP_BLUE,
    };
    private static final float[] FLOW_STOPS = {
            0.00f, 0.14f, 0.28f, 0.42f, 0.55f, 0.70f, 0.85f, 1.00f,
    };

    // Brighter twin of FLOW_COLORS — same hues, higher luminance for text legibility.
    private static final int[] TEXT_FLOW_COLORS = {
            0xFFB8CCE0, 0xFFD0E0F0, 0xFFE0F0F8, 0xFFFFFFFF,
            0xFFE0F8E8, 0xFFD0E8DC, 0xFFD0E0F0, 0xFFB8CCE0,
    };

    // Matched to the dominant wave's flowSpeed so the bright peak in text tracks the wave crest.
    private static final float SHIMMER_FLOW_SPEED = 0.13f;

    private static final int PANEL_RADIUS_DP = 16;
    private static final int SLIDE_OFFSET_DP = 28;
    private static final Interpolator ENTER_EASING =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f);
    private static final Interpolator EXIT_EASING =
            new AccelerateInterpolator(1.2f);

    private static final class Wave {
        final float y;            // base y as fraction of view height
        final float amp;          // sine amplitude as fraction of view height
        final float freq;         // sine cycles per view width
        final float waveSpeed;    // radians/sec for sine phase drift
        final float flowSpeed;    // cycles/sec for gradient pattern translation
        final float phase;        // 0..1 initial offset
        final float thicknessDp;  // stroke width
        final float blurDp;       // blur radius
        Wave(float y, float amp, float freq, float waveSpeed, float flowSpeed,
             float phase, float thicknessDp, float blurDp) {
            this.y = y; this.amp = amp; this.freq = freq;
            this.waveSpeed = waveSpeed; this.flowSpeed = flowSpeed;
            this.phase = phase; this.thicknessDp = thicknessDp;
            this.blurDp = blurDp;
        }
    }

    // Index 1 is the dominant wave; its flowSpeed is mirrored by the text shimmer.
    private static final Wave[] WAVES = {
            //       y      amp    freq   waveS  flowS  phase  thick  blur
            new Wave(0.42f, 0.12f, 1.2f,  0.50f, 0.16f, 0.00f,  14f,   5f),
            new Wave(0.50f, 0.16f, 1.0f,  0.45f, 0.13f, 0.30f,  18f,   7f),
            new Wave(0.58f, 0.10f, 1.5f,  0.60f, 0.20f, 0.55f,  12f,   4f),
    };

    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PorterDuffXfermode SCREEN_MODE =
            new PorterDuffXfermode(PorterDuff.Mode.SCREEN);
    private final Matrix flowMatrix = new Matrix();
    private final Path wavePath = new Path();

    @Nullable private Bitmap grain;
    @Nullable private BitmapShader grainShader;
    @Nullable private ValueAnimator drift;
    @Nullable private LinearGradient shimmerShader;

    private final Matrix shimmerMatrix = new Matrix();
    private long driftStartedAt;
    private float density = 1f;

    private final LinearGradient[] waveShaders = new LinearGradient[WAVES.length];
    private final BlurMaskFilter[] waveBlurs = new BlurMaskFilter[WAVES.length];
    private float patternW;

    private TextView message;

    public GeneratingLoaderView(Context c) { super(c); init(); }
    public GeneratingLoaderView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        setVisibility(GONE);
        // Block touches from reaching whatever sits behind.
        setClickable(true);
        setFocusable(true);
        // BlurMaskFilter + SCREEN xfermode behave predictably on a software layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);

        final int radius = dp(PANEL_RADIUS_DP);
        GradientDrawable card = new GradientDrawable();
        card.setShape(GradientDrawable.RECTANGLE);
        card.setColor(BG);
        card.setCornerRadius(radius);
        setBackground(card);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);

        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);
        wavePaint.setStrokeJoin(Paint.Join.ROUND);

        message = new TextView(getContext());
        message.setText("Generating image…");
        message.setTextColor(C_PEAK);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        message.setGravity(Gravity.CENTER);
        message.setIncludeFontPadding(false);
        message.setTypeface(android.graphics.Typeface.create("sans-serif-light",
                android.graphics.Typeface.NORMAL));
        message.setLetterSpacing(0.04f);
        message.setShadowLayer(6f * density, 0f, 1f * density, 0xCC000000);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        addView(message, lp);
    }

    public void show(@Nullable String text) {
        message.setText(text == null || text.isEmpty() ? "Generating image…" : text);
        startShimmer();
        startDrift();
        animateIn();
    }

    public void hide() {
        animateOut();
    }

    private void animateIn() {
        animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(0f);
        setTranslationY(dp(SLIDE_OFFSET_DP));
        animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320)
                .setInterpolator(ENTER_EASING)
                .start();
    }

    private void animateOut() {
        animate().cancel();
        if (getVisibility() != VISIBLE) {
            stopShimmer();
            stopDrift();
            return;
        }
        animate()
                .alpha(0f)
                .translationY(dp(SLIDE_OFFSET_DP))
                .setDuration(220)
                .setInterpolator(EXIT_EASING)
                .withEndAction(() -> {
                    setVisibility(GONE);
                    setAlpha(1f);
                    setTranslationY(0f);
                    stopShimmer();
                    stopDrift();
                })
                .start();
    }

    private void startShimmer() {
        message.post(this::installShimmerShader);
    }

    private void installShimmerShader() {
        int textW = message.getWidth();
        if (textW <= 0) {
            message.post(this::installShimmerShader);
            return;
        }
        float textPatternW = textW * 1.8f;
        shimmerShader = new LinearGradient(
                0f, 0f, textPatternW, 0f,
                TEXT_FLOW_COLORS, FLOW_STOPS,
                Shader.TileMode.REPEAT);
        message.getPaint().setShader(shimmerShader);
        message.invalidate();
    }

    private void stopShimmer() {
        message.getPaint().setShader(null);
        shimmerShader = null;
        message.invalidate();
    }

    /** Drives invalidation only; wave + shimmer positions come from wall-clock time. */
    private void startDrift() {
        driftStartedAt = System.currentTimeMillis();
        if (drift != null && drift.isRunning()) return;
        drift = ValueAnimator.ofFloat(0f, 1f);
        drift.setDuration(60_000L);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setInterpolator(new LinearInterpolator());
        drift.addUpdateListener(a -> {
            updateShimmerMatrix();
            invalidate();
        });
        drift.start();
    }

    private void updateShimmerMatrix() {
        if (shimmerShader == null) return;
        int textW = message.getWidth();
        if (textW <= 0) return;
        float t = (System.currentTimeMillis() - driftStartedAt) / 1000f;
        float textPatternW = textW * 1.8f;
        float cycle = (t * SHIMMER_FLOW_SPEED) % 1f;
        shimmerMatrix.setTranslate(cycle * textPatternW, 0f);
        shimmerShader.setLocalMatrix(shimmerMatrix);
        message.invalidate();
    }

    private void stopDrift() {
        if (drift != null) { drift.cancel(); drift = null; }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        animate().cancel();
        setAlpha(1f);
        setTranslationY(0f);
        stopShimmer();
        stopDrift();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || w == oldw) return;
        grain = makeGrainTile(GRAIN_TILE);
        grainShader = new BitmapShader(grain,
                Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        grainPaint.setShader(grainShader);
        grainPaint.setAlpha((int) (255 * GRAIN_ALPHA));
        // 1.8× so the REPEAT seam (C_DEEP_BLUE meets C_DEEP_BLUE) stays off-screen.
        patternW = w * 1.8f;
        for (int i = 0; i < WAVES.length; i++) {
            Wave wv = WAVES[i];
            waveShaders[i] = new LinearGradient(
                    0f, 0f, patternW, 0f,
                    FLOW_COLORS, FLOW_STOPS,
                    Shader.TileMode.REPEAT);
            waveBlurs[i] = new BlurMaskFilter(
                    wv.blurDp * density, BlurMaskFilter.Blur.NORMAL);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        float t = (System.currentTimeMillis() - driftStartedAt) / 1000f;

        for (int i = 0; i < WAVES.length; i++) {
            drawWave(c, w, h, t, WAVES[i], i);
        }

        if (grainShader != null) {
            c.drawRect(0, 0, w, h, grainPaint);
        }
    }

    private void drawWave(Canvas c, int w, int h, float t, Wave wv, int idx) {
        LinearGradient shader = waveShaders[idx];
        BlurMaskFilter blur = waveBlurs[idx];
        if (shader == null || blur == null) return;

        // Extend path past view edges so the blurred halo tapers naturally instead of cutting off.
        wavePath.rewind();
        final int N = 48;
        final float twoPi = (float) (Math.PI * 2.0);
        float sinePhase = wv.phase * twoPi + t * wv.waveSpeed;
        float margin = (wv.thicknessDp * 0.5f + wv.blurDp + 4f) * density;
        float startX = -margin;
        float endX = w + margin;
        float spanX = endX - startX;
        for (int i = 0; i <= N; i++) {
            float fx = i / (float) N;
            float x = startX + fx * spanX;
            float angle = (x / w) * wv.freq * twoPi + sinePhase;
            float y = h * wv.y + h * wv.amp * (float) Math.sin(angle);
            if (i == 0) wavePath.moveTo(x, y);
            else wavePath.lineTo(x, y);
        }

        float cycle = (t * wv.flowSpeed + wv.phase) % 1f;
        flowMatrix.setTranslate(cycle * patternW, 0f);
        shader.setLocalMatrix(flowMatrix);

        wavePaint.setShader(shader);
        wavePaint.setMaskFilter(blur);
        wavePaint.setStrokeWidth(wv.thicknessDp * density);
        wavePaint.setXfermode(SCREEN_MODE);
        c.drawPath(wavePath, wavePaint);
        wavePaint.setXfermode(null);
        wavePaint.setMaskFilter(null);
        wavePaint.setShader(null);
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

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
