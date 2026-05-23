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
 * Inline panel shown above the keyboard while typing a command argument.
 * Mirrors the composer buffer, renders a blinking caret with tap/drag
 * positioning, supports long-press paste, and exposes a Go button that
 * dispatches the command.
 */
public class CommandPanelView extends LinearLayout {

    public interface OnGoListener { void onGo(); }
    public interface OnPasteListener { void onPaste(String text); }
    /** Fires when the user taps or drags inside the query area to position the prompt caret. */
    public interface OnCursorMoveListener { void onMove(int pos); }

    private static final int BG = 0xFF000000;
    private static final int TEXT_PRIMARY = 0xFFF5F5F5;
    private static final int CHIP_FILL_SUBTLE = 0x14FFFFFF;

    private static final long CARET_BLINK_PERIOD_MS = 1100L;

    private ImageView stagedThumb;
    private TextView stagedClose;
    private TextView uploadButton;
    private TextView pasteButton;
    private TextView labelView;

    private FrameLayout queryWrapper;
    private TextView queryView;
    private View caretView;
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

    private int cursorPos;
    private String currentQuery = "";
    private boolean paused;

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

        // Paste goes to the prompt buffer, not the host editor.
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

        queryWrapper = new FrameLayout(getContext());
        // Clip children so a mis-measured caret can't bleed down through the keyboard.
        queryWrapper.setClipChildren(true);
        queryWrapper.setClipToPadding(true);
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

        caretView = new View(getContext());
        caretView.setBackgroundColor(TEXT_PRIMARY);
        caretView.setVisibility(INVISIBLE);
        // Concrete height avoids a WRAP_CONTENT caret blowing up to the wrapper's full height on first measure.
        FrameLayout.LayoutParams caretLp = new FrameLayout.LayoutParams(
                dp(2), dp(20));
        caretLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        queryWrapper.addView(caretView, caretLp);

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

        // Tap/drag positions the caret; long-press pastes. GestureDetector keeps both gestures from fighting.
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

    private void moveCaretToTouch(float rawX) {
        if (onCursorMove == null) return;
        // getOffsetForPosition expects local-to-TextView x.
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
        setPaused(false);
        startCaretBlink();
    }

    /** Stops the caret blink and dims the surface to show the prompt is staged but not collecting keystrokes. */
    public void setPaused(boolean paused) {
        if (this.paused == paused) return;
        this.paused = paused;
        if (paused) {
            stopCaretBlink();
            setAlpha(0.55f);
        } else {
            setAlpha(1f);
            if (getVisibility() == VISIBLE) startCaretBlink();
        }
    }

    public boolean isPaused() { return paused; }

    public void update(String query, int cursorPos) {
        renderQuery(query, cursorPos);
    }

    public void hide() {
        setVisibility(GONE);
        stopCaretBlink();
        paused = false;
        setAlpha(1f);
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
        // getLayout() returns null until measure runs.
        queryView.post(this::layoutCaret);
    }

    private void layoutCaret() {
        android.text.Layout layout = queryView.getLayout();
        if (layout == null) return;
        int safePos = currentQuery.isEmpty() ? 0 : Math.min(cursorPos, currentQuery.length());
        // getPrimaryHorizontal is layout-local; add paddingLeft for the visible character boundary.
        float layoutX = currentQuery.isEmpty()
                ? layout.getPrimaryHorizontal(0)
                : layout.getPrimaryHorizontal(safePos);
        float wrapperX = queryView.getLeft() + queryView.getPaddingLeft() + layoutX;
        // Capped at dp(28) so a mis-measured layout can't render the bar full-keyboard tall.
        int caretHeight = Math.max(dp(14),
                Math.min(dp(28), layout.getHeight() - dp(2)));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) caretView.getLayoutParams();
        if (lp.height != caretHeight) {
            lp.height = caretHeight;
            caretView.setLayoutParams(lp);
        }
        caretView.setTranslationX(wrapperX - lp.width / 2f);
        if (caretView.getVisibility() != VISIBLE) caretView.setVisibility(VISIBLE);

        FrameLayout.LayoutParams handleLp =
                (FrameLayout.LayoutParams) dragHandleView.getLayoutParams();
        dragHandleView.setTranslationX(wrapperX - handleLp.width / 2f);
        dragHandleView.setTranslationY(caretHeight / 2f + dp(2));
    }

    private void showDragHandle(boolean show) {
        if (show) {
            // Layout the handle before showing it so first DOWN doesn't pop in at (0,0).
            queryView.post(this::layoutCaret);
            dragHandleView.setVisibility(VISIBLE);
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
        // Only the Go button accent tracks the theme — surface/chips/glyphs are fixed.
        goButton.setBackground(pillBackground(theme.accent, dp(18)));
        goButton.setTextColor(0xFFFFFFFF);
    }
}
