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
 * Compact loading panel shown above the keys while a slash-command is
 * awaiting an AI result.
 *
 * <p>Aesthetic — overlapping sinusoidal wave-paths stroked with a cool
 * blue / cyan / mint-green gradient (aurora-style) that flows continuously
 * left → right. The pattern width is wider than the view so no repeat
 * boundary is ever visible on-screen, and the three waves run at different
 * speeds/phases so no full cycle ever repeats cleanly — the surface reads
 * as a genuinely endless flow, not a looping marquee. The centred status
 * line picks up the same palette so the letters appear lit by the same
 * surface underneath, not by a separate shader.
 *
 * <p>Chrome — a 16 dp-radius card with the dark-navy BG fill, no top gap
 * and no hairline stroke (deliberately flatter than the other rounded
 * panels so this reads as a status strip rather than a floating sheet).
 * The card slides up + fades in with the same Material-emphasized easing
 * the other panels use, so the keyboard's "panel rising into view"
 * vocabulary stays consistent across surfaces.
 *
 * <p>Designed as a deliberate continuation of {@link VoiceStageView}'s
 * "brushstroke moving across the surface" language — same cool colour
 * family, but translated from a one-shot arc into a sustained infinite
 * flow.
 */
public class GeneratingLoaderView extends FrameLayout {

    // Very dark navy — barely off-black so the panel reads as distinct,
    // but tonally aligned with the cool palette flowing above.
    private static final int BG = 0xFF05081A;

    // Cool aurora palette — deep blue → cyan → muted peak → mint → teal.
    // Dialled well down from neon so the surface reads as low-luminance
    // ambient flow: the brightest peak here is ~60% luminance, leaving
    // clear headroom above for the (separately-shaded) bright text to
    // sit on top. SCREEN compositing where waves overlap lifts the peaks
    // a touch without ever approaching white.
    private static final int C_DEEP_BLUE = 0xFF101D55;
    private static final int C_BLUE_MID  = 0xFF1E5081;
    private static final int C_CYAN      = 0xFF2A7B91;
    private static final int C_PEAK      = 0xFF6A9C8C; // muted mint-cyan
    private static final int C_MINT      = 0xFF2E8362;
    private static final int C_TEAL      = 0xFF12685A;

    private static final int GRAIN_TILE = 96;
    private static final float GRAIN_ALPHA = 0.04f;

    /** Aurora palette used by every wave. C_DEEP_BLUE at both endpoints so
     *  REPEAT tiling joins seamlessly with no visible boundary between
     *  repeats. Cycle:
     *  deep-blue → mid-blue → cyan → peak → mint → teal → mid-blue → deep-blue. */
    private static final int[] FLOW_COLORS = {
            C_DEEP_BLUE, C_BLUE_MID, C_CYAN, C_PEAK,
            C_MINT, C_TEAL, C_BLUE_MID, C_DEEP_BLUE,
    };
    private static final float[] FLOW_STOPS = {
            0.00f, 0.14f, 0.28f, 0.42f, 0.55f, 0.70f, 0.85f, 1.00f,
    };

    /** Brighter twin of {@link #FLOW_COLORS} for the text shimmer — same
     *  hue sequence, much higher luminance. The text always sits well
     *  above the dim wave peaks below it so the status message is legible
     *  on every frame, while still reading as part of the same flow
     *  (matching hue cycle + same direction + same speed). */
    private static final int[] TEXT_FLOW_COLORS = {
            0xFFB8CCE0, 0xFFD0E0F0, 0xFFE0F0F8, 0xFFFFFFFF,
            0xFFE0F8E8, 0xFFD0E8DC, 0xFFD0E0F0, 0xFFB8CCE0,
    };

    /** Flow speed for the text-shimmer pattern (cycles/sec). Matched to the
     *  dominant wave's flow speed so the bright peak in the text moves in
     *  lock-step with the bright crest of the underlying surface. */
    private static final float SHIMMER_FLOW_SPEED = 0.13f;

    // ── Card chrome ──
    /** Card corner radius. 16 dp matches the keyboard's other panel cards.
     *  No top gap and no hairline stroke on this surface (deliberately
     *  flatter than the other panels) — keeps the loader feeling like a
     *  status strip rather than a floating sheet. */
    private static final int PANEL_RADIUS_DP = 16;
    /** Translate-from offset for the slide-up entrance / slide-down exit. */
    private static final int SLIDE_OFFSET_DP = 28;
    /** Material's "emphasized" easing — organic decelerating settle. */
    private static final Interpolator ENTER_EASING =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f);
    private static final Interpolator EXIT_EASING =
            new AccelerateInterpolator(1.2f);

    /** A single flowing wave — a stroked sine path whose iridescent gradient
     *  pattern translates left → right while the sine phase ripples over
     *  time. The combination of pattern-flow + phase-drift is what reads as
     *  living, flowing fabric instead of a rigid translation. */
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

    // Three waves clustered tightly around the vertical centre of the 56 dp
    // strip. Amplitudes, stroke widths and blur radii are sized so every
    // wave's full halo (baseY ± amp + thickness/2 + blur) stays comfortably
    // inside the strip — no hard cut-off at the top or bottom edge where
    // the blur would otherwise clip. Index 1 is the dominant wave (largest
    // amplitude / thickness) and its flowSpeed is what the text shimmer
    // matches. Phases are offset so crests never line up vertically.
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

    // Per-wave cached gradient + blur — built once on size-change so each
    // frame just retranslates the localMatrix and rebuilds the sine path.
    private final LinearGradient[] waveShaders = new LinearGradient[WAVES.length];
    private final BlurMaskFilter[] waveBlurs = new BlurMaskFilter[WAVES.length];
    private float patternW;

    private TextView message;

    public GeneratingLoaderView(Context c) { super(c); init(); }
    public GeneratingLoaderView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        setVisibility(GONE);
        // Block touches falling through to whatever sits behind while loading.
        setClickable(true);
        setFocusable(true);
        // BlurMaskFilter + SCREEN compositing are most predictable on a
        // software-rendered layer. The loader is a thin strip only on
        // screen briefly, so the perf cost is negligible.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);

        // Rounded-card background — no top gap, no hairline stroke. The
        // GradientDrawable supplies the BG fill and the corner radii;
        // outline + clipToOutline keeps the wave + grain drawing inside
        // the card's rounded shape.
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

        // Each wave is rendered as a stroked path so the iridescent gradient
        // wraps the sine curve like fabric, instead of filling rectangular
        // bands.
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
        // Subtle shadow keeps the bright iridescent text legible against
        // the equally bright flowing surface underneath without resorting
        // to a heavier weight (which would clash with the silk aesthetic).
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

    /** Slide-up + fade-in with the keyboard's shared Material-emphasized
     *  easing. Cancels any in-flight exit so a rapid show after a partial
     *  hide doesn't leave the panel mid-fade. */
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

    /** Mirror: fade out + slide down, then setVisibility(GONE) + teardown.
     *  Wave drift + shimmer keep running through the exit (they look like
     *  the panel is still alive as it leaves), then stop once the panel is
     *  fully out so we don't pay for offscreen invalidates. */
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
        // Text shader uses TEXT_FLOW_COLORS (the brighter twin of the wave
        // palette). Same hue sequence + same direction + same speed reads
        // as belonging to the flow underneath, but the higher luminance
        // keeps the letters legible against the dim wave peaks.
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

    /** Linear ticker drives invalidation only. Wave + shimmer positions are
     *  derived from elapsed wall-clock time in {@link #onDraw}, so the
     *  animation stays smooth even if a frame is dropped. */
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

    /** Advance the text shimmer's gradient matrix once per frame, off the
     *  draw path. Doing it from the drift animator keeps the shimmer in
     *  lock-step with the wave drift but charges only one TextView draw
     *  per frame. */
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
        // Cache the BitmapShader + paint config once — previously this was
        // rebuilt inside onDraw every frame, allocating a new BitmapShader
        // 60×/sec on top of the layout thrash from the slide animation.
        grainShader = new BitmapShader(grain,
                Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        grainPaint.setShader(grainShader);
        grainPaint.setAlpha((int) (255 * GRAIN_ALPHA));
        // patternW is deliberately wider than the view (1.8×) so the
        // repeat seam — where C_DEEP_BLUE meets C_DEEP_BLUE — is always
        // off-screen. Combined with REPEAT tiling, the flow looks
        // genuinely infinite rather than a marquee that loops every w
        // pixels. All waves share this scale so colours align across
        // ribbons; their flowSpeeds differ.
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

        // Background fill comes from the rounded GradientDrawable set via
        // setBackground — we don't paint a fill here. clipToOutline keeps
        // wave + grain drawing inside the card's rounded shape.
        for (int i = 0; i < WAVES.length; i++) {
            drawWave(c, w, h, t, WAVES[i], i);
        }

        if (grainShader != null) {
            c.drawRect(0, 0, w, h, grainPaint);
        }
    }

    /** Build the wave's sine path and stroke it with the iridescent
     *  gradient. The path itself is regenerated each frame because the
     *  sinePhase advances continuously — that's what gives the wave its
     *  living ripple. SCREEN xfermode lets overlapping waves add together
     *  as light, so where multiple crests meet the result clips toward
     *  pearl-white. */
    private void drawWave(Canvas c, int w, int h, float t, Wave wv, int idx) {
        LinearGradient shader = waveShaders[idx];
        BlurMaskFilter blur = waveBlurs[idx];
        if (shader == null || blur == null) return;

        // 48 segments is enough that the polyline reads as a smooth curve
        // after stroking + blur — past ~64 the gain is invisible.
        //
        // Path is extended past the left/right view edges by a margin
        // sized to cover stroke half-width + blur radius. The canvas
        // clips to the view bounds, so the overshoot is invisible — but
        // the visible portion at x = 0 and x = w now has neighbouring
        // path points outside, so the soft halo at the edges tapers
        // naturally instead of being cut off as a hard vertical line.
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
            // Phase uses x relative to the view (not the extended range)
            // so the wave shape at any visible pixel is unchanged — only
            // the off-view continuation is added.
            float angle = (x / w) * wv.freq * twoPi + sinePhase;
            float y = h * wv.y + h * wv.amp * (float) Math.sin(angle);
            if (i == 0) wavePath.moveTo(x, y);
            else wavePath.lineTo(x, y);
        }

        // Translate the iridescent pattern right by cycle×patternW each
        // frame. REPEAT tiling means there's always colour present along
        // the entire wave — no marquee gap, just a continuous flow.
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
