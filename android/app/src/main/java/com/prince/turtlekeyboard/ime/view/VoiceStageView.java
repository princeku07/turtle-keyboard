package com.prince.turtlekeyboard.ime.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import com.prince.turtlekeyboard.theme.KeyboardTheme;

/**
 * Full-keyboard-area takeover shown while voice dictation is active.
 *
 * <p>Composition (back → front, all overlaid in a {@link FrameLayout}):
 * <ol>
 *   <li>{@link GradientBlobView} — a {@link GLSurfaceView} that runs a real
 *       fragment shader to render three "jelly" sub-surfaces drifting on
 *       independent orbits. Audio loudness feeds shader uniforms.</li>
 *   <li>Uniform dark scrim over the entire blob to keep the text legible.</li>
 *   <li>"LISTENING" hint (visible until the first partial transcript).</li>
 *   <li>The live transcript, big and bold.</li>
 * </ol>
 *
 * <p>Tap anywhere on the stage to stop dictation (commits the transcript).
 * The stage animates open with a circular reveal from the mic button's
 * centre, and collapses back into it on stop — call {@link #start(int,int)}
 * with the mic's window coordinates.
 *
 * <p>The IME assigns this view's height to match the keyboard's measured
 * height before flipping the keyboard to {@code GONE} — so the IME window
 * height stays constant.
 */
public class VoiceStageView extends FrameLayout {

    public interface Listener {
        /** Stage was tapped — commit the current transcript. */
        void onStop();
    }

    private static final int BG = 0xFF000000;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_HINT = 0xCCFFFFFF;
    private static final int TEXT_SHADOW = 0xAA000000;

    private static final long REVEAL_OPEN_MS = 380L;
    private static final long REVEAL_CLOSE_MS = 280L;

    private GradientBlobView blob;
    private TextView transcript;
    private TextView hint;
    private Listener listener;

    // Reveal-animation source point (mic centre) in this view's local coords.
    private int revealCx = -1, revealCy = -1;
    @Nullable private Animator revealAnim;

    public VoiceStageView(Context c) { super(c); init(); }
    public VoiceStageView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        setVisibility(GONE);
        setBackgroundColor(BG);
        setClickable(true);
        setFocusable(true);
        // Tap anywhere on the stage to stop dictation. The hint/transcript are
        // not themselves clickable, so taps on them bubble up here.
        setOnClickListener(v -> { if (listener != null) listener.onStop(); });

        // ── 1) Blob: full-stage GL background.
        blob = new GradientBlobView(getContext());
        LayoutParams blobLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        addView(blob, blobLp);

        // ── 1b) Uniform dark scrim over the entire blob.
        View scrim = new View(getContext());
        scrim.setBackgroundColor(0x73000000);
        LayoutParams scrimLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        addView(scrim, scrimLp);

        // ── 2) Hint.
        hint = new TextView(getContext());
        hint.setText("LISTENING");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setLetterSpacing(0.24f);
        hint.setTypeface(hint.getTypeface(), Typeface.BOLD);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(TEXT_HINT);
        hint.setShadowLayer(dp(4), 0f, dp(1), TEXT_SHADOW);
        LayoutParams hLp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        hLp.gravity = Gravity.CENTER;
        addView(hint, hLp);

        // ── 3) Transcript.
        transcript = new TextView(getContext());
        transcript.setText("");
        transcript.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
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

    /** Show the stage, revealing from the mic button's window-space centre.
     *  Local coordinates are resolved inside {@code post()} after the stage
     *  has been laid out (and the keyboard has been swapped to GONE), so the
     *  reveal anchor is always correct. */
    public void start(int micWindowX, int micWindowY) {
        transcript.setText("");
        hint.setVisibility(VISIBLE);
        setVisibility(VISIBLE);
        blob.start();
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
     *  #start(int,int)} call, then hide it. If we never had a source (size
     *  is 0, or animation API rejected), fall back to instant hide. */
    public void stop() {
        cancelRevealAnim();
        if (getVisibility() != VISIBLE
                || getWidth() == 0 || getHeight() == 0
                || revealCx < 0 || revealCy < 0) {
            blob.stop();
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
                blob.stop();
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

    /** Distance from {@code (cx,cy)} to the farthest corner. Used as the
     *  reveal's end radius so the clip fully covers the view. */
    private float maxRadiusFrom(int cx, int cy) {
        int w = getWidth();
        int h = getHeight();
        float dx = Math.max(cx, w - cx);
        float dy = Math.max(cy, h - cy);
        return (float) Math.hypot(dx, dy);
    }

    public void setRms(float dB) { blob.setRms(dB); }

    public void setTranscript(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            transcript.setText("");
            hint.setVisibility(VISIBLE);
        } else {
            transcript.setText(text);
            hint.setVisibility(GONE);
        }
    }

    /** Ignored intentionally — the gradient design has its own fixed palette. */
    public void applyTheme(KeyboardTheme theme) { /* no-op */ }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ──────────────────────────────────────────────────────────────────────
    // GL blob
    // ──────────────────────────────────────────────────────────────────────

    /** GLSurfaceView wrapper. Owns a {@link BlobRenderer} that runs vertex +
     *  fragment shaders to draw three audio-reactive sub-surfaces. */
    private static class GradientBlobView extends GLSurfaceView {
        private final BlobRenderer renderer;

        GradientBlobView(Context c) {
            super(c);
            setEGLContextClientVersion(2);
            renderer = new BlobRenderer();
            setRenderer(renderer);
            setRenderMode(RENDERMODE_CONTINUOUSLY);
        }

        void start() { renderer.start(); }
        void stop() { renderer.stop(); }
        void setRms(float dB) { renderer.setRms(dB); }
    }

    /** Renders a fullscreen quad and shades it with the blob fragment shader.
     *
     *  <p>Per-frame Java work:
     *  <ol>
     *    <li>Decay loudness (eases back toward 0 between RMS samples).</li>
     *    <li>For each of 3 sub surfaces, compute its current centre and
     *        radius from {@code u_time} + drift orbit + audio inflation.</li>
     *    <li>Upload uniforms.</li>
     *    <li>Draw two triangles ({@code GL_TRIANGLE_STRIP}, 4 verts).</li>
     *  </ol>
     *
     *  <p>All gradient blending, smooth merging, and grain happens in the
     *  fragment shader on the GPU. */
    private static class BlobRenderer implements GLSurfaceView.Renderer {

        private static final String TAG = "BlobRenderer";

        // GL ES 2.0 shaders. The vertex shader is a passthrough that just
        // emits a fullscreen quad and forwards a 0..1 UV. The fragment shader
        // computes a soft per-pixel weight for each sub surface, blends their
        // colours, and overlays animated film grain.
        private static final String VERT_SRC =
                "attribute vec2 a_position;\n" +
                "varying vec2 v_uv;\n" +
                "void main() {\n" +
                "    v_uv = a_position * 0.5 + 0.5;\n" +
                "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
                "}\n";

        private static final String FRAG_SRC =
                "precision mediump float;\n" +
                "varying vec2 v_uv;\n" +
                "\n" +
                "uniform vec2 u_resolution;\n" +
                "uniform float u_time;\n" +
                "uniform float u_loudness;\n" +
                "\n" +
                "// Three sub surfaces. Position is in pixel space, radius in pixels.\n" +
                "uniform vec2 u_pos1; uniform float u_r1; uniform vec3 u_col1; uniform float u_a1;\n" +
                "uniform vec2 u_pos2; uniform float u_r2; uniform vec3 u_col2; uniform float u_a2;\n" +
                "uniform vec2 u_pos3; uniform float u_r3; uniform vec3 u_col3; uniform float u_a3;\n" +
                "\n" +
                "// Cheap per-pixel hash. Identical seed → identical noise frame-to-frame;\n" +
                "// we vary the seed with floor(time*30) so grain shimmers like film.\n" +
                "float hash(vec2 p) {\n" +
                "    p = fract(p * vec2(123.34, 456.21));\n" +
                "    p += dot(p, p + 78.233);\n" +
                "    return fract(p.x * p.y);\n" +
                "}\n" +
                "\n" +
                "// Smooth radial weight: 1 at centre, 0 at radius. The smoothstep\n" +
                "// start is offset inward from the centre so the inner ~15% stays\n" +
                "// fully solid and the rim band is narrower — tighter edge, less blur.\n" +
                "float subWeight(vec2 p, vec2 c, float r) {\n" +
                "    float t = 1.0 - smoothstep(r * 0.15, r, distance(p, c));\n" +
                "    return t * t;\n" +
                "}\n" +
                "\n" +
                "void main() {\n" +
                "    vec2 p = v_uv * u_resolution;\n" +
                "\n" +
                "    float w1 = subWeight(p, u_pos1, u_r1) * u_a1;\n" +
                "    float w2 = subWeight(p, u_pos2, u_r2) * u_a2;\n" +
                "    float w3 = subWeight(p, u_pos3, u_r3) * u_a3;\n" +
                "\n" +
                "    float total = w1 + w2 + w3;\n" +
                "    // Normalised colour blend across the three subs.\n" +
                "    vec3 col = (u_col1 * w1 + u_col2 * w2 + u_col3 * w3) / max(total, 0.0001);\n" +
                "    // Intensity is the combined field, clamped — produces a soft edge\n" +
                "    // to black at the rim, no hard outline.\n" +
                "    float intensity = clamp(total, 0.0, 1.0);\n" +
                "    vec3 finalColor = col * intensity;\n" +
                "\n" +
                "    // Film grain — animated by quantising time so it doesn't strobe\n" +
                "    // per frame at 60fps but still reads as 'live'.\n" +
                "    float grain = (hash(p + floor(u_time * 30.0)) - 0.5) * 0.06;\n" +
                "    finalColor += grain;\n" +
                "\n" +
                "    gl_FragColor = vec4(finalColor, 1.0);\n" +
                "}\n";

        // Fullscreen-quad vertices as -1..1 x,y (drawn as TRIANGLE_STRIP).
        private static final float[] QUAD = {
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f
        };

        // GL handles
        private int program;
        private int aPos;
        private int uTime, uLoud, uRes;
        private int uPos1, uPos2, uPos3;
        private int uR1, uR2, uR3;
        private int uCol1, uCol2, uCol3;
        private int uA1, uA2, uA3;

        private FloatBuffer vertexBuffer;

        // Envelope follower constants (seconds). Slow attack settles into a new
        // RMS sample rather than snapping; slower release makes the blob exhale
        // smoothly when speech stops. Tuning is what makes intensity feel like
        // breath instead of jitter.
        private static final float ATTACK_S = 0.08f;
        private static final float RELEASE_S = 0.60f;

        // State (volatile reads from the GL thread)
        private long startedAt = System.currentTimeMillis();
        private volatile float targetLoudness; // latest RMS sample (main thread writes)
        private float loudness;                // envelope-tracked value (GL thread)
        private long lastEnvelopeMs;           // wall-clock for dt-based smoothing
        private int width, height;

        // Three sub surfaces — static config, dynamic centre + radius derived
        // per frame from time + loudness. The blue sub is the big body, green
        // is the mid-layer offset, hilite is the small fast travelling spot.
        private final Sub blueSub   = new Sub(0x2747FF, 1.10f, 0.08f, 0.32f,
                0.9f, 0.7f, 0.0f, 0.0f, 1.30f, 1.00f);
        private final Sub greenSub  = new Sub(0x66FFA0, 0.85f, 0.14f, 0.38f,
                0.6f, 1.1f, 1.3f, 0.4f, 1.50f, 0.92f);
        private final Sub hiliteSub = new Sub(0xE8FFD8, 0.45f, 0.22f, 0.45f,
                1.7f, 1.4f, 2.1f, 1.7f, 2.10f, 0.78f);

        void start() {
            startedAt = System.currentTimeMillis();
            loudness = 0f;
            targetLoudness = 0f;
            lastEnvelopeMs = 0L;
        }

        void stop() {
            loudness = 0f;
            targetLoudness = 0f;
        }

        /** Stash the latest normalised RMS sample. The envelope follower in
         *  {@link #onDrawFrame} eases the displayed {@link #loudness} toward
         *  this target with attack/release dynamics — no spike chasing. */
        void setRms(float dB) {
            float n = (dB + 2f) / 12f;
            if (n < 0f) n = 0f;
            if (n > 1f) n = 1f;
            targetLoudness = n;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            // Re-built fresh on every surface re-create.
            program = createProgram(VERT_SRC, FRAG_SRC);
            if (program == 0) return;

            aPos  = GLES20.glGetAttribLocation(program, "a_position");
            uTime = GLES20.glGetUniformLocation(program, "u_time");
            uLoud = GLES20.glGetUniformLocation(program, "u_loudness");
            uRes  = GLES20.glGetUniformLocation(program, "u_resolution");
            uPos1 = GLES20.glGetUniformLocation(program, "u_pos1");
            uPos2 = GLES20.glGetUniformLocation(program, "u_pos2");
            uPos3 = GLES20.glGetUniformLocation(program, "u_pos3");
            uR1   = GLES20.glGetUniformLocation(program, "u_r1");
            uR2   = GLES20.glGetUniformLocation(program, "u_r2");
            uR3   = GLES20.glGetUniformLocation(program, "u_r3");
            uCol1 = GLES20.glGetUniformLocation(program, "u_col1");
            uCol2 = GLES20.glGetUniformLocation(program, "u_col2");
            uCol3 = GLES20.glGetUniformLocation(program, "u_col3");
            uA1   = GLES20.glGetUniformLocation(program, "u_a1");
            uA2   = GLES20.glGetUniformLocation(program, "u_a2");
            uA3   = GLES20.glGetUniformLocation(program, "u_a3");

            vertexBuffer = ByteBuffer.allocateDirect(QUAD.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            vertexBuffer.put(QUAD).position(0);

            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            this.width = w;
            this.height = h;
            GLES20.glViewport(0, 0, w, h);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (program == 0 || width == 0 || height == 0) return;

            // One-pole envelope follower toward targetLoudness. dt-based so
            // attack/release feel the same regardless of frame rate. Long
            // pauses (e.g. surface re-create) get clamped to avoid a sudden
            // jump on the first resumed frame.
            long now = System.currentTimeMillis();
            if (lastEnvelopeMs == 0L) lastEnvelopeMs = now;
            float dt = (now - lastEnvelopeMs) / 1000f;
            if (dt > 0.1f) dt = 0.1f;
            lastEnvelopeMs = now;

            float target = targetLoudness;
            float tau = (target > loudness) ? ATTACK_S : RELEASE_S;
            float alpha = 1f - (float) Math.exp(-dt / tau);
            loudness += alpha * (target - loudness);

            float t = (now - startedAt) / 1000f;
            float cx = width / 2f;
            float cy = height / 2f;
            float baseR = Math.min(width, height) * 0.55f;
            float audioBoost = 1f + loudness * 0.35f;
            float driftBoost = 1f + loudness * 1.50f;

            GLES20.glUseProgram(program);
            GLES20.glUniform1f(uTime, t);
            GLES20.glUniform1f(uLoud, loudness);
            GLES20.glUniform2f(uRes, width, height);

            uploadSub(blueSub,   uPos1, uR1, uCol1, uA1, t, cx, cy, baseR, audioBoost, driftBoost);
            uploadSub(greenSub,  uPos2, uR2, uCol2, uA2, t, cx, cy, baseR, audioBoost, driftBoost);
            uploadSub(hiliteSub, uPos3, uR3, uCol3, uA3, t, cx, cy, baseR, audioBoost, driftBoost);

            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPos);
        }

        private void uploadSub(Sub s, int posLoc, int rLoc, int colLoc, int aLoc,
                               float t, float cx, float cy, float baseR,
                               float audioBoost, float driftBoost) {
            float drift = baseR * s.driftFactor * driftBoost;
            float subCx = cx + drift * (float) Math.sin(t * s.fx + s.px);
            float subCy = cy + drift * (float) Math.cos(t * s.fy + s.py);
            float r = baseR * s.sizeFactor
                    * (1f + 0.06f * (float) Math.sin(t * s.fr) + loudness * s.audioSize)
                    * audioBoost;

            // GL has Y up (framebuffer origin at bottom-left); the math is
            // symmetric about cy so we don't need to flip — but if the visual
            // ever needs to match Android view-space we'd subtract subCy here.
            GLES20.glUniform2f(posLoc, subCx, subCy);
            GLES20.glUniform1f(rLoc, r);
            GLES20.glUniform3f(colLoc, s.r, s.g, s.b);
            GLES20.glUniform1f(aLoc, s.alpha);
        }

        // ── Shader compile/link helpers ───────────────────────────────────

        private static int compileShader(int type, String src) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, src);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        private static int createProgram(String vSrc, String fSrc) {
            int vs = compileShader(GLES20.GL_VERTEX_SHADER, vSrc);
            int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fSrc);
            if (vs == 0 || fs == 0) return 0;
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vs);
            GLES20.glAttachShader(program, fs);
            GLES20.glLinkProgram(program);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                return 0;
            }
            // Shaders can be detached/deleted after a successful link.
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            return program;
        }
    }

    /** Static config for a sub surface. Per-frame centre + radius are
     *  computed in {@link BlobRenderer#uploadSub} from these factors. */
    private static class Sub {
        final float r, g, b;            // RGB 0..1
        final float sizeFactor;          // radius as a fraction of baseR
        final float driftFactor;         // orbit radius as a fraction of baseR
        final float audioSize;           // extra radius scaling per unit loudness
        final float fx, fy;              // orbital frequencies (rad/s)
        final float px, py;              // orbital phase offsets
        final float fr;                  // radius-pulse frequency
        final float alpha;               // contribution weight 0..1

        Sub(int rgb888, float sizeFactor, float driftFactor, float audioSize,
            float fx, float fy, float px, float py, float fr, float alpha) {
            this.r = ((rgb888 >> 16) & 0xFF) / 255f;
            this.g = ((rgb888 >> 8) & 0xFF) / 255f;
            this.b = (rgb888 & 0xFF) / 255f;
            this.sizeFactor = sizeFactor;
            this.driftFactor = driftFactor;
            this.audioSize = audioSize;
            this.fx = fx; this.fy = fy;
            this.px = px; this.py = py;
            this.fr = fr;
            this.alpha = alpha;
        }
    }
}
