package com.prince.turtlekeyboard.ime.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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
import android.view.ViewAnimationUtils;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import com.prince.turtlekeyboard.theme.KeyboardTheme;

/**
 * Voice dictation overlay.
 *
 * <p>Composition (back → front, all children of a {@link FrameLayout}):
 * <ol>
 *   <li>{@link GlowTexture} — a {@link TextureView} that renders an animated
 *       GL gradient. TextureView draws into the window surface like any
 *       regular view, so siblings layered after it compose naturally on
 *       top — no z-order tricks, no texture-baking of text.</li>
 *   <li>"LISTENING…" hint — plain {@link TextView}, centred.</li>
 *   <li>Live transcript — plain {@link TextView}, centred, replaces the hint
 *       as partials arrive.</li>
 * </ol>
 *
 * <p>The stage is non-clickable: key taps pass through to the keyboard
 * underneath. Dismissal is owned by the external Stop button.
 *
 * <p>Open/close use a circular reveal anchored at the mic button's window
 * centre — pass those coordinates to {@link #start(int,int)}.
 */
public class VoiceStageView extends FrameLayout {

    public interface Listener {
        /** External Stop button was pressed — commit the current transcript. */
        void onStop();
    }

    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SHADOW  = 0xCC000000;

    private static final long REVEAL_OPEN_MS  = 380L;
    private static final long REVEAL_CLOSE_MS = 280L;

    private GlowTexture glow;
    private TextView hint;
    private TextView transcript;
    private Listener listener;

    // Reveal-animation source point (mic centre) in this view's local coords.
    private int revealCx = -1, revealCy = -1;
    @Nullable private Animator revealAnim;

    public VoiceStageView(Context c) { super(c); init(); }
    public VoiceStageView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        setVisibility(GONE);

        // ── 1) GL gradient background.
        glow = new GlowTexture(getContext());
        addView(glow, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));

        // ── 2) Hint — shown until the first partial transcript arrives.
        hint = new TextView(getContext());
        hint.setText("✦  LISTENING…");
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

        // ── 3) Live transcript — replaces the hint as partials arrive.
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

    /** Show the stage with a circular reveal from the mic button's window
     *  centre. Local coords are resolved inside {@code post()} once layout
     *  has settled. */
    public void start(int micWindowX, int micWindowY) {
        transcript.setText("");
        hint.setVisibility(VISIBLE);
        setVisibility(VISIBLE);
        glow.setLoudness(0f);
        post(() -> {
            int[] loc = new int[2];
            getLocationInWindow(loc);
            revealCx = micWindowX - loc[0];
            revealCy = micWindowY - loc[1];
            runOpenReveal();
        });
    }

    private void runOpenReveal() {
        if (getWidth() == 0 || getHeight() == 0) return;
        cancelRevealAnim();
        float endR = maxRadiusFrom(revealCx, revealCy);
        Animator a = ViewAnimationUtils.createCircularReveal(
                this, revealCx, revealCy, 0f, endR);
        a.setDuration(REVEAL_OPEN_MS);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        revealAnim = a;
        a.start();
    }

    /** Collapse the stage back into the source point of the last {@link
     *  #start(int,int)} call, then hide it. */
    public void stop() {
        cancelRevealAnim();
        if (getVisibility() != VISIBLE
                || getWidth() == 0 || getHeight() == 0
                || revealCx < 0 || revealCy < 0) {
            setVisibility(GONE);
            return;
        }
        float startR = maxRadiusFrom(revealCx, revealCy);
        Animator a = ViewAnimationUtils.createCircularReveal(
                this, revealCx, revealCy, startR, 0f);
        a.setDuration(REVEAL_CLOSE_MS);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator anim) {
                setVisibility(GONE);
                revealAnim = null;
            }
        });
        revealAnim = a;
        a.start();
    }

    private void cancelRevealAnim() {
        if (revealAnim != null) {
            revealAnim.cancel();
            revealAnim = null;
        }
    }

    private float maxRadiusFrom(int cx, int cy) {
        int w = getWidth(), h = getHeight();
        float dx = Math.max(cx, w - cx);
        float dy = Math.max(cy, h - cy);
        return (float) Math.hypot(dx, dy);
    }

    /** Latest mic RMS sample. Normalised internally; drives the gradient
     *  intensity. */
    public void setRms(float dB) {
        float n = (dB + 2f) / 12f;
        if (n < 0f) n = 0f;
        if (n > 1f) n = 1f;
        glow.setLoudness(n);
    }

    /** Live partial transcript. Empty/null → hint shown; non-empty → text. */
    public void setTranscript(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            transcript.setText("");
            hint.setVisibility(VISIBLE);
        } else {
            transcript.setText(text);
            hint.setVisibility(GONE);
        }
    }

    /** Ignored — the gradient design has its own fixed palette. */
    public void applyTheme(KeyboardTheme theme) { /* no-op */ }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ──────────────────────────────────────────────────────────────────────
    // GL gradient layer
    // ──────────────────────────────────────────────────────────────────────

    /**
     *  TextureView that owns a {@link RenderThread} for an animated gradient.
     *  Unlike GLSurfaceView this draws into the window surface, so siblings
     *  in the FrameLayout compose on top without z-order workarounds.
     */
    private static class GlowTexture extends TextureView
            implements TextureView.SurfaceTextureListener {

        @Nullable private RenderThread thread;
        private volatile float loudness;

        GlowTexture(Context c) {
            super(c);
            // Honour the alpha channel — without this, TextureView treats
            // every pixel as opaque and the gradient won't blend with the
            // keyboard.
            setOpaque(false);
            setSurfaceTextureListener(this);
        }

        void setLoudness(float n) {
            loudness = n;
            if (thread != null) thread.setLoudness(n);
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
            thread = new RenderThread(s, w, h);
            thread.setLoudness(loudness);
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
            return true; // we own the SurfaceTexture; let TextureView release it
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture s) { /* no-op */ }
    }

    /**
     *  Render thread — owns an EGL context bound to the TextureView's
     *  SurfaceTexture and draws the gradient at ~60 fps.
     *
     *  <p>Threading model is straightforward: created by
     *  {@link GlowTexture#onSurfaceTextureAvailable}, torn down by
     *  {@link GlowTexture#onSurfaceTextureDestroyed}. Main thread updates
     *  {@link #loudness} (volatile); render thread reads it each frame.
     */
    private static class RenderThread extends Thread {

        private static final String TAG = "VoiceGlowRT";
        private static final String VERT_SRC =
                "attribute vec2 a_position;\n" +
                "varying vec2 v_uv;\n" +
                "void main() {\n" +
                "    v_uv = a_position * 0.5 + 0.5;\n" +
                "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
                "}\n";

        // Two arcs — top and bottom — drawn as gaussian-falloff bands around
        // a radius from off-screen centres.
        //
        // An "arc band" is the set of points whose distance from a centre
        // point is ≈ r. By placing the centre OUTSIDE the view (below for
        // the top arc, above for the bottom), only a slice of the circle is
        // visible — and that slice reads as a gentle arch.
        //
        //   top arc:    centre (0.5, -0.40), radius 1.00
        //               apex at y=0.60 (just above midline), curving down to
        //               y≈0.47 at the left/right edges.
        //   bottom arc: mirror across the horizontal midline.
        //
        // halfWidth controls band thickness. Tweak these constants to
        // redesign the arc shape; the rest of the renderer doesn't care.
        private static final String FRAG_SRC =
                "precision mediump float;\n" +
                "varying vec2 v_uv;\n" +
                "uniform float u_time;\n" +
                "uniform float u_loudness;\n" +
                "\n" +
                "// Gaussian band around radius r — bright at d≈r, fading on\n" +
                "// both sides.\n" +
                "float arcBand(vec2 p, vec2 c, float r, float halfWidth) {\n" +
                "    float d = distance(p, c);\n" +
                "    float x = (d - r) / halfWidth;\n" +
                "    return exp(-x * x);\n" +
                "}\n" +
                "\n" +
                "void main() {\n" +
                "    vec2 p = v_uv;\n" +
                "\n" +
                "    // Top arc — centre below the view; apex lands just above\n" +
                "    // the horizontal midline (where the text sits).\n" +
                "    float topGlow = arcBand(p, vec2(0.5, -0.40), 1.00, 0.10);\n" +
                "\n" +
                "    // Bottom arc — mirror.\n" +
                "    float botGlow = arcBand(p, vec2(0.5,  1.40), 1.00, 0.10);\n" +
                "\n" +
                "    float intensity = max(topGlow, botGlow);\n" +
                "    intensity *= 0.70 + u_loudness * 0.40;\n" +
                "\n" +
                "    vec3 col = vec3(0.55, 0.42, 1.00); // purple\n" +
                "\n" +
                "    // Premultiplied alpha (GL_ONE / GL_ONE_MINUS_SRC_ALPHA).\n" +
                "    gl_FragColor = vec4(col * intensity, intensity);\n" +
                "}\n";

        // Fullscreen quad (-1..1) drawn as TRIANGLE_STRIP.
        private static final float[] QUAD = {
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f
        };

        private final SurfaceTexture surfaceTexture;
        private volatile int width, height;
        private volatile boolean shutdown;
        private volatile float loudness;

        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface surface = EGL14.EGL_NO_SURFACE;

        private int program;
        private int aPos, uTime, uLoud;
        private FloatBuffer vb;
        private long startedAt;

        RenderThread(SurfaceTexture s, int w, int h) {
            super("VoiceGlowRenderer");
            this.surfaceTexture = s;
            this.width = w;
            this.height = h;
        }

        void setLoudness(float n) { loudness = n; }
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

            aPos  = GLES20.glGetAttribLocation (program, "a_position");
            uTime = GLES20.glGetUniformLocation(program, "u_time");
            uLoud = GLES20.glGetUniformLocation(program, "u_loudness");

            vb = ByteBuffer.allocateDirect(QUAD.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            vb.put(QUAD).position(0);

            GLES20.glClearColor(0f, 0f, 0f, 0.2f);
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
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            float t = (System.currentTimeMillis() - startedAt) / 1000f;
            GLES20.glUseProgram(program);
            GLES20.glUniform1f(uTime, t);
            GLES20.glUniform1f(uLoud, loudness);

            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vb);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPos);

            EGL14.eglSwapBuffers(display, surface);
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
