package com.prince.turtlekeyboard.ime.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.ai.AiAssistPresets;
import com.prince.turtlekeyboard.input.InputTarget;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

import java.util.List;

/**
 * Strip shown above the keys when the user taps the ✨ trigger. Contains:
 * <ul>
 *   <li>preset chips that fire a built-in system prompt on tap,</li>
 *   <li>a custom-prompt field that the user can type into using the live keyboard.</li>
 * </ul>
 *
 * <p>Key routing: when the custom field is "active" (user tapped it), the IME forwards
 * printable keys and DELETE to {@link #appendChar(char)} / {@link #backspaceInput()}
 * instead of the host editor. Mirrors the {@code EmojiPanelView} search pattern.</p>
 */
public class AiAssistPanelView extends LinearLayout implements InputTarget {

    public interface OnRunListener { void onRun(String systemPrompt); }
    public interface OnCloseListener { void onClose(); }

    /** Mirrors EmojiPanelView's search-state pattern: when the user taps the custom-prompt
     *  field the panel shrinks to a compose-friendly height and the IME re-shows the keys
     *  below. Closes restore the full panel and hide keys again. */
    public interface OnComposeStateListener {
        void onEnterCompose();
        void onExitCompose();
    }

    private static final int BG = 0xFF111111;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;
    private static final int TEXT_SUBTLE = 0x99FFFFFF;
    private static final int CHIP_FILL = 0x14FFFFFF;
    private static final int CHIP_STROKE = 0x33FFFFFF;
    private static final int FIELD_FILL = 0x14FFFFFF;
    private static final int FIELD_STROKE = 0x22FFFFFF;
    private static final int FIELD_STROKE_ACTIVE = 0xFF15803D;
    private static final int RUN_ACCENT = 0xFF15803D;

    private static final long CARET_BLINK_PERIOD_MS = 1100L;

    private LinearLayout chipsRow;
    private TextView fieldView;
    private TextView runButton;
    private GradientDrawable fieldBg;
    @Nullable private ValueAnimator caretBlinker;

    @Nullable private OnRunListener runListener;
    @Nullable private OnCloseListener closeListener;
    @Nullable private InputTarget.ActiveChangeListener inputModeListener;
    @Nullable private OnComposeStateListener composeStateListener;
    /** Set by the IME to the full keyboard-area height; the browse-mode size. */
    private int browseHeightPx = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int COMPOSE_HEIGHT_DP = 140;
    private boolean inflight;
    private boolean inputActive;
    private final StringBuilder buffer = new StringBuilder();
    @Nullable private KeyboardTheme theme;
    private boolean caretOn = true;

    public AiAssistPanelView(Context context) { this(context, null); }

    public AiAssistPanelView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        int padH = dp(12);
        setPadding(padH, dp(8), padH, dp(10));

        addView(buildHeader(context));
        addView(buildChipsRow(context));
        addView(buildFieldRow(context));
    }

    private View buildHeader(Context ctx) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("✨ AI Assist");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(ctx);
        close.setText("×");
        close.setTextColor(TEXT_PRIMARY);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        close.setTypeface(close.getTypeface(), Typeface.BOLD);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), dp(2), dp(10), dp(2));
        close.setClickable(true);
        close.setFocusable(true);
        close.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (closeListener != null) closeListener.onClose();
        });
        bar.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        bar.setLayoutParams(lp);
        return bar;
    }

    private View buildChipsRow(Context ctx) {
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        chipsRow = new LinearLayout(ctx);
        chipsRow.setOrientation(HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);

        List<AiAssistPresets.Preset> presets = AiAssistPresets.DEFAULTS;
        for (int i = 0; i < presets.size(); i++) {
            AiAssistPresets.Preset p = presets.get(i);
            TextView chip = makeChip(ctx, p.label);
            chip.setOnClickListener(v -> {
                if (inflight) return;
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                exitInputMode();
                if (runListener != null) {
                    runListener.onRun(p.systemPrompt + AiAssistPresets.OUTPUT_RULES);
                }
            });
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) cLp.leftMargin = dp(8);
            chipsRow.addView(chip, cLp);
        }

        scroll.addView(chipsRow, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sLp.bottomMargin = dp(10);
        scroll.setLayoutParams(sLp);
        return scroll;
    }

    private View buildFieldRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        fieldView = new TextView(ctx);
        fieldBg = new GradientDrawable();
        fieldBg.setShape(GradientDrawable.RECTANGLE);
        fieldBg.setColor(FIELD_FILL);
        fieldBg.setStroke(dp(1), FIELD_STROKE);
        fieldBg.setCornerRadius(dp(18));
        fieldView.setBackground(fieldBg);
        fieldView.setTextColor(TEXT_PRIMARY);
        fieldView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        fieldView.setSingleLine(true);
        fieldView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        fieldView.setIncludeFontPadding(false);
        fieldView.setPadding(dp(14), dp(10), dp(14), dp(10));
        fieldView.setClickable(true);
        fieldView.setFocusable(true);
        fieldView.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            enterInputMode();
        });
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        fLp.rightMargin = dp(8);
        row.addView(fieldView, fLp);

        runButton = new TextView(ctx);
        runButton.setText("Run");
        runButton.setTextColor(Color.WHITE);
        runButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        runButton.setTypeface(runButton.getTypeface(), Typeface.BOLD);
        runButton.setGravity(Gravity.CENTER);
        runButton.setPadding(dp(16), dp(9), dp(16), dp(9));
        runButton.setBackground(pill(RUN_ACCENT, dp(18)));
        runButton.setClickable(true);
        runButton.setFocusable(true);
        runButton.setOnClickListener(v -> {
            if (inflight) return;
            String text = buffer.toString().trim();
            if (text.isEmpty()) return;
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (runListener != null) {
                runListener.onRun(text + AiAssistPresets.OUTPUT_RULES);
            }
        });
        row.addView(runButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        renderField();
        return row;
    }

    private TextView makeChip(Context ctx, String label) {
        TextView chip = new TextView(ctx);
        chip.setText(label);
        chip.setTextColor(TEXT_PRIMARY);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        chip.setSingleLine(true);
        chip.setIncludeFontPadding(false);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(CHIP_FILL);
        bg.setStroke(dp(1), CHIP_STROKE);
        bg.setCornerRadius(dp(16));
        chip.setBackground(bg);
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    private GradientDrawable pill(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Wires callbacks + clears state. View-tree mount and visibility are owned by the
     *  IME via PanelSlot — this is just state initialization. */
    public void show(@Nullable OnRunListener onRun, @Nullable OnCloseListener onClose) {
        this.runListener = onRun;
        this.closeListener = onClose;
        this.inflight = false;
        this.buffer.setLength(0);
        setAlpha(1f);
        chipsRow.setVisibility(VISIBLE);
        exitInputMode();
        renderField();
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
    }

    /** Resets state. The IME removes us from the view tree via PanelSlot.hide() separately. */
    public void hide() {
        resetAfterHide();
    }

    /** Dim chips/run while a rewrite is in flight; ignored taps until cleared. */
    public void setInflight(boolean inflight) {
        this.inflight = inflight;
        setAlpha(inflight ? 0.55f : 1f);
        renderField();
    }

    /** True while keystrokes should flow into the custom-prompt buffer instead of the host editor. */
    public boolean isInputActive() { return inputActive; }

    public void setOnInputActiveChangedListener(@Nullable InputTarget.ActiveChangeListener l) {
        this.inputModeListener = l;
    }

    public void setOnComposeStateListener(@Nullable OnComposeStateListener l) {
        this.composeStateListener = l;
    }

    /** Full keyboard-area height the IME wants us to occupy in browse mode. */
    public void setBrowseHeightPx(int px) {
        this.browseHeightPx = px;
        if (!inputActive) setPanelHeight(px);
    }

    private void setPanelHeight(int px) {
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null || lp.height == px) return;
        lp.height = px;
        setLayoutParams(lp);
    }

    @Override
    public void appendChar(char c) {
        if (!inputActive) return;
        buffer.append(c);
        renderField();
    }

    @Override
    public void onDone() {
        if (inflight) return;
        String text = buffer.toString().trim();
        if (text.isEmpty()) return;
        if (runListener != null) {
            runListener.onRun(text + AiAssistPresets.OUTPUT_RULES);
        }
    }

    @Override
    public void onBackspace() {
        if (!inputActive) return;
        if (buffer.length() == 0) {
            exitInputMode();
            return;
        }
        buffer.deleteCharAt(buffer.length() - 1);
        renderField();
    }

    private void enterInputMode() {
        if (inputActive) return;
        inputActive = true;
        fieldBg.setStroke(dp(2),
                theme != null ? theme.accent : FIELD_STROKE_ACTIVE);
        startCaretBlink();
        renderField();
        chipsRow.setVisibility(GONE);
        setPanelHeight(dp(COMPOSE_HEIGHT_DP));
        if (composeStateListener != null) composeStateListener.onEnterCompose();
        if (inputModeListener != null) inputModeListener.onActiveChanged(this, true);
    }

    private void exitInputMode() {
        if (!inputActive) {
            stopCaretBlink();
            return;
        }
        inputActive = false;
        fieldBg.setStroke(dp(1), FIELD_STROKE);
        stopCaretBlink();
        renderField();
        chipsRow.setVisibility(VISIBLE);
        setPanelHeight(browseHeightPx);
        if (composeStateListener != null) composeStateListener.onExitCompose();
        if (inputModeListener != null) inputModeListener.onActiveChanged(this, false);
    }

    private void renderField() {
        String typed = buffer.toString();
        if (typed.isEmpty() && !inputActive) {
            fieldView.setText("Tell AI what to do…");
            fieldView.setTextColor(TEXT_SUBTLE);
        } else {
            String display = typed + (inputActive && caretOn ? "|" : "");
            fieldView.setText(display);
            fieldView.setTextColor(TEXT_PRIMARY);
        }
        boolean hasInput = !typed.trim().isEmpty();
        boolean runEnabled = hasInput && !inflight;
        runButton.setEnabled(runEnabled);
        runButton.setAlpha(runEnabled ? 1f : 0.45f);
    }

    private void startCaretBlink() {
        stopCaretBlink();
        caretOn = true;
        caretBlinker = ValueAnimator.ofFloat(0f, 2f);
        caretBlinker.setDuration(CARET_BLINK_PERIOD_MS);
        caretBlinker.setRepeatCount(ValueAnimator.INFINITE);
        caretBlinker.setInterpolator(new LinearInterpolator());
        caretBlinker.addUpdateListener(a -> {
            boolean on = ((float) a.getAnimatedValue()) < 1f;
            if (on != caretOn) {
                caretOn = on;
                renderField();
            }
        });
        caretBlinker.start();
    }

    private void stopCaretBlink() {
        if (caretBlinker != null) {
            caretBlinker.cancel();
            caretBlinker = null;
        }
        caretOn = true;
    }

    private void resetAfterHide() {
        // The IME removes us from the view tree via PanelSlot.hide(); this is just state cleanup
        // so the next show() starts fresh.
        setAlpha(1f);
        chipsRow.setVisibility(VISIBLE);
        this.runListener = null;
        this.closeListener = null;
        this.composeStateListener = null;
        this.inflight = false;
        exitInputMode();
        buffer.setLength(0);
        renderField();
    }

    public void applyTheme(KeyboardTheme theme) {
        this.theme = theme;
        if (theme != null) {
            setBackgroundColor(theme.background);
            runButton.setBackground(pill(theme.accent, dp(18)));
            if (inputActive) {
                fieldBg.setStroke(dp(2), theme.accent);
            }
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
