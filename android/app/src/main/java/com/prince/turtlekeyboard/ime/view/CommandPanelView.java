package com.prince.turtlekeyboard.ime.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.theme.KeyboardTheme;

/**
 * Inline panel shown above the keyboard while the user is typing a command
 * argument (e.g. the URL after "/search "). Mirrors the keystrokes captured
 * by the composer and exposes a "Go" button that dispatches the command.
 *
 * <p>Polish details:
 * <ul>
 *   <li>The leading icon + label are nudged ~2 dp up so they sit just above
 *       the query baseline — small typographic cue that they're chrome
 *       around the editable text rather than peers of it.</li>
 *   <li>A blinking caret renders inside the query area at the composer's
 *       cursor position; tap or drag on the query to move the caret, and
 *       subsequent keystrokes / backspaces / pastes operate at that
 *       position (via {@link com.prince.turtlekeyboard.command.CommandComposer}).</li>
 *   <li>Long-press still pastes the clipboard. The drag-to-move gesture
 *       coexists with long-press via a {@link GestureDetector} so neither
 *       fights the other for the same touch sequence.</li>
 * </ul>
 */
public class CommandPanelView extends LinearLayout {

    public interface OnGoListener { void onGo(); }
    public interface OnPasteListener { void onPaste(String text); }
    /** Fires when the user taps or drags inside the query area to position
     *  the prompt caret. {@code pos} is the character offset within the
     *  current query string. The IME forwards this to
     *  {@code CommandComposer#setPromptCursor(int)}. */
    public interface OnCursorMoveListener { void onMove(int pos); }

    // Surface palette — black panel, translucent-white chips, light glyphs.
    private static final int BG = 0xFF000000;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;
    private static final int CHIP_FILL_SUBTLE = 0x14FFFFFF;

/** Caret blink period — 1100 ms total (550 on / 550 off) feels closer to
     *  a system-style text-cursor than the harder 500/500 default. */
    private static final long CARET_BLINK_PERIOD_MS = 1100L;

    private ImageView stagedThumb;
    private TextView stagedClose;
    private TextView uploadButton;
    /** Discoverable paste affordance shown when the clipboard has text. Tapping
     *  pastes into the prompt buffer via the existing {@link OnPasteListener}
     *  (which the IME wires to {@code composer.appendString}). The long-press
     *  paste on the query area still works; this button is the explicit /
     *  one-handed equivalent. */
    private TextView pasteButton;
    private TextView labelView;

    /** Wraps the query TextView + an overlaid caret View. The wrapper takes
     *  the weight=1 slot in the horizontal row; touch + drag events for
     *  caret positioning live on it. */
    private FrameLayout queryWrapper;
    private TextView queryView;
    private View caretView;
    /** Small filled circle that appears below the caret while the user is
     *  actively dragging it — a finger-friendly "you're moving this" handle.
     *  Hidden when the touch ends; the bare blinking caret remains. */
    private View dragHandleView;
    private boolean dragging;
    @Nullable private ValueAnimator caretBlinker;

    private TextView goButton;
    private OnGoListener onGo;
    private OnPasteListener onPaste;
    @Nullable private OnCursorMoveListener onCursorMove;
    @Nullable private Runnable onStagedClear;
    @Nullable private Runnable onUpload;
    @Nullable private KeyboardTheme theme;
    private String hint = "type and tap →";
    @Nullable private String clipboardText;

    /** Mirror of the composer's prompt cursor. We re-read it from
     *  {@link #show(String, String, String, int)} / {@link #update(String, int)}
     *  rather than tracking edits ourselves, so the source of truth stays
     *  in the composer. */
    private int cursorPos;
    /** Current rendered query string — kept so we can re-layout the caret
     *  on size-change without going through {@link #update}. */
    private String currentQuery = "";

    public CommandPanelView(Context context) { super(context); init(); }
    public CommandPanelView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setOrientation(HORIZONTAL);
        setVisibility(GONE);
        setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(12), padV = dp(10);
        setPadding(padH, padV, padH, padV);
        GradientDrawable surface = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ 0xFF1A1A1A, BG });
        setBackground(surface);

        // Leading slot — staged thumb / × / upload / label, all of which
        // get the LEADING_LIFT_DP nudge up so they read as caption-style
        // chrome above the editable query baseline.
        stagedThumb = new ImageView(getContext());
        stagedThumb.setVisibility(GONE);
        stagedThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final int thumbCorner = dp(6);
        stagedThumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), thumbCorner);
            }
        });
        stagedThumb.setClipToOutline(true);
LayoutParams thumbLp = new LayoutParams(dp(32), dp(32));
        thumbLp.rightMargin = dp(6);
        addView(stagedThumb, thumbLp);

        stagedClose = makeCloseButton();
stagedClose.setOnClickListener(v -> {
            Runnable r = onStagedClear;
            setStagedImage(null, null);
            if (r != null) r.run();
        });
        LayoutParams closeLp = new LayoutParams(dp(20), dp(20));
        closeLp.rightMargin = dp(10);
        addView(stagedClose, closeLp);

        uploadButton = new TextView(getContext());
        uploadButton.setVisibility(GONE);
        uploadButton.setText("📷");
        uploadButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        uploadButton.setGravity(Gravity.CENTER);
        uploadButton.setIncludeFontPadding(false);
        GradientDrawable upBg = new GradientDrawable();
        upBg.setShape(GradientDrawable.RECTANGLE);
        upBg.setColor(CHIP_FILL_SUBTLE);
        upBg.setCornerRadius(dp(6));
        uploadButton.setBackground(upBg);
        uploadButton.setClickable(true);
        uploadButton.setFocusable(true);
        uploadButton.setOnClickListener(v -> { if (onUpload != null) onUpload.run(); });
        LayoutParams upLp = new LayoutParams(dp(32), dp(32));
        upLp.rightMargin = dp(8);
        addView(uploadButton, upLp);

        // Paste shortcut — same chip styling as the upload affordance.
        // Visible only when the clipboard has text (via setPasteAvailable);
        // tap calls firePaste() which fires the OnPasteListener so the
        // clipboard content lands in the prompt buffer (composer.appendString
        // in the IME wiring), not the host editor.
        pasteButton = new TextView(getContext());
        pasteButton.setVisibility(GONE);
        pasteButton.setText("📋");
        pasteButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        pasteButton.setGravity(Gravity.CENTER);
        pasteButton.setIncludeFontPadding(false);
        GradientDrawable pasteBg = new GradientDrawable();
        pasteBg.setShape(GradientDrawable.RECTANGLE);
        pasteBg.setColor(CHIP_FILL_SUBTLE);
        pasteBg.setCornerRadius(dp(6));
        pasteButton.setBackground(pasteBg);
        pasteButton.setClickable(true);
        pasteButton.setFocusable(true);
        pasteButton.setOnClickListener(v -> firePaste());
        LayoutParams pasteLp = new LayoutParams(dp(32), dp(32));
        pasteLp.rightMargin = dp(8);
        addView(pasteButton, pasteLp);

        labelView = new TextView(getContext());
        labelView.setTextColor(TEXT_PRIMARY);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        labelView.setTypeface(labelView.getTypeface(), Typeface.BOLD);
        addView(labelView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // ── Query wrapper: TextView for the typed prompt + overlaid caret ──
        queryWrapper = new FrameLayout(getContext());
        LayoutParams qLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        addView(queryWrapper, qLp);

        queryView = new TextView(getContext());
        queryView.setTextColor(TEXT_PRIMARY);
        queryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        queryView.setSingleLine(true);
        queryView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        queryView.setPadding(dp(10), 0, dp(10), 0);
        queryView.setIncludeFontPadding(false);
        FrameLayout.LayoutParams qvLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        qvLp.gravity = Gravity.CENTER_VERTICAL;
        queryWrapper.addView(queryView, qvLp);

        // Caret — a 1.5 dp wide vertical bar that gets positioned over the
        // query via translationX. Height is set in layoutCaret() once we
        // know the line height. ValueAnimator toggles its alpha on a
        // square-wave so the cursor pulses without easing (system text
        // cursors don't fade smoothly).
        caretView = new View(getContext());
        caretView.setBackgroundColor(TEXT_PRIMARY);
        caretView.setVisibility(INVISIBLE);
        FrameLayout.LayoutParams caretLp = new FrameLayout.LayoutParams(
                dp(2), FrameLayout.LayoutParams.WRAP_CONTENT);
        caretLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        queryWrapper.addView(caretView, caretLp);

        // Drag handle — a small filled circle that sits below the caret line
        // while the user is touching the query area, like the cursor handle
        // in a standard Android text field. The wrapper's padding doesn't
        // give us much vertical room, so the handle is small (10 dp) and
        // gets translated downward by half its size + a hair so it sits
        // visually beneath the bar rather than overlapping it.
        dragHandleView = new View(getContext());
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setShape(GradientDrawable.OVAL);
        handleBg.setColor(TEXT_PRIMARY);
        dragHandleView.setBackground(handleBg);
        dragHandleView.setVisibility(INVISIBLE);
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(
                dp(10), dp(10));
        handleLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        queryWrapper.addView(dragHandleView, handleLp);

        // Touch handler — tap or drag positions the caret; long-press still
        // pastes. Routed through a GestureDetector so the two gestures
        // can coexist on the same touch slot without fighting.
        final GestureDetector detector = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) {
                        moveCaretToTouch(e.getX());
                        return true;
                    }
                    @Override public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                                       float dx, float dy) {
                        moveCaretToTouch(e2.getX());
                        return true;
                    }
                    @Override public void onLongPress(MotionEvent e) {
                        firePaste();
                    }
                });
        queryWrapper.setClickable(true);
        queryWrapper.setOnTouchListener((v, event) -> {
            boolean handled = detector.onTouchEvent(event);
            // Drag state flips on DOWN and resets on UP/CANCEL — the handle's
            // visibility tracks this directly so the user gets a clear
            // "you're moving the cursor" affordance only while their finger
            // is down.
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                if (!dragging) {
                    dragging = true;
                    pauseCaretBlink();
                }
                showDragHandle(true);
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                showDragHandle(false);
                // Resume the steady blink once the user has lifted off.
                startCaretBlink();
            }
            return handled;
        });

        goButton = new TextView(getContext());
        goButton.setText("➤");
        goButton.setTextColor(Color.WHITE);
        goButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        goButton.setTypeface(goButton.getTypeface(), Typeface.BOLD);
        goButton.setGravity(Gravity.CENTER);
        goButton.setPadding(dp(16), dp(8), dp(16), dp(8));
        goButton.setBackground(pillBackground(0xFF15803D, dp(18)));
        goButton.setClickable(true);
        goButton.setFocusable(true);
        goButton.setOnClickListener(v -> { if (onGo != null) onGo.onGo(); });
        addView(goButton, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    /** Translate a raw touch x (in queryWrapper coords) to a character offset
     *  within the current query string, then notify the IME. The IME forwards
     *  to {@code CommandComposer.setPromptCursor} which re-fires our
     *  {@link #update(String, int)} so the rendered caret follows. */
    private void moveCaretToTouch(float rawX) {
        if (onCursorMove == null) return;
        // queryView's left edge sits inside the wrapper after its own
        // setPadding; getOffsetForPosition expects local-to-textView x.
        float xInTv = rawX - queryView.getLeft();
        int offset = queryView.getOffsetForPosition(xInTv, queryView.getHeight() / 2f);
        if (offset < 0) offset = 0;
        if (offset > currentQuery.length()) offset = currentQuery.length();
        onCursorMove.onMove(offset);
    }

    private GradientDrawable pillBackground(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private TextView makeCloseButton() {
        TextView t = new TextView(getContext());
        t.setText("×");
        t.setTextColor(TEXT_PRIMARY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(CHIP_FILL_SUBTLE);
        t.setBackground(bg);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private boolean firePaste() {
        if (onPaste == null || clipboardText == null || clipboardText.isEmpty()) return false;
        onPaste.onPaste(clipboardText);
        return true;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    public void show(String label, String hint, String query, int cursorPos) {
        labelView.setText(label);
        this.hint = hint == null ? "" : hint;
        renderQuery(query, cursorPos);
        setVisibility(VISIBLE);
        startCaretBlink();
    }

    public void update(String query, int cursorPos) {
        renderQuery(query, cursorPos);
    }

    public void hide() {
        setVisibility(GONE);
        stopCaretBlink();
        clipboardText = null;
        setStagedImage(null, null);
        setUploadAction(null);
    }

    public void setPasteAvailable(@Nullable String text) {
        this.clipboardText = (text == null || text.isEmpty()) ? null : text;
        pasteButton.setVisibility(this.clipboardText != null ? VISIBLE : GONE);
    }

    public void setStagedImage(@Nullable Bitmap thumb, @Nullable Runnable onClear) {
        this.onStagedClear = onClear;
        if (thumb == null) {
            stagedThumb.setImageDrawable(null);
            stagedThumb.setVisibility(GONE);
            stagedClose.setVisibility(GONE);
        } else {
            stagedThumb.setImageBitmap(thumb);
            stagedThumb.setVisibility(VISIBLE);
            stagedClose.setVisibility(VISIBLE);
        }
        refreshUploadVisibility();
    }

    public void setUploadAction(@Nullable Runnable onUpload) {
        this.onUpload = onUpload;
        refreshUploadVisibility();
    }

    private void refreshUploadVisibility() {
        boolean thumbVisible = stagedThumb.getVisibility() == VISIBLE;
        boolean show = onUpload != null && !thumbVisible;
        uploadButton.setVisibility(show ? VISIBLE : GONE);
    }

    private void renderQuery(String q, int cursorPos) {
        this.currentQuery = q == null ? "" : q;
        this.cursorPos = Math.max(0, Math.min(currentQuery.length(), cursorPos));
        if (currentQuery.isEmpty()) {
            queryView.setText(hint);
            queryView.setAlpha(0.55f);
        } else {
            queryView.setText(currentQuery);
            queryView.setAlpha(1f);
        }
        // Wait for the new text to lay out before measuring caret position —
        // getLayout() returns null until measure runs.
        queryView.post(this::layoutCaret);
    }

    private void layoutCaret() {
        android.text.Layout layout = queryView.getLayout();
        if (layout == null) return;
        // Position uses the actual typed query (not the hint), so an empty
        // prompt parks the caret at the left of the visible text area.
        int safePos = currentQuery.isEmpty() ? 0 : Math.min(cursorPos, currentQuery.length());
        // getPrimaryHorizontal returns x in LAYOUT coordinates — i.e.
        // relative to the layout's left edge, which sits at the TextView's
        // paddingLeft. We add paddingLeft so the caret renders at the
        // visible character boundary; without it the caret would appear
        // ~paddingLeft pixels to the left of the actual letter (i.e.
        // "before the last letter" once any padding > 0 is set on the TV).
        float layoutX = currentQuery.isEmpty()
                ? layout.getPrimaryHorizontal(0)
                : layout.getPrimaryHorizontal(safePos);
        float wrapperX = queryView.getLeft() + queryView.getPaddingLeft() + layoutX;
        // Caret height = line height minus a small inset so the bar doesn't
        // hard-clip the rounded sans-serif descenders.
        int caretHeight = Math.max(dp(14), layout.getHeight() - dp(2));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) caretView.getLayoutParams();
        if (lp.height != caretHeight) {
            lp.height = caretHeight;
            caretView.setLayoutParams(lp);
        }
        // Center the 2 dp caret on the character boundary so it doesn't
        // look pushed a hair to the right of the letter it sits beside.
        caretView.setTranslationX(wrapperX - lp.width / 2f);
        if (caretView.getVisibility() != VISIBLE) caretView.setVisibility(VISIBLE);

        // Drag handle sits centred on the same x as the caret, but offset
        // vertically so it visually grips the bar from below. translationY
        // is applied here once the caret height is known.
        FrameLayout.LayoutParams handleLp =
                (FrameLayout.LayoutParams) dragHandleView.getLayoutParams();
        dragHandleView.setTranslationX(wrapperX - handleLp.width / 2f);
        dragHandleView.setTranslationY(caretHeight / 2f + dp(2));
    }

    private void showDragHandle(boolean show) {
        if (show) {
            // Make sure the handle is positioned correctly before becoming
            // visible — otherwise on first DOWN it would pop in at (0,0).
            queryView.post(this::layoutCaret);
            dragHandleView.setVisibility(VISIBLE);
            // Caret stays solid while a drag is in progress so the user
            // can see the exact insertion point under their finger.
            caretView.setAlpha(1f);
            caretView.setVisibility(VISIBLE);
        } else {
            dragHandleView.setVisibility(INVISIBLE);
        }
    }

    private void pauseCaretBlink() {
        if (caretBlinker != null) {
            caretBlinker.cancel();
            caretBlinker = null;
        }
        caretView.setAlpha(1f);
        caretView.setVisibility(VISIBLE);
    }

    /** Square-wave blink: alpha snaps between 0 and 1 every half-period so
     *  the caret matches the platform's hard cursor cadence instead of
     *  smoothly fading (which reads as "shimmering"). */
    private void startCaretBlink() {
        if (caretBlinker != null && caretBlinker.isRunning()) return;
        caretView.setVisibility(VISIBLE);
        caretView.setAlpha(1f);
        caretBlinker = ValueAnimator.ofFloat(0f, 2f);
        caretBlinker.setDuration(CARET_BLINK_PERIOD_MS);
        caretBlinker.setRepeatCount(ValueAnimator.INFINITE);
        caretBlinker.setInterpolator(new LinearInterpolator());
        caretBlinker.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            caretView.setAlpha(v < 1f ? 1f : 0f);
        });
        caretBlinker.start();
    }

    private void stopCaretBlink() {
        if (caretBlinker != null) { caretBlinker.cancel(); caretBlinker = null; }
        caretView.setVisibility(INVISIBLE);
        dragHandleView.setVisibility(INVISIBLE);
        dragging = false;
    }

    public void setOnGoListener(OnGoListener l) { onGo = l; }
    public void setOnPasteListener(OnPasteListener l) { onPaste = l; }
    public void setOnCursorMoveListener(@Nullable OnCursorMoveListener l) {
        this.onCursorMove = l;
    }

    public void applyTheme(KeyboardTheme theme) {
        this.theme = theme;
        // Surface, chip fills, and glyph colours are fixed by the dark
        // gradient design — only the lime accent on the Go button still
        // tracks the theme so brand changes propagate.
        goButton.setBackground(pillBackground(theme.accent, dp(18)));
        goButton.setTextColor(0xFFFFFFFF);
    }
}
