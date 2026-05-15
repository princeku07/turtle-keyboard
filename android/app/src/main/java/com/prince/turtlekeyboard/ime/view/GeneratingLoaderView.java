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
    // Three-stop blue: a hot lighter-blue centre, a mid cobalt tail, then
    // fully transparent so it fades into the black surface. Single-stop
    // gradients flattened out in the visible band — the inner stop is what
    // gives the panel its visible "spotlight" punch.
    private static final int GLOW_HOT  = 0xFF7AA0E0;   // hot bright centre
    private static final int GLOW_MID  = 0xCC3A5896;   // long blue tail
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
        // Narrow band gradient: outside the [0.30, 0.70] range the shader
        // clamps to TEXT_DIM, so the text only brightens when the ball's
        // x-position pulls the peak into the text's bounds.
        shimmerShader = new LinearGradient(
                0f, 0f, textW, 0f,
                new int[]{ TEXT_DIM, TEXT_BRIGHT, TEXT_DIM },
                new float[]{ 0.30f, 0.50f, 0.70f },
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

        // Centre lives at the TOP edge so the hot core lands inside the
        // visible band — at 56 dp tall, putting it in the middle hid most
        // of the gradient's structure. With cy at the top, the bright peak
        // is right at y=0 and the full radial falloff plays through the
        // strip downward. Drift is X-only so the hot spot sweeps across.
        float t = (System.currentTimeMillis() - driftStartedAt) / 1000f;
        float ampX = w * 0.22f;
        float cx = w * 0.42f + ampX * (float) Math.sin(t * 0.55f);
        float cy = 0f;
        float r = w * (0.75f + 0.10f * (float) Math.sin(t * 0.40f));
        RadialGradient g = new RadialGradient(cx, cy, r,
                new int[]{ GLOW_HOT, GLOW_MID, GLOW_RIM },
                new float[]{ 0f, 0.35f, 1f },
                Shader.TileMode.CLAMP);
        glowPaint.setShader(g);
        c.drawRect(0, 0, w, h, glowPaint);

        // Drive the text shimmer from the ball's cx. The text sits centred
        // at w/2, so translating its gradient by (cx - w/2) puts the bright
        // peak directly underneath wherever the ball currently is. When the
        // ball drifts off the text, the shader clamps to TEXT_DIM and the
        // text dims back down — so the text "reacts" to the ball passing
        // overhead instead of running an independent shimmer cycle.
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
