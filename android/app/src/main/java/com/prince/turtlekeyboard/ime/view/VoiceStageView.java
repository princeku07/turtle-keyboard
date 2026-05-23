package com.prince.turtlekeyboard.ime.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import com.prince.turtlekeyboard.theme.KeyboardTheme;

/**
 * Voice dictation overlay: an animated GL gradient behind centred LISTENING…
 * hint and live-transcript text. Non-clickable so key taps fall through; the
 * external Stop button owns dismissal.
 */
public class VoiceStageView extends FrameLayout {

    public interface Listener {
        void onStop();
    }

    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SHADOW  = 0xCC000000;

    private static final String HINT_DEFAULT = "✦  LISTENING…";

    private static final long REVEAL_OPEN_DELAY_MS = 120L;
    private static final long REVEAL_OPEN_MS       = 650L;
    private static final long REVEAL_CLOSE_MS      = 500L;

    private static final long ERROR_LINGER_MS      = 1200L;

    private GlowTexture glow;
    private TextView hint;
    private TextView transcript;
    private Listener listener;

    // 0 = hidden, 1 = fully drawn. Tracked so in-flight reveals can resume from current value.
    private float revealProgress = 0f;
    @Nullable private ValueAnimator revealAnim;

    @Nullable private Runnable errorDismiss;

    public VoiceStageView(Context c) { super(c); init(); }
    public VoiceStageView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        setVisibility(GONE);

        glow = new GlowTexture(getContext());
        addView(glow, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));

        hint = new TextView(getContext());
        hint.setText(HINT_DEFAULT);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setLetterSpacing(0.22f);
        hint.setTypeface(hint.getTypeface(), Typeface.BOLD);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(TEXT_PRIMARY);
        hint.setShadowLayer(dp(6), 0f, dp(1), TEXT_SHADOW);
        LayoutParams hLp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        hLp.gravity = Gravity.CENTER;
        addView(hint, hLp);

        transcript = new TextView(getContext());
        transcript.setText("");
        transcript.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        transcript.setTypeface(transcript.getTypeface(), Typeface.BOLD);
        transcript.setLineSpacing(0, 1.08f);
        transcript.setMaxLines(3);
        transcript.setEllipsize(android.text.TextUtils.TruncateAt.END);
        transcript.setGravity(Gravity.CENTER);
        transcript.setTextColor(TEXT_PRIMARY);
        transcript.setShadowLayer(dp(6), 0f, dp(2), TEXT_SHADOW);
        int padH = dp(24);
        transcript.setPadding(padH, 0, padH, 0);
        LayoutParams tLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        tLp.gravity = Gravity.CENTER;
        addView(transcript, tLp);
    }

    public void setListener(Listener l) { this.listener = l; }

    /** At revealProgress = 0 the shader outputs (0,0,0,0), so showing the view pre-sweep is invisible. */
    public void start(int micWindowX, int micWindowY) {
        cancelErrorDismiss();
        transcript.setText("");
        hint.setText(HINT_DEFAULT);
        hint.setVisibility(VISIBLE);
        glow.setLoudness(0f);

        // Reset to a clean slate so the sweep restarts even if a prior close was interrupted.
        if (revealAnim != null) revealAnim.cancel();
        animate().cancel();
        setAlpha(1f);
        revealProgress = 0f;
        glow.setRevealProgress(0f);

        setVisibility(VISIBLE);
        animateRevealTo(1f, REVEAL_OPEN_MS, REVEAL_OPEN_DELAY_MS, null);
    }

    public void stop() {
        cancelErrorDismiss();
        if (getVisibility() != VISIBLE) {
            setVisibility(GONE);
            return;
        }
        // Parallel alpha-out: the GL clear-colour holds a 0.6-alpha dim that would otherwise snap off.
        animate().alpha(0f).setDuration(REVEAL_CLOSE_MS).start();
        animateRevealTo(2f, REVEAL_CLOSE_MS, 0L, () -> {
            setVisibility(GONE);
            setAlpha(1f);
            revealProgress = 0f;
            glow.setRevealProgress(0f);
            revealAnim = null;
        });
    }

    private void animateRevealTo(float target, long durationMs,
                                 long startDelayMs,
                                 @Nullable Runnable onEnd) {
        if (revealAnim != null) revealAnim.cancel();

        ValueAnimator a = ValueAnimator.ofFloat(revealProgress, target);
        a.setDuration(durationMs);
        if (startDelayMs > 0L) a.setStartDelay(startDelayMs);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.addUpdateListener(an -> {
            revealProgress = (float) an.getAnimatedValue();
            glow.setRevealProgress(revealProgress);
        });
        if (onEnd != null) {
            a.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator anim) { onEnd.run(); }
            });
        }
        revealAnim = a;
        a.start();
    }

    public void setRms(float dB) {
        float n = (dB + 2f) / 12f;
        if (n < 0f) n = 0f;
        if (n > 1f) n = 1f;
        glow.setLoudness(n);
    }

    /** Null/empty shows the hint; otherwise replaces it with the transcript. */
    public void setTranscript(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            transcript.setText("");
            hint.setText(HINT_DEFAULT);
            hint.setVisibility(VISIBLE);
        } else {
            transcript.setText(text);
            hint.setVisibility(GONE);
        }
    }

    /** Shows the error in place of the hint, then auto-closes after a short linger. */
    public void showError(String message) {
        if (getVisibility() != VISIBLE) return;
        cancelErrorDismiss();
        transcript.setText("");
        hint.setText(message);
        hint.setVisibility(VISIBLE);
        errorDismiss = this::stop;
        postDelayed(errorDismiss, ERROR_LINGER_MS);
    }

    private void cancelErrorDismiss() {
        if (errorDismiss != null) {
            removeCallbacks(errorDismiss);
            errorDismiss = null;
        }
    }

    /** No-op; the gradient design has its own fixed palette. */
    public void applyTheme(KeyboardTheme theme) { }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** TextureView wrapping a {@link RenderThread}. Draws into the window surface so siblings compose on top naturally. */
    private static class GlowTexture extends TextureView
            implements TextureView.SurfaceTextureListener {

        @Nullable private RenderThread thread;
        private volatile float loudness;
        private volatile float revealProgress;

        // First-frame ready flag + listener so callers don't see a half-warmed-up GL surface.
        private volatile boolean firstFrameReady;
        @Nullable private Runnable firstFrameListener;

        GlowTexture(Context c) {
            super(c);
            // setOpaque(false) is required for the gradient's alpha to blend with the keyboard.
            setOpaque(false);
            setSurfaceTextureListener(this);
        }

        void setLoudness(float n) {
            loudness = n;
            if (thread != null) thread.setLoudness(n);
        }

        void setRevealProgress(float n) {
            revealProgress = n;
            if (thread != null) thread.setRevealProgress(n);
        }

        /** Runs {@code cb} immediately if the GL surface has already rendered; otherwise on first frame. */
        void runOnFirstFrame(Runnable cb) {
            if (firstFrameReady) {
                cb.run();
            } else {
                firstFrameListener = cb;
            }
        }

        private void onFirstFrameSwapped() {
            firstFrameReady = true;
            Runnable cb = firstFrameListener;
            firstFrameListener = null;
            if (cb != null) cb.run();
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
            firstFrameReady = false;
            thread = new RenderThread(s, w, h,
                    () -> post(this::onFirstFrameSwapped));
            thread.setLoudness(loudness);
            thread.setRevealProgress(revealProgress);
            thread.start();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
            if (thread != null) thread.setSize(w, h);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
            if (thread != null) {
                thread.shutdown();
                try { thread.join(200); } catch (InterruptedException ignored) {}
                thread = null;
            }
            firstFrameReady = false;
            firstFrameListener = null;
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture s) { }
    }

    /** Owns an EGL context bound to the TextureView's SurfaceTexture and draws the gradient at ~60 fps. */
    private static class RenderThread extends Thread {

        private static final String TAG = "VoiceGlowRT";
        private static final String VERT_SRC =
                "attribute vec2 a_position;\n" +
                "varying vec2 v_uv;\n" +
                "void main() {\n" +
                "    v_uv = a_position * 0.5 + 0.5;\n" +
                "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
                "}\n";
        private static final String FRAG_SRC =
                "precision highp float;\n" +
                "varying vec2 v_uv;\n" +
                "uniform vec2  u_resolution;\n" +
                "uniform float u_time;\n" +
                "uniform float u_loudness;\n" +
                "uniform float u_revealProgress;\n" +
                "\n" +
                "float hash(vec2 p) {\n" +
                "    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);\n" +
                "}\n" +
                "\n" +
                "float noise(vec2 p) {\n" +
                "    vec2 i = floor(p);\n" +
                "    vec2 f = fract(p);\n" +
                "    f = f * f * (3.0 - 2.0 * f);\n" +
                "    float a = hash(i);\n" +
                "    float b = hash(i + vec2(1.0, 0.0));\n" +
                "    float c = hash(i + vec2(0.0, 1.0));\n" +
                "    float d = hash(i + vec2(1.0, 1.0));\n" +
                "    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);\n" +
                "}\n" +
                "\n" +
                "float fbm(vec2 p) {\n" +
                "    float v = 0.0;\n" +
                "    float a = 0.5;\n" +
                "    for (int i = 0; i < 6; i++) {\n" +
                "        v += a * noise(p);\n" +
                "        p *= 2.0;\n" +
                "        a *= 0.5;\n" +
                "    }\n" +
                "    return v;\n" +
                "}\n" +
                "\n" +
                "void main() {\n" +
                "    // Normalize so uv.y in [-0.5, 0.5]; uv.x widens with aspect.\n" +
                "    vec2 frag = v_uv * u_resolution;\n" +
                "    vec2 uv = (frag - 0.5 * u_resolution) / u_resolution.y;\n" +
                "\n" +
                "    // Mirror sits a bit below center; small dark gap before reflection.\n" +
                "    float mirrorY = -0.08;\n" +
                "    bool isReflection = uv.y < mirrorY;\n" +
                "    vec2 p = uv;\n" +
                "    if (isReflection) {\n" +
                "        p.y = 2.0 * mirrorY - p.y;\n" +
                "    }\n" +
                "\n" +
                "    // Place the bowl so its bottom arc lands just above the mirror.\n" +
                "    // Offset = radius - 0.03 keeps the same dark gap above mirrorY\n" +
                "    // that the HTML reference had with radius 0.20 / offset 0.17.\n" +
                "    p.y -= 0.32;\n" +
                "\n" +
                "    // Bowl horizontal half-width = 0.35 * aspect (arc spans 70% of\n" +
                "    // keyboard width). xStretch is derived from radius and aspect\n" +
                "    // so radius alone controls vertical extent; the rest of the\n" +
                "    // shader (HTML-source values) stays untouched.\n" +
                "    float aspect = u_resolution.x / u_resolution.y;\n" +
                "    float radius = 0.35;\n" +
                "    float xStretch = radius / (0.35 * aspect);\n" +
                "    vec2 ep = p * vec2(xStretch, 1.0);\n" +
                "    float d = length(ep);\n" +
                "    float ang = atan(ep.y, ep.x);\n" +
                "\n" +
                "    // ---- Arc reveal: a single brush stroke that runs right → bottom\n" +
                "    // → left for BOTH the open and the close, so the close feels like\n" +
                "    // a continuation of the open instead of a rewind.\n" +
                "    //\n" +
                "    //   sweepDist:  0 at right, 0.5 at bowl floor, 1 at left.\n" +
                "    //   progress:   0 → 1 = open sweep (front advances right→left).\n" +
                "    //               1 → 2 = close sweep (back catches up, also r→l).\n" +
                "    //\n" +
                "    // Front and back are both padded to [-0.1, 1.1] so the band edges\n" +
                "    // sit fully OUTSIDE sweepDist's [0, 1] range at progress = 0 and\n" +
                "    // progress = 2 — that gives a clean revealMask = 0 at both ends\n" +
                "    // without any partial-bright flash at the right/left limit.\n" +
                "    // 0.06 smoothstep width on each edge reads as a soft glow rather\n" +
                "    // than a hard wipe.\n" +
                "    float sweepDist = clamp(-ang / 3.1416, 0.0, 1.0);\n" +
                "    float front = min(u_revealProgress,         1.0) * 1.2 - 0.1;\n" +
                "    float back  = max(u_revealProgress - 1.0, 0.0) * 1.2 - 0.1;\n" +
                "    float revealMask = smoothstep(back  - 0.06, back  + 0.06, sweepDist)\n" +
                "                     * (1.0 - smoothstep(front - 0.06,\n" +
                "                                         front + 0.06, sweepDist));\n" +
                "\n" +
                "    // ---- Bowl arc: sharp cyan rim accent at d ≈ radius, masked to\n" +
                "    // the bottom half. This IS the defined arc silhouette — the\n" +
                "    // dense pooled smoke at the bowl's curved bottom rim. Kept as\n" +
                "    // an explicit ring shape (not derived from smoke noise) so\n" +
                "    // the arc reads sharp and continuous, not gappy where the fbm\n" +
                "    // happens to be quiet.\n" +
                "    float thickness = 0.20;\n" +
                "    float ringDist = (d - radius) / thickness;\n" +
                "    float falloffScale = mix(1.2, 3.5, step(0.0, ringDist));\n" +
                "    float ring = exp(-pow(ringDist * falloffScale, 2.0));\n" +
                "\n" +
                "    float bottomness = pow(clamp(-sin(ang), 0.0, 1.0), 1.5);\n" +
                "    float bowlMask = smoothstep(0.10, -0.95, sin(ang));\n" +
                "    float bowl = ring * (bowlMask * 0.35 + bottomness * 0.70) * revealMask;\n" +
                "\n" +
                "    // ---- Wisps rising from the bottom, blown right with wind shear.\n" +
                "    float shear = (p.y + 0.32) * 2.0;\n" +
                "\n" +
                "    vec2 warp = vec2(\n" +
                "        fbm(p * 3.5 + vec2(0.0, u_time * 0.25)) - 0.5,\n" +
                "        fbm(p * 3.5 + vec2(5.7, u_time * 0.25)) - 0.5\n" +
                "    ) * 0.10;\n" +
                "    vec2 pw = p + warp;\n" +
                "\n" +
                "    vec2 wispUv1 = pw * vec2(8.0, 2.8);\n" +
                "    wispUv1.y -= u_time * 0.65;\n" +
                "    wispUv1.x -= u_time * 0.22 + shear;\n" +
                "\n" +
                "    vec2 wispUv2 = pw * vec2(13.0, 4.5) + vec2(13.7, 4.2);\n" +
                "    wispUv2.y -= u_time * 0.95;\n" +
                "    wispUv2.x -= u_time * 0.38 + shear * 1.5;\n" +
                "\n" +
                "    // Smoke shape: raise the lower threshold so only the noise body's\n" +
                "    // brighter peaks survive (less overall volume), but keep the\n" +
                "    // pow-exponent under 1 so the surviving peaks still bloom into\n" +
                "    // soft mid-tone halos — that's what gives the wisps the glow look\n" +
                "    // rather than sharply-bordered streaks.\n" +
                "    float wispsRaw = fbm(wispUv1) * 0.55 + fbm(wispUv2) * 0.45;\n" +
                "    float wisps = smoothstep(0.36, 0.78, wispsRaw);\n" +
                "    wisps = pow(wisps, 0.85);\n" +
                "\n" +
                "    float insideMask = smoothstep(radius + 0.02, radius - 0.30, d);\n" +
                "    float wispVerticalFade = smoothstep(0.35, -0.32, p.y);\n" +
                "\n" +
                "    // Smoke + halo gate: ramps IN during the second half of the\n" +
                "    // open and ramps OUT during the first 70% of the close. The\n" +
                "    // bowl rim leads (revealMask), then smoke/halo fill in.\n" +
                "    float revealGate = smoothstep(0.3, 1.0, u_revealProgress)\n" +
                "                     * (1.0 - smoothstep(1.0, 1.7, u_revealProgress));\n" +
                "    float wispGlow = wisps * insideMask * wispVerticalFade\n" +
                "                   * 2.4 * revealGate;\n" +
                "\n" +
                "    // ---- Soft outer halo, gated by the same ramp.\n" +
                "    float halo = exp(-d * 6.0) * 0.10 * revealGate;\n" +
                "\n" +
                "    // ---- Inner halo: softer ring just inside the cyan rim accent.\n" +
                "    float blueR = radius - 0.035;\n" +
                "    float blueRingDist = (d - blueR) / 0.05;\n" +
                "    float blueInnerRing = exp(-blueRingDist * blueRingDist);\n" +
                "    float blueInnerMask = smoothstep(-0.05, -0.95, sin(ang));\n" +
                "    float blueInnerGlow = blueInnerRing * blueInnerMask * 0.55 * revealMask;\n" +
                "\n" +
                "    // ---- Per-component coloring (aurora theme — matches\n" +
                "    //      GeneratingLoaderView's wave palette).\n" +
                "    //\n" +
                "    //      violetEdge → bright cyan rim accent (bowl bottom edge)\n" +
                "    //      blueBody   → cool teal-blue body / inner halo / halo\n" +
                "    //      lavender   → bright mint-cyan smoke wisps\n" +
                "    vec3 violetEdge = vec3(0.30, 0.87, 1.00);\n" +
                "    vec3 blueBody   = vec3(0.18, 0.55, 0.93);\n" +
                "    vec3 lavender   = vec3(0.55, 0.95, 0.85);\n" +
                "\n" +
                "    vec3 colorBowl = mix(blueBody, violetEdge, bottomness);\n" +
                "\n" +
                "    vec3 color = colorBowl * bowl\n" +
                "               + blueBody  * blueInnerGlow\n" +
                "               + lavender  * wispGlow\n" +
                "               + blueBody  * halo * 1.2;\n" +
                "\n" +
                "    color = min(color, vec3(1.0));\n" +
                "\n" +
                "    // Reflection: smooth fade away from the mirror so the boundary\n" +
                "    // doesn't clip. The naive '* 0.40 * fade' was 1.0 just above the\n" +
                "    // mirror line and 0.40 just below (same bowl content, different\n" +
                "    // multiplier) — a 60% brightness step right at uv.y = mirrorY,\n" +
                "    // which read as a visible horizontal clip between the bowl and\n" +
                "    // its reflection.\n" +
                "    //\n" +
                "    // Now the dim coefficient itself ramps:\n" +
                "    //   uv.y == mirrorY            → multiplier = 1.0  (matches bowl)\n" +
                "    //   uv.y ~ mirrorY - 0.08      → multiplier = 0.40 (reflection dim)\n" +
                "    //   uv.y →  -0.50              → multiplier = 0    (faded out)\n" +
                "    if (isReflection) {\n" +
                "        float boundary = smoothstep(mirrorY - 0.08, mirrorY, uv.y);\n" +
                "        float fade     = smoothstep(-0.50, -0.15, uv.y);\n" +
                "        color *= mix(0.40, 1.0, boundary) * fade;\n" +
                "    }\n" +
                "\n" +
                "    // (Loudness no longer modulates brightness — the bowl stays at\n" +
                "    // a steady glow regardless of mic level. u_loudness is kept on\n" +
                "    // the uniform list so the Java plumbing doesn't need rewiring.)\n" +
                "\n" +
                "    // Premultiplied alpha (GL_ONE / GL_ONE_MINUS_SRC_ALPHA): the\n" +
                "    // alpha tracks the brightest channel so the rim and smoke\n" +
                "    // composite cleanly over the keyboard, with no dim backdrop.\n" +
                "    float alpha = clamp(max(max(color.r, color.g), color.b), 0.0, 1.0);\n" +
                "    gl_FragColor = vec4(color, alpha);\n" +
                "}\n";

        private static final float[] QUAD = {
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f
        };

        private final SurfaceTexture surfaceTexture;
        private final Runnable firstFrameSignal;
        private volatile int width, height;
        private volatile boolean shutdown;
        private volatile float loudness;
        private volatile float revealProgress;
        private boolean firstFrameSent;

        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface surface = EGL14.EGL_NO_SURFACE;

        private int program;
        private int aPos, uTime, uLoud, uRes, uReveal;
        private FloatBuffer vb;
        private long startedAt;

        RenderThread(SurfaceTexture s, int w, int h, Runnable firstFrameSignal) {
            super("VoiceGlowRenderer");
            this.surfaceTexture = s;
            this.width = w;
            this.height = h;
            this.firstFrameSignal = firstFrameSignal;
        }

        void setLoudness(float n) { loudness = n; }
        void setRevealProgress(float n) { revealProgress = n; }
        void setSize(int w, int h) { width = w; height = h; }
        void shutdown() { shutdown = true; }

        @Override
        public void run() {
            try {
                if (!initEGL()) return;
                if (!initGL())  return;
                startedAt = System.currentTimeMillis();
                while (!shutdown) {
                    drawFrame();
                    try { Thread.sleep(16); }
                    catch (InterruptedException e) { break; }
                }
            } catch (Throwable t) {
                Log.e(TAG, "render thread crashed", t);
            } finally {
                releaseEGL();
            }
        }

        private boolean initEGL() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "eglGetDisplay failed");
                return false;
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                Log.e(TAG, "eglInitialize failed");
                return false;
            }
            int[] cfgAttrs = {
                    EGL14.EGL_RED_SIZE,        8,
                    EGL14.EGL_GREEN_SIZE,      8,
                    EGL14.EGL_BLUE_SIZE,       8,
                    EGL14.EGL_ALPHA_SIZE,      8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] cfgs = new EGLConfig[1];
            int[] nc = new int[1];
            if (!EGL14.eglChooseConfig(display, cfgAttrs, 0, cfgs, 0, 1, nc, 0)
                    || nc[0] == 0) {
                Log.e(TAG, "eglChooseConfig failed");
                return false;
            }
            int[] ctxAttrs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            context = EGL14.eglCreateContext(display, cfgs[0],
                    EGL14.EGL_NO_CONTEXT, ctxAttrs, 0);
            if (context == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "eglCreateContext failed");
                return false;
            }
            int[] sAttrs = { EGL14.EGL_NONE };
            surface = EGL14.eglCreateWindowSurface(display, cfgs[0],
                    surfaceTexture, sAttrs, 0);
            if (surface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface failed");
                return false;
            }
            return EGL14.eglMakeCurrent(display, surface, surface, context);
        }

        private boolean initGL() {
            program = createProgram(VERT_SRC, FRAG_SRC);
            if (program == 0) return false;

            aPos   = GLES20.glGetAttribLocation (program, "a_position");
            uTime  = GLES20.glGetUniformLocation(program, "u_time");
            uLoud  = GLES20.glGetUniformLocation(program, "u_loudness");
            uRes   = GLES20.glGetUniformLocation(program, "u_resolution");
            uReveal = GLES20.glGetUniformLocation(program, "u_revealProgress");

            vb = ByteBuffer.allocateDirect(QUAD.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            vb.put(QUAD).position(0);

            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            // Premultiplied-alpha blend: shader outputs (rgb * alpha, alpha).
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            return true;
        }

        private void drawFrame() {
            int w = width, h = height;
            if (w == 0 || h == 0) return;

            GLES20.glViewport(0, 0, w, h);
            GLES20.glClearColor(0f, 0f, 0f, 0.6f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            float t = (System.currentTimeMillis() - startedAt) / 1000f;
            GLES20.glUseProgram(program);
            GLES20.glUniform1f(uTime, t);
            GLES20.glUniform1f(uLoud, loudness);
            GLES20.glUniform2f(uRes, (float) w, (float) h);
            GLES20.glUniform1f(uReveal, revealProgress);

            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vb);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPos);

            EGL14.eglSwapBuffers(display, surface);

            // Notify the main thread once pixels exist so the reveal doesn't animate over the clear-colour dim.
            if (!firstFrameSent) {
                firstFrameSent = true;
                firstFrameSignal.run();
            }
        }

        private void releaseEGL() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface);
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context);
                }
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(display);
            }
            display = EGL14.EGL_NO_DISPLAY;
            context = EGL14.EGL_NO_CONTEXT;
            surface = EGL14.EGL_NO_SURFACE;
        }

        private static int compileShader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] status = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(s));
                GLES20.glDeleteShader(s);
                return 0;
            }
            return s;
        }

        private static int createProgram(String vs, String fs) {
            int v = compileShader(GLES20.GL_VERTEX_SHADER,   vs);
            int f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs);
            if (v == 0 || f == 0) return 0;
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            int[] status = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(p));
                GLES20.glDeleteProgram(p);
                return 0;
            }
            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
            return p;
        }
    }
}
