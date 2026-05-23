package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.theme.KeyboardTheme;

/**
 * Replaces the keyboard area with a Gboard-style "more options" panel: a top
 * icon row anchored to a green back button, a header, and a grid of action
 * tiles. Mounted by the IME in the quick-panel host when the user taps the
 * leading hamburger on the suggestion strip.
 */
public class MoreActionsPanelView extends LinearLayout {

    public interface Callbacks {
        void onClose();
        void onAction(int actionId);
    }

    public static final int ACTION_THEME       = 1;
    public static final int ACTION_QUICK_PANEL = 2;
    public static final int ACTION_HISTORY     = 3;
    public static final int ACTION_SETTINGS    = 4;
    public static final int ACTION_VOICE       = 5;
    public static final int ACTION_TRANSLATE   = 6;
    public static final int ACTION_EMOJI       = 7;
    public static final int ACTION_UNDO        = 8;

    private static final int BG           = 0xFF000000;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;
    private static final int TEXT_MUTED   = 0xA0F5F5F5;
    private static final int CHIP_FILL    = 0x14FFFFFF;
    private static final int RIPPLE_WASH  = 0x33FFFFFF;
    private static final int ACCENT_LIME  = 0xFF15803D;

    private static final int PANEL_RADIUS_DP = 16;
    private static final int TILE_RADIUS_DP  = 12;
    private static final int TOP_GAP_DP      = 12;
    private static final int SLIDE_OFFSET_DP = 28;
    private static final int GRID_COLUMNS    = 3;
    private static final Interpolator ENTER_EASING =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f);
    private static final Interpolator EXIT_EASING =
            new AccelerateInterpolator(1.2f);

    @Nullable private Callbacks callbacks;
    private final LinearLayout cardContainer;
    private final CircleBackButton backButton;
    private final LinearLayout topIconRow;
    private final TextView header;
    private final GridLayout grid;

    public MoreActionsPanelView(Context c) {
        this(c, null);
    }

    public MoreActionsPanelView(Context c, @Nullable AttributeSet a) {
        super(c, a);
        setOrientation(VERTICAL);

        cardContainer = new LinearLayout(c);
        cardContainer.setOrientation(VERTICAL);
        final int cardRadius = dp(PANEL_RADIUS_DP);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(BG);
        cardBg.setCornerRadius(cardRadius);
        cardBg.setStroke(dp(1), 0x33FFFFFF);
        cardContainer.setBackground(cardBg);
        cardContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cardRadius);
            }
        });
        cardContainer.setClipToOutline(true);

        LinearLayout topRow = new LinearLayout(c);
        topRow.setOrientation(HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        int rowPad = dp(12);
        topRow.setPadding(rowPad, dp(10), rowPad, dp(4));

        backButton = new CircleBackButton(c);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        backLp.rightMargin = dp(10);
        topRow.addView(backButton, backLp);

        topIconRow = new LinearLayout(c);
        topIconRow.setOrientation(HORIZONTAL);
        topIconRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams iconRowLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        topRow.addView(topIconRow, iconRowLp);
        addQuickIcon(GlyphIcon.GLYPH_QUICK_PANEL, ACTION_QUICK_PANEL);
        addQuickIcon(GlyphIcon.GLYPH_HISTORY, ACTION_HISTORY);
        addQuickIcon(GlyphIcon.GLYPH_SETTINGS, ACTION_SETTINGS);
        addQuickIcon(GlyphIcon.GLYPH_MIC, ACTION_VOICE);

        cardContainer.addView(topRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        header = new TextView(c);
        header.setText("More options");
        header.setTextColor(TEXT_MUTED);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setLetterSpacing(0.04f);
        header.setGravity(Gravity.CENTER);
        header.setIncludeFontPadding(false);
        header.setPadding(0, dp(6), 0, dp(8));
        cardContainer.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);

        grid = new GridLayout(c);
        grid.setColumnCount(GRID_COLUMNS);
        int gridPad = dp(12);
        grid.setPadding(gridPad, 0, gridPad, gridPad);
        scroll.addView(grid, new ScrollView.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        cardContainer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        backButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            animateOut(() -> { if (callbacks != null) callbacks.onClose(); });
        });

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        cardLp.topMargin = dp(TOP_GAP_DP);
        addView(cardContainer, cardLp);
    }

    /** Bind callbacks and (re)populate the grid. Safe to call multiple times. */
    public void show(Callbacks callbacks) {
        this.callbacks = callbacks;
        grid.removeAllViews();
        addTile("🎨", "Theme",     ACTION_THEME);
        addTile("⚡",  "Slash",     ACTION_QUICK_PANEL);
        addTile("🗂",  "History",   ACTION_HISTORY);
        addTile("⚙",   "Settings",  ACTION_SETTINGS);
        addTile("🌐", "Translate", ACTION_TRANSLATE);
        addTile("↩",   "Undo",      ACTION_UNDO);
        animateIn();
    }

    /** No-op for API symmetry — the panel pins to its own dark palette. */
    @SuppressWarnings("unused")
    public void applyTheme(KeyboardTheme theme) { }

    private void addQuickIcon(int glyph, int actionId) {
        GlyphIcon icon = new GlyphIcon(getContext(), glyph);
        icon.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            animateOut(() -> { if (callbacks != null) callbacks.onAction(actionId); });
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.rightMargin = dp(6);
        topIconRow.addView(icon, lp);
    }

    private void addTile(String emoji, String label, int actionId) {
        Tile tile = new Tile(getContext(), emoji, label);
        tile.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            animateOut(() -> { if (callbacks != null) callbacks.onAction(actionId); });
        });
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        int margin = dp(5);
        lp.setMargins(margin, margin, margin, margin);
        tile.setLayoutParams(lp);
        grid.addView(tile);
    }

    private void animateIn() {
        animate().cancel();
        setAlpha(0f);
        setTranslationY(dp(SLIDE_OFFSET_DP));
        animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320)
                .setInterpolator(ENTER_EASING)
                .start();
    }

    private void animateOut(Runnable onEnd) {
        animate().cancel();
        animate()
                .alpha(0f)
                .translationY(dp(SLIDE_OFFSET_DP))
                .setDuration(220)
                .setInterpolator(EXIT_EASING)
                .withEndAction(() -> {
                    setAlpha(1f);
                    setTranslationY(0f);
                    if (onEnd != null) onEnd.run();
                })
                .start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        animate().cancel();
        setAlpha(1f);
        setTranslationY(0f);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static class CircleBackButton extends FrameLayout {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CircleBackButton(Context c) {
            super(c);
            setClickable(true);
            setFocusable(true);
            setWillNotDraw(false);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(ACCENT_LIME);
            arrowPaint.setStyle(Paint.Style.STROKE);
            arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            arrowPaint.setStrokeJoin(Paint.Join.ROUND);
            arrowPaint.setColor(Color.WHITE);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    int side = Math.min(view.getWidth(), view.getHeight());
                    int inset = (int) dp(2);
                    outline.setOval(inset, inset, side - inset, side - inset);
                }
            });
            setClipToOutline(true);
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(Color.WHITE);
            setForeground(new RippleDrawable(
                    ColorStateList.valueOf(RIPPLE_WASH), null, mask));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) / 2f - dp(2);
            float cx = w / 2f, cy = h / 2f;
            c.drawCircle(cx, cy, r, fillPaint);
            arrowPaint.setStrokeWidth(dp(2.2f));
            float arm = r * 0.45f;
            c.drawLine(cx - arm, cy, cx + arm, cy, arrowPaint);
            c.drawLine(cx - arm, cy, cx - arm * 0.3f, cy - arm * 0.55f, arrowPaint);
            c.drawLine(cx - arm, cy, cx - arm * 0.3f, cy + arm * 0.55f, arrowPaint);
        }

        private float dp(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    getResources().getDisplayMetrics());
        }
    }

    private static class GlyphIcon extends FrameLayout {
        static final int GLYPH_QUICK_PANEL = 0;
        static final int GLYPH_HISTORY = 1;
        static final int GLYPH_SETTINGS = 2;
        static final int GLYPH_MIC = 3;

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int glyph;

        GlyphIcon(Context c, int glyph) {
            super(c);
            this.glyph = glyph;
            setClickable(true);
            setFocusable(true);
            setWillNotDraw(false);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(CHIP_FILL);
            glyphPaint.setStyle(Paint.Style.STROKE);
            glyphPaint.setStrokeCap(Paint.Cap.ROUND);
            glyphPaint.setStrokeJoin(Paint.Join.ROUND);
            glyphPaint.setColor(TEXT_PRIMARY);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    int side = Math.min(view.getWidth(), view.getHeight());
                    int inset = (int) dp(3);
                    outline.setOval(inset, inset, side - inset, side - inset);
                }
            });
            setClipToOutline(true);
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(Color.WHITE);
            setForeground(new RippleDrawable(
                    ColorStateList.valueOf(RIPPLE_WASH), null, mask));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) / 2f - dp(3);
            float cx = w / 2f, cy = h / 2f;
            c.drawCircle(cx, cy, r, fillPaint);
            glyphPaint.setStrokeWidth(dp(1.8f));
            switch (glyph) {
                case GLYPH_QUICK_PANEL: drawGrid(c, cx, cy, r); break;
                case GLYPH_HISTORY:     drawHistory(c, cx, cy, r); break;
                case GLYPH_SETTINGS:    drawGear(c, cx, cy, r); break;
                case GLYPH_MIC:         drawMic(c, cx, cy, r); break;
            }
        }

        private void drawGrid(Canvas c, float cx, float cy, float r) {
            float s = r * 0.32f;
            float gap = r * 0.14f;
            float[] xs = { cx - s - gap, cx + gap };
            float[] ys = { cy - s - gap, cy + gap };
            Paint fill = new Paint(glyphPaint);
            fill.setStyle(Paint.Style.FILL);
            for (float x : xs) for (float y : ys) {
                c.drawRoundRect(new RectF(x, y, x + s, y + s), s * 0.25f, s * 0.25f, fill);
            }
        }

        private void drawHistory(Canvas c, float cx, float cy, float r) {
            float arcR = r * 0.7f;
            RectF arc = new RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR);
            c.drawArc(arc, 60f, 280f, false, glyphPaint);
            c.drawLine(cx, cy, cx, cy - arcR * 0.55f, glyphPaint);
            c.drawLine(cx, cy, cx + arcR * 0.45f, cy, glyphPaint);
        }

        private void drawGear(Canvas c, float cx, float cy, float r) {
            float outer = r * 0.85f;
            float inner = r * 0.55f;
            int teeth = 8;
            Paint fill = new Paint(glyphPaint);
            fill.setStyle(Paint.Style.FILL);
            for (int i = 0; i < teeth; i++) {
                double a = i * (2 * Math.PI / teeth);
                float tx = cx + (float) (Math.cos(a) * outer);
                float ty = cy + (float) (Math.sin(a) * outer);
                c.drawCircle(tx, ty, dp(2f), fill);
            }
            c.drawCircle(cx, cy, inner, glyphPaint);
            c.drawCircle(cx, cy, inner * 0.35f, fill);
        }

        private void drawMic(Canvas c, float cx, float cy, float r) {
            float bodyW = r * 0.5f, bodyH = r * 0.85f;
            Paint fill = new Paint(glyphPaint);
            fill.setStyle(Paint.Style.FILL);
            RectF body = new RectF(cx - bodyW / 2f, cy - bodyH * 0.55f,
                    cx + bodyW / 2f, cy + bodyH * 0.10f);
            c.drawRoundRect(body, bodyW / 2f, bodyW / 2f, fill);
            float arcR = bodyW * 0.95f;
            RectF arc = new RectF(cx - arcR, cy - arcR * 0.2f, cx + arcR, cy + arcR * 1.2f);
            c.drawArc(arc, 20f, 140f, false, glyphPaint);
            c.drawLine(cx, cy + arcR * 0.55f, cx, cy + arcR * 0.95f, glyphPaint);
        }

        private float dp(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    getResources().getDisplayMetrics());
        }
    }

    private static class Tile extends LinearLayout {
        Tile(Context c, String emoji, String label) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setClickable(true);
            setFocusable(true);
            int padV = (int) dp(14), padH = (int) dp(10);
            setPadding(padH, padV, padH, padV);

            final float radius = dp(TILE_RADIUS_DP);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(radius);
            bg.setColor(CHIP_FILL);
            setBackground(bg);

            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.RECTANGLE);
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(radius);
            setForeground(new RippleDrawable(
                    ColorStateList.valueOf(RIPPLE_WASH), null, mask));

            TextView emojiView = new TextView(c);
            emojiView.setText(emoji);
            emojiView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
            emojiView.setIncludeFontPadding(false);
            emojiView.setGravity(Gravity.CENTER);
            addView(emojiView, new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT));

            TextView labelView = new TextView(c);
            labelView.setText(label);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            labelView.setTextColor(TEXT_PRIMARY);
            labelView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            labelView.setIncludeFontPadding(false);
            labelView.setGravity(Gravity.CENTER);
            LayoutParams labelLp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = (int) dp(6);
            addView(labelView, labelLp);
        }

        private float dp(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    getResources().getDisplayMetrics());
        }
    }
}
