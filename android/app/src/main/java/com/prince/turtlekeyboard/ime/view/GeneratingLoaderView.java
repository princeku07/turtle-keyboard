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
    // Four-stop blue: hot core, a short plateau at the peak so the centre
    // reads as a solid blob (not a single-pixel peak), a mid cobalt tail,
    // then transparent. Alphas dialled back from the brighter pass — with
    // SCREEN blending and three overlapping blobs, lower per-blob alpha
    // keeps the merged liquid mass from feeling overpowering.
    private static final int GLOW_HOT  = 0xCC7AA0DE;   // softer hot centre
    private static final int GLOW_MID  = 0xAA3858A0;   // cobalt tail
    private static final int GLOW_RIM  = 0x00000000;   // transparent at the rim
    // Text shimmer is a 5-stop ramp so the reflection reads as light
    // wrapping around the type rather than a hard white sweep:
    //   DIM → HALO (cool blue tint) → PEARL (almost-white peak) → HALO → DIM
    // The HALO stops carry a faint cobalt tint borrowed from the ball, so
    // the text picks up the same hue as its 'light source' just before /
    // after the brightest point — tonally cohesive with the glow.
    private static final int TEXT_DIM   = 0xFF7A7A7A;  // base text shade
    private static final int TEXT_HALO  = 0xFFA8C0E2;  // soft cool tint around the peak
    private static final int TEXT_PEARL = 0xFFE8EEF8;  // cool pearl peak (no pure white)

    private static final int GRAIN_TILE = 96;
    private static final float GRAIN_ALPHA = 0.07f;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Nullable private Bitmap grain;
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
        // Lighter weight + slight tracking — reads as a refined status line
        // rather than a chunky label. Pairs better with the soft gradient.
        message.setTypeface(android.graphics.Typeface.create("sans-serif-light",
                android.graphics.Typeface.NORMAL));
        message.setLetterSpacing(0.04f);
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

    /** The text shimmer is now driven by the ball's horizontal position
     *  (see {@link #onDraw}). We install the gradient shader once and let
     *  each draw frame translate its local matrix so the bright peak lands
     *  underneath wherever the ball currently is — the text reads as 'lit
     *  up' only when the ball is passing overhead. */
    private void startShimmer() {
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
        // 5-stop ramp: dim → cool halo → pearl peak → cool halo → dim.
        // Wider lit zone (0.12 → 0.88) than the previous 0.30/0.70 band so
        // more of the text breathes with the ball, and the halo carries
        // the same cool tint as the glow so the reflection reads as 'lit
        // by the ball' rather than 'a different-coloured stripe'.
        shimmerShader = new LinearGradient(
                0f, 0f, textW, 0f,
                new int[]{ TEXT_DIM, TEXT_HALO, TEXT_PEARL, TEXT_HALO, TEXT_DIM },
                new float[]{ 0.12f, 0.36f, 0.50f, 0.64f, 0.88f },
                Shader.TileMode.CLAMP);
        message.getPaint().setShader(shimmerShader);
        message.invalidate();
    }

    private void stopShimmer() {
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

        // Single large blob with non-harmonic multi-sine drift on both
        // axes. Two sines per axis at different periods means the path
        // never resolves into a clean ellipse — it wanders, so the eye
        // reads it as a random walk rather than an animation loop. The
        // primary horizontal sine carries most of the motion; the
        // smaller secondary sines on each axis perturb the path so the
        // blob doesn't repeat itself.
        float t = (System.currentTimeMillis() - driftStartedAt) / 1000f;

        float cx = w * 0.50f
                + w * 0.22f * (float) Math.sin(t * 0.55f)
                + w * 0.08f * (float) Math.sin(t * 0.31f + 1.7f);
        // cy stays near the top edge so the radial peak lands inside the
        // 56 dp visible strip, with a small wobble for vertical interest.
        float cy = h * 0.10f * (float) Math.sin(t * 0.42f + 0.9f)
                + h * 0.05f * (float) Math.cos(t * 0.27f);
        float r = w * (0.55f + 0.10f * (float) Math.sin(t * 0.40f));

        RadialGradient g = new RadialGradient(cx, cy, r,
                new int[]{ GLOW_HOT, GLOW_HOT, GLOW_MID, GLOW_RIM },
                new float[]{ 0f, 0.22f, 0.55f, 0.70f },
                Shader.TileMode.CLAMP);
        glowPaint.setShader(g);
        c.drawRect(0, 0, w, h, glowPaint);

        // Text shimmer tracks the blob's cx so the lit-letter zone follows
        // wherever the blob currently is.
        if (shimmerShader != null) {
            shimmerMatrix.setTranslate(cx - w * 0.5f, 0f);
            shimmerShader.setLocalMatrix(shimmerMatrix);
            message.invalidate();
        }

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
