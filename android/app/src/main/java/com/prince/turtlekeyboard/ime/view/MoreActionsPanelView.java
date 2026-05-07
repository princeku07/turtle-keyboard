package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.theme.KeyboardTheme;

/**
 * Replaces the keyboard area with a Gboard-style "more options" panel: a top icon
 * row anchored to a green back button, a header, and a grid of action tiles. The
 * IME mounts this view in {@code quickPanelHost} when the user taps the leading
 * hamburger button on the suggestion strip.
 */
public class MoreActionsPanelView extends LinearLayout {

    public interface Callbacks {
        /** User dismissed the panel via the back button. IME should hide it. */
        void onClose();
        /** User tapped a tile or top-row glyph. The action ID identifies which one. */
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

    @Nullable private Callbacks callbacks;
    @Nullable private KeyboardTheme theme;
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

        // Top bar: back button + small inline icon shortcuts.
        LinearLayout topRow = new LinearLayout(c);
        topRow.setOrientation(HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        int rowPad = dp(10);
        topRow.setPadding(rowPad, rowPad, rowPad, dp(4));

        backButton = new CircleBackButton(c);
        LayoutParams backLp = new LayoutParams(dp(40), dp(40));
        backLp.rightMargin = dp(8);
        topRow.addView(backButton, backLp);

        topIconRow = new LinearLayout(c);
        topIconRow.setOrientation(HORIZONTAL);
        topIconRow.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams iconRowLp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        topRow.addView(topIconRow, iconRowLp);
        addQuickIcon(GlyphIcon.GLYPH_QUICK_PANEL, ACTION_QUICK_PANEL);
        addQuickIcon(GlyphIcon.GLYPH_HISTORY, ACTION_HISTORY);
        addQuickIcon(GlyphIcon.GLYPH_SETTINGS, ACTION_SETTINGS);
        addQuickIcon(GlyphIcon.GLYPH_MIC, ACTION_VOICE);

        addView(topRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Header label.
        header = new TextView(c);
        header.setText("More options");
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, dp(4), 0, dp(8));
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Tile grid in a scroller so taller panels stay reachable.
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);

        grid = new GridLayout(c);
        grid.setColumnCount(4);
        int gridPad = dp(10);
        grid.setPadding(gridPad, 0, gridPad, gridPad);
        scroll.addView(grid, new ScrollView.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        LayoutParams scrollLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        addView(scroll, scrollLp);

        backButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (callbacks != null) callbacks.onClose();
        });
    }

    /** Bind callbacks and (re)populate the grid. Safe to call multiple times. */
    public void show(Callbacks callbacks) {
        this.callbacks = callbacks;
        grid.removeAllViews();
        addTile("🎨", "Theme", ACTION_THEME);
        addTile("⚡",  "Slash", ACTION_QUICK_PANEL);
        addTile("🗂",  "History", ACTION_HISTORY);
        addTile("⚙",   "Settings", ACTION_SETTINGS);
        addTile("🎤", "Voice", ACTION_VOICE);
        addTile("🌐", "Translate", ACTION_TRANSLATE);
        addTile("😊", "Emoji", ACTION_EMOJI);
        addTile("↩",   "Undo", ACTION_UNDO);
        applyThemeIfPresent();
    }

    public void applyTheme(KeyboardTheme theme) {
        this.theme = theme;
        applyThemeIfPresent();
    }

    private void applyThemeIfPresent() {
        if (theme == null) return;
        setBackgroundColor(theme.background);
        header.setTextColor(theme.bannerText);
        backButton.applyTheme(theme);
        for (int i = 0; i < topIconRow.getChildCount(); i++) {
            View v = topIconRow.getChildAt(i);
            if (v instanceof GlyphIcon) ((GlyphIcon) v).applyTheme(theme);
        }
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            if (v instanceof Tile) ((Tile) v).applyTheme(theme);
        }
    }

    private void addQuickIcon(int glyph, int actionId) {
        GlyphIcon icon = new GlyphIcon(getContext(), glyph);
        icon.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (callbacks != null) callbacks.onAction(actionId);
        });
        LayoutParams lp = new LayoutParams(dp(40), dp(40));
        lp.rightMargin = dp(6);
        topIconRow.addView(icon, lp);
    }

    private void addTile(String emoji, String label, int actionId) {
        Tile tile = new Tile(getContext(), emoji, label);
        tile.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (callbacks != null) callbacks.onAction(actionId);
        });
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        int margin = dp(4);
        lp.setMargins(margin, margin, margin, margin);
        tile.setLayoutParams(lp);
        grid.addView(tile);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** A green circular back button that mirrors the Enter circle on the keys. */
    private static class CircleBackButton extends FrameLayout {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CircleBackButton(Context c) {
            super(c);
            setClickable(true);
            setFocusable(true);
            setWillNotDraw(false);
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(0xFF15803D);
            arrowPaint.setStyle(Paint.Style.STROKE);
            arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            arrowPaint.setStrokeJoin(Paint.Join.ROUND);
            arrowPaint.setColor(0xFFFFFFFF);
        }

        void applyTheme(KeyboardTheme theme) {
            fillPaint.setColor(theme.enterFill);
            arrowPaint.setColor(theme.enterIcon);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) / 2f - dp(2);
            float cx = w / 2f, cy = h / 2f;
            c.drawCircle(cx, cy, r, fillPaint);
            arrowPaint.setStrokeWidth(dp(2.2f));
            float arm = r * 0.45f;
            // ← arrow: stem and two arrowhead legs.
            c.drawLine(cx - arm, cy, cx + arm, cy, arrowPaint);
            c.drawLine(cx - arm, cy, cx - arm * 0.3f, cy - arm * 0.55f, arrowPaint);
            c.drawLine(cx - arm, cy, cx - arm * 0.3f, cy + arm * 0.55f, arrowPaint);
        }

        private float dp(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    getResources().getDisplayMetrics());
        }
    }

    /** Small circular icon button shown in the top row next to back. */
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
            glyphPaint.setStyle(Paint.Style.STROKE);
            glyphPaint.setStrokeCap(Paint.Cap.ROUND);
            glyphPaint.setStrokeJoin(Paint.Join.ROUND);
        }

        void applyTheme(KeyboardTheme theme) {
            fillPaint.setColor(theme.chipFill);
            glyphPaint.setColor(theme.suggestionText);
            invalidate();
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
                case GLYPH_HISTORY: drawHistory(c, cx, cy, r); break;
                case GLYPH_SETTINGS: drawGear(c, cx, cy, r); break;
                case GLYPH_MIC: drawMic(c, cx, cy, r); break;
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
            // tick marks: clock hands.
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

    /** A grid tile: rounded rect background with a single emoji glyph + label below. */
    private static class Tile extends LinearLayout {
        private final TextView emojiView;
        private final TextView labelView;
        private final GradientDrawable bg;
        private final TextView background;

        Tile(Context c, String emoji, String label) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setClickable(true);
            setFocusable(true);

            // Use a TextView purely for the rounded background; cheaper than a drawable nest.
            background = new TextView(c);
            // Outer container provides the touch target and stack; the icon "card" is
            // a smaller rounded square inside it.
            int padInner = (int) dp(4);
            setPadding(0, padInner, 0, padInner);

            FrameLayout iconCard = new FrameLayout(c);
            bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(14));
            bg.setColor(0xFFE8F5EE);
            iconCard.setBackground(bg);
            iconCard.setMinimumHeight((int) dp(64));

            emojiView = new TextView(c);
            emojiView.setText(emoji);
            emojiView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
            emojiView.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams emojiLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            emojiLp.gravity = Gravity.CENTER;
            int cardPad = (int) dp(14);
            iconCard.setPadding(cardPad, cardPad, cardPad, cardPad);
            iconCard.addView(emojiView, emojiLp);

            addView(iconCard, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));

            labelView = new TextView(c);
            labelView.setText(label);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            labelView.setGravity(Gravity.CENTER);
            labelView.setPadding(0, (int) dp(6), 0, 0);
            labelView.setTypeface(labelView.getTypeface(), Typeface.NORMAL);
            addView(labelView, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
        }

        void applyTheme(KeyboardTheme theme) {
            bg.setColor(theme.chipFill);
            labelView.setTextColor(theme.bannerText);
            background.setTextColor(theme.bannerText);
        }

        private float dp(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    getResources().getDisplayMetrics());
        }
    }
}
