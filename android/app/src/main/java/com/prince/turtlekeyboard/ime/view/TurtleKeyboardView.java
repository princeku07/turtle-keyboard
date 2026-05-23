package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;

import com.prince.turtlekeyboard.theme.KeyboardTheme;

/**
 * KeyboardView that fully self-paints every key (bypasses {@code super.onDraw})
 * so face color, label color, digit hint, and the circular Enter button can be
 * varied per key. Touch and pressed-state handling stay with the parent.
 */
public class TurtleKeyboardView extends KeyboardView {

    private static final int CODE_SHIFT = -1;
    private static final int CODE_MODE = -2;
    private static final int CODE_ENTER = -4;
    private static final int CODE_BACKSPACE = -5;
    private static final int CODE_EMOJI = -11;
    private static final int CODE_SPACE = 32;
    private static final int CODE_COMMA = 44;
    private static final int CODE_PERIOD = 46;

    public interface ModeKeyLongPressListener {
        void onModeKeyLongPress();
    }

    public interface BackspaceLongPressListener {
        void onBackspaceLongPress();
    }

    private ModeKeyLongPressListener modeKeyLongPressListener;
    private BackspaceLongPressListener backspaceLongPressListener;

    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint enterFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint enterIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private int keyFace = 0xFFFFFFFF;
    private int functionFace = 0xFFC8E8D5;
    private int pressedFace = 0xFFA6D9BC;
    private int enterFill = 0xFF15803D;
    private int enterIcon = 0xFFFFFFFF;
    private final float keyCornerPx;
    private final float keyInsetPx;
    private final float labelTextPx;
    private final float functionLabelTextPx;
    private final float hintTextPx;

    public TurtleKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        keyCornerPx = dp(8);
        keyInsetPx = dp(3);
        labelTextPx = sp(26);
        functionLabelTextPx = sp(17);
        hintTextPx = sp(11);

        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(0xFF0C0C0C);
        labelPaint.setTypeface(Typeface.DEFAULT);

        hintPaint.setTextAlign(Paint.Align.RIGHT);
        hintPaint.setTextSize(hintTextPx);
        hintPaint.setColor(0xFF6B6B6B);

        enterFillPaint.setStyle(Paint.Style.FILL);
        enterIconPaint.setStyle(Paint.Style.STROKE);
        enterIconPaint.setStrokeCap(Paint.Cap.ROUND);
        enterIconPaint.setStrokeJoin(Paint.Join.ROUND);
        enterIconPaint.setStrokeWidth(dp(2.2f));
    }

    public TurtleKeyboardView(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs);
    }

    public void setModeKeyLongPressListener(ModeKeyLongPressListener l) {
        this.modeKeyLongPressListener = l;
    }

    public void setBackspaceLongPressListener(BackspaceLongPressListener l) {
        this.backspaceLongPressListener = l;
    }

    @Override
    protected boolean onLongPress(Keyboard.Key popupKey) {
        int code = codeOf(popupKey);
        if (code == CODE_MODE && modeKeyLongPressListener != null) {
            modeKeyLongPressListener.onModeKeyLongPress();
            return true;
        }
        if (code == CODE_BACKSPACE && backspaceLongPressListener != null) {
            backspaceLongPressListener.onBackspaceLongPress();
            return true;
        }
        // Long-press on a letter key with a single popupCharacter commits the alt directly.
        // Returning true aborts KeyboardView's queued release so the base letter doesn't also commit.
        if (!isFunctionKey(popupKey)
                && popupKey.popupCharacters != null
                && popupKey.popupCharacters.length() == 1) {
            char alt = popupKey.popupCharacters.charAt(0);
            OnKeyboardActionListener l = getOnKeyboardActionListener();
            if (l != null) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                l.onKey(alt, new int[]{ alt });
                return true;
            }
        }
        return super.onLongPress(popupKey);
    }

    public void applyTheme(KeyboardTheme theme) {
        this.keyFace = theme.keyFace;
        this.functionFace = theme.functionFace;
        this.pressedFace = theme.pressedFace;
        this.enterFill = theme.enterFill;
        this.enterIcon = theme.enterIcon;
        labelPaint.setColor(theme.keyText);
        hintPaint.setColor(theme.hintText);
        setBackgroundColor(theme.background);
        invalidate();
    }

    @Override
    public void onDraw(Canvas canvas) {
        // Intentionally skip super.onDraw — touch/state still flow through the parent normally.
        Keyboard kb = getKeyboard();
        if (kb == null) return;
        int padLeft = getPaddingLeft();
        int padTop = getPaddingTop();
        for (Keyboard.Key key : kb.getKeys()) {
            paintKey(canvas, key, padLeft, padTop);
        }
    }

    private void paintKey(Canvas c, Keyboard.Key key, int padLeft, int padTop) {
        float left = padLeft + key.x + keyInsetPx;
        float top = padTop + key.y + keyInsetPx;
        float right = padLeft + key.x + key.width - keyInsetPx;
        float bottom = padTop + key.y + key.height - keyInsetPx;

        if (codeOf(key) == CODE_ENTER) {
            paintEnter(c, left, top, right, bottom, key.pressed);
            return;
        }

        Keyboard kb = getKeyboard();
        boolean shifted = kb != null && kb.isShifted();
        int code = codeOf(key);
        boolean isShiftKey = code == CODE_SHIFT;

        tmp.set(left, top, right, bottom);
        int face = isFunctionKey(key) ? functionFace : keyFace;
        // Shift adopts the brand accent fill while shifted/caps-locked as an "on" cue.
        if (isShiftKey && shifted) face = enterFill;
        if (key.pressed) face = pressedFace;
        facePaint.setColor(face);
        c.drawRoundRect(tmp, keyCornerPx, keyCornerPx, facePaint);

        CharSequence label = key.label;
        if (label != null && label.length() > 0) {
            CharSequence drawLabel = label;
            if (shifted && label.length() == 1) {
                char ch = label.charAt(0);
                if (Character.isLowerCase(ch)) {
                    drawLabel = String.valueOf(Character.toUpperCase(ch));
                }
            }
            boolean isShortLabel = drawLabel.length() <= 1;
            labelPaint.setTextSize(isShortLabel ? labelTextPx : functionLabelTextPx);
            labelPaint.setFakeBoldText(!isShortLabel);
            int prevColor = labelPaint.getColor();
            if (isShiftKey && shifted) labelPaint.setColor(enterIcon);
            float cx = (left + right) / 2f;
            float baseline = (top + bottom) / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f;
            c.drawText(drawLabel, 0, drawLabel.length(), cx, baseline, labelPaint);
            labelPaint.setColor(prevColor);
        }

        if (key.popupCharacters != null && key.popupCharacters.length() > 0
                && !isFunctionKey(key)) {
            char hint = key.popupCharacters.charAt(0);
            float x = right - dp(6);
            float y = top + dp(13);
            c.drawText(String.valueOf(hint), x, y, hintPaint);
        }
    }

    private void paintEnter(Canvas c, float left, float top, float right, float bottom, boolean pressed) {
        float cx = (left + right) / 2f;
        float cy = (top + bottom) / 2f;
        float r = Math.min(right - left, bottom - top) / 2f;
        enterFillPaint.setColor(pressed ? darken(enterFill) : enterFill);
        c.drawCircle(cx, cy, r, enterFillPaint);

        // Hand-drawn ↵ glyph reads better than the unicode character at this size.
        float arm = Math.min(right - left, bottom - top) * 0.18f;
        enterIconPaint.setColor(enterIcon);
        c.drawLine(cx + arm, cy - arm, cx + arm, cy + arm * 0.2f, enterIconPaint);
        c.drawLine(cx + arm, cy + arm * 0.2f, cx - arm, cy + arm * 0.2f, enterIconPaint);
        c.drawLine(cx - arm, cy + arm * 0.2f, cx - arm * 0.4f, cy - arm * 0.3f, enterIconPaint);
        c.drawLine(cx - arm, cy + arm * 0.2f, cx - arm * 0.4f, cy + arm * 0.7f, enterIconPaint);
    }

    private boolean isFunctionKey(Keyboard.Key key) {
        switch (codeOf(key)) {
            case CODE_SHIFT:
            case CODE_MODE:
            case CODE_BACKSPACE:
            case CODE_EMOJI:
            case CODE_COMMA:
            case CODE_PERIOD:
                return true;
            default:
                return false;
        }
    }

    private static int codeOf(Keyboard.Key key) {
        if (key.codes == null || key.codes.length == 0) return Integer.MIN_VALUE;
        return key.codes[0];
    }

    private static int darken(int color) {
        int a = (color >>> 24) & 0xFF;
        int r = Math.max(0, ((color >> 16) & 0xFF) - 24);
        int g = Math.max(0, ((color >> 8) & 0xFF) - 24);
        int b = Math.max(0, (color & 0xFF) - 24);
        return Color.argb(a, r, g, b);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }
}
