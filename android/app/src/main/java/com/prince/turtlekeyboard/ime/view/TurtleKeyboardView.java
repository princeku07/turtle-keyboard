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
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;

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

    private ModeKeyLongPressListener modeKeyLongPressListener;

    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint enterFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint enterIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Separate text paint for the Enter button's action label (Send / Search / etc.) —
     *  keeps state isolated from {@link #labelPaint} so per-key painting doesn't have
     *  to save/restore color and size around the action key. */
    private final Paint enterLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    /** Active editor action (EditorInfo.IME_ACTION_*), or 0 for the default ↵ glyph. */
    private int editorAction;
    /** True when shift is sticky (double-tap caps-lock). Drives the ⇧ → ⇪ glyph swap
     *  so the user can tell whether they're in shift-once or caps-lock at a glance. */
    private boolean capsLocked;
    /** Cached space key for {@link #biasSpaceHitZone(MotionEvent)} — refreshed on
     *  Keyboard identity change. */
    private Keyboard.Key cachedSpace;
    private Keyboard cachedSpaceFor;

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
        // Framework default: getKeyIndices returns NOT_A_KEY for touches in row gaps,
        // so onPress fires on DOWN (edge-of-key tolerance) but the UP no-ops — haptic
        // without commit. Enabling proximity correction makes the framework fall back
        // to the nearest key on UP, so taps "between n and space" land on whichever
        // is closer instead of being dropped.
        setProximityCorrectionEnabled(true);
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

        enterLabelPaint.setTextAlign(Paint.Align.CENTER);
        enterLabelPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    /** Updates the glyph drawn on the Enter button. Pass an
     *  {@code EditorInfo.IME_ACTION_*} value or 0 for the default ↵. */
    public void setEditorAction(int actionId) {
        if (editorAction == actionId) return;
        editorAction = actionId;
        invalidate();
    }

    /** Sets caps-lock state so the shift key shows ⇪ instead of ⇧. */
    public void setCapsLocked(boolean locked) {
        if (capsLocked == locked) return;
        capsLocked = locked;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_MOVE) {
            biasSpaceHitZone(ev);
        }
        return super.onTouchEvent(ev);
    }

    /** Snaps touches in the small band directly above the space bar into space.
     *  Framework proximity correction uses distance-to-center, which loses to row-3
     *  keys whose centers are vertically closer than space's (deeper) center even
     *  when the user clearly meant to hit the space bar. Bias band is slightly
     *  wider than the row gap so thumbs that land 1–2 px inside the row above are
     *  still forgiven. Only applied to space — other keys keep the default rule. */
    private void biasSpaceHitZone(MotionEvent ev) {
        Keyboard kb = getKeyboard();
        if (kb == null) return;
        if (kb != cachedSpaceFor) {
            cachedSpace = null;
            for (Keyboard.Key k : kb.getKeys()) {
                if (k.codes != null && k.codes.length > 0 && k.codes[0] == CODE_SPACE) {
                    cachedSpace = k;
                    break;
                }
            }
            cachedSpaceFor = kb;
        }
        if (cachedSpace == null) return;
        Keyboard.Key space = cachedSpace;
        float px = ev.getX() - getPaddingLeft();
        float py = ev.getY() - getPaddingTop();
        // Already inside space — nothing to do.
        if (px >= space.x && px < space.x + space.width
                && py >= space.y && py < space.y + space.height) return;
        // In the band above space and horizontally within space's bounds — snap down.
        float band = dp(8);
        if (px >= space.x && px < space.x + space.width
                && py < space.y && py >= space.y - band) {
            ev.setLocation(ev.getX(), space.y + getPaddingTop() + 1);
        }
    }

    public TurtleKeyboardView(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs);
    }

    public void setModeKeyLongPressListener(ModeKeyLongPressListener l) {
        this.modeKeyLongPressListener = l;
    }

    @Override
    protected boolean onLongPress(Keyboard.Key popupKey) {
        int code = codeOf(popupKey);
        if (code == CODE_MODE && modeKeyLongPressListener != null) {
            modeKeyLongPressListener.onModeKeyLongPress();
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
                // Returning true makes the framework set mAbortKey, which skips the
                // UP-path detectAndSendKey — so onRelease never fires for the original
                // key and the IME's KeyPreviewPopup stays visible (the "stuck preview"
                // symptom). Fire onRelease explicitly so the popup is dismissed.
                l.onRelease(alt);
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
            // ⇪ (CAPS LOCK arrow with bar) when sticky, ⇧ when shift-once.
            if (isShiftKey && capsLocked) {
                drawLabel = "⇪";
            } else if (shifted && label.length() == 1) {
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

        String label = labelForAction(editorAction);
        if (label != null) {
            // Action-bearing field (search / send / go / etc.) — short label inside the disc.
            enterLabelPaint.setColor(enterIcon);
            enterLabelPaint.setTextSize(hintTextPx * 1.4f);
            // Shrink until it fits inside the disc, with a floor so we never go invisible.
            float maxWidth = r * 1.6f;
            float minSize = dp(8);
            while (enterLabelPaint.measureText(label) > maxWidth
                    && enterLabelPaint.getTextSize() > minSize) {
                enterLabelPaint.setTextSize(enterLabelPaint.getTextSize() - dp(1));
            }
            float baseline = cy - (enterLabelPaint.descent() + enterLabelPaint.ascent()) / 2f;
            c.drawText(label, cx, baseline, enterLabelPaint);
            return;
        }

        // Hand-drawn ↵ glyph reads better than the unicode character at this size.
        float arm = Math.min(right - left, bottom - top) * 0.18f;
        enterIconPaint.setColor(enterIcon);
        c.drawLine(cx + arm, cy - arm, cx + arm, cy + arm * 0.2f, enterIconPaint);
        c.drawLine(cx + arm, cy + arm * 0.2f, cx - arm, cy + arm * 0.2f, enterIconPaint);
        c.drawLine(cx - arm, cy + arm * 0.2f, cx - arm * 0.4f, cy - arm * 0.3f, enterIconPaint);
        c.drawLine(cx - arm, cy + arm * 0.2f, cx - arm * 0.4f, cy + arm * 0.7f, enterIconPaint);
    }

    private static String labelForAction(int action) {
        switch (action) {
            case EditorInfo.IME_ACTION_GO:       return "Go";
            case EditorInfo.IME_ACTION_SEARCH:   return "Search";
            case EditorInfo.IME_ACTION_SEND:     return "Send";
            case EditorInfo.IME_ACTION_NEXT:     return "Next";
            case EditorInfo.IME_ACTION_DONE:     return "Done";
            case EditorInfo.IME_ACTION_PREVIOUS: return "Prev";
            default: return null;
        }
    }

    private boolean isFunctionKey(Keyboard.Key key) {
        // PERIOD is no longer flagged: in qwerty.xml it carries a popupCharacters=","
        // hint and accepts long-press to commit ',', so it needs the regular-key paint
        // (for the corner hint) and the non-function long-press path (for the alt commit).
        switch (codeOf(key)) {
            case CODE_SHIFT:
            case CODE_MODE:
            case CODE_BACKSPACE:
            case CODE_EMOJI:
            case CODE_COMMA:
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
