package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

import java.util.List;

/**
 * Top bar above the keys. Layout:
 * <pre>
 *   [ ☰ ]      [ suggestions  OR  📋 https://…  centered ]      [ 🎤 ]
 * </pre>
 *
 * <p>Settings icon on the leading edge opens the host detail/settings view. Mic on
 * the trailing edge. The center hosts either the suggestion slots or — when the
 * clipboard has text — a centered paste preview pill.
 */
public class SuggestionStripView extends LinearLayout {

    public interface OnPickListener {
        void onPick(String suggestion);
    }

    public interface OnIconTapListener {
        void onTap();
    }

    /** Max characters of the clipboard text shown inside the paste preview pill. */
    private static final int PASTE_PREVIEW_MAX_CHARS = 18;

    private final IconButton settingsButton;
    private final IconButton micButton;
    private final FrameLayout centerHost;
    private final LinearLayout slotsRow;
    private final PastePreviewChip pasteChip;

    private OnPickListener listener;
    @Nullable private OnIconTapListener micListener;
    @Nullable private OnIconTapListener pasteListener;
    @Nullable private OnIconTapListener settingsListener;
    private KeyboardTheme theme;

    public SuggestionStripView(Context context) {
        super(context);
        settingsButton = new IconButton(context, IconButton.GLYPH_MENU);
        micButton = new IconButton(context, IconButton.GLYPH_MIC);
        centerHost = new FrameLayout(context);
        slotsRow = new LinearLayout(context);
        pasteChip = new PastePreviewChip(context);
        init();
    }

    public SuggestionStripView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        settingsButton = new IconButton(context, IconButton.GLYPH_MENU);
        micButton = new IconButton(context, IconButton.GLYPH_MIC);
        centerHost = new FrameLayout(context);
        slotsRow = new LinearLayout(context);
        pasteChip = new PastePreviewChip(context);
        init();
    }

    private void init() {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        int padH = dp(6);
        setPadding(padH, 0, padH, 0);

        // Leading: settings icon — opens the host detail view.
        addView(settingsButton, new LayoutParams(dp(40), dp(40)));
        settingsButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (settingsListener != null) settingsListener.onTap();
        });

        // Center: holds either the suggestion slots (full width) or the centered
        // paste preview pill, depending on clipboard state.
        slotsRow.setOrientation(HORIZONTAL);
        slotsRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams slotsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        centerHost.addView(slotsRow, slotsLp);

        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(34));
        chipLp.gravity = Gravity.CENTER;
        centerHost.addView(pasteChip, chipLp);
        pasteChip.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (pasteListener != null) pasteListener.onTap();
        });
        pasteChip.setVisibility(GONE);

        addView(centerHost, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        // Trailing: mic button — always visible.
        addView(micButton, new LayoutParams(dp(40), dp(40)));
        micButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (micListener != null) micListener.onTap();
        });
    }

    public void setOnPickListener(OnPickListener l) { this.listener = l; }
    public void setOnMicTapListener(@Nullable OnIconTapListener l) { this.micListener = l; }
    public void setOnPasteTapListener(@Nullable OnIconTapListener l) { this.pasteListener = l; }
    public void setOnSettingsTapListener(@Nullable OnIconTapListener l) { this.settingsListener = l; }

    /**
     * Show or hide the centered paste preview pill. Pass the clipboard's plain-text
     * content (truncated for display); pass {@code null} to hide. While the pill is
     * showing it owns the center — suggestion slots are hidden so they don't sit
     * underneath it.
     */
    public void setPasteText(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            pasteChip.setVisibility(GONE);
            slotsRow.setVisibility(VISIBLE);
            return;
        }
        pasteChip.setText(truncate(text));
        pasteChip.setVisibility(VISIBLE);
        slotsRow.setVisibility(GONE);
    }

    private static String truncate(String s) {
        // Collapse newlines so the preview reads on one line.
        String oneLine = s.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= PASTE_PREVIEW_MAX_CHARS) return oneLine;
        return oneLine.substring(0, PASTE_PREVIEW_MAX_CHARS) + "…";
    }

    public void applyTheme(KeyboardTheme theme) {
        this.theme = theme;
        setBackgroundColor(theme.bannerBg);
        settingsButton.applyTheme(theme);
        micButton.applyTheme(theme);
        pasteChip.applyTheme(theme);
        for (int i = 0; i < slotsRow.getChildCount(); i++) {
            View v = slotsRow.getChildAt(i);
            if (v instanceof TextView) {
                ((TextView) v).setTextColor(theme.suggestionText);
            } else {
                v.setBackgroundColor(theme.divider);
            }
        }
    }

    public void setSuggestions(List<String> suggestions) {
        slotsRow.removeAllViews();
        if (suggestions == null || suggestions.isEmpty()) {
            return;
        }
        for (int i = 0; i < suggestions.size(); i++) {
            if (i > 0) addDivider();
            addSlot(suggestions.get(i));
        }
    }

    public void clear() { setSuggestions(null); }

    private void addSlot(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setSingleLine(true);
        tv.setIncludeFontPadding(false);
        tv.setTextColor(theme != null ? theme.suggestionText : Color.parseColor("#0C0C0C"));
        tv.setBackgroundResource(R.drawable.suggestion_pick_ripple);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (listener != null) listener.onPick(text);
        });
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        slotsRow.addView(tv, lp);
    }

    private void addDivider() {
        View v = new View(getContext());
        v.setBackgroundColor(theme != null ? theme.divider : 0xFFD9E8DF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(22));
        lp.gravity = Gravity.CENTER_VERTICAL;
        slotsRow.addView(v, lp);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /**
     * Pill that sits in the center of the strip when the clipboard has text.
     * Holds a small paste glyph and the truncated preview, in a {@link LinearLayout}
     * with a rounded background painted by {@link #applyTheme(KeyboardTheme)}.
     */
    private static class PastePreviewChip extends LinearLayout {
        private final IconButton glyph;
        private final TextView preview;
        private final GradientDrawable bg;

        PastePreviewChip(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setClickable(true);
            setFocusable(true);

            bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(18));
            bg.setColor(0xFFE8F5EE);
            setBackground(bg);

            int padV = (int) dp(2);
            int padH = (int) dp(4);
            setPadding(padH, padV, (int) dp(12), padV);

            int margin = (int) dp(4);
            int glyphSize = (int) dp(24);
            glyph = new IconButton(context, IconButton.GLYPH_PASTE);
            glyph.setClickable(false);
            glyph.setFocusable(false);
            LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(glyphSize, glyphSize);
            glyphLp.rightMargin = margin;
            addView(glyph, glyphLp);

            preview = new TextView(context);
            preview.setSingleLine(true);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            preview.setTextColor(0xFF0C0C0C);
            preview.setIncludeFontPadding(false);
            addView(preview, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }

        void setText(String text) {
            preview.setText(text);
        }

        void applyTheme(KeyboardTheme theme) {
            bg.setColor(theme.chipFill);
            preview.setTextColor(theme.suggestionText);
            glyph.applyTheme(theme);
        }

        private float dp(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    getResources().getDisplayMetrics());
        }
    }

    /**
     * Tiny circular tap-target with a vector glyph drawn in code so we don't need
     * extra drawable resources. Two glyph variants: mic and paste.
     */
    private static class IconButton extends FrameLayout {
        static final int GLYPH_MIC = 0;
        static final int GLYPH_PASTE = 1;
        static final int GLYPH_MENU = 2;

        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int glyphKind;
        /** When false (used inside the paste preview pill) we skip the circular
         *  background — the parent pill already provides one. */
        private boolean drawBackground = true;
        private int bgColor = 0x00000000;
        private int glyphColor = 0xFF0C0C0C;

        IconButton(Context context, int glyphKind) {
            super(context);
            this.glyphKind = glyphKind;
            backgroundPaint.setStyle(Paint.Style.FILL);
            glyphPaint.setStyle(Paint.Style.STROKE);
            glyphPaint.setStrokeCap(Paint.Cap.ROUND);
            glyphPaint.setStrokeJoin(Paint.Join.ROUND);
            setClickable(true);
            setFocusable(true);
            setBackground(rippleMask());
            setWillNotDraw(false);
        }

        @Override
        public void setClickable(boolean clickable) {
            super.setClickable(clickable);
            // Glyphs nested inside another tappable container don't paint their
            // own circular background — they're decoration.
            this.drawBackground = clickable;
        }

        void applyTheme(KeyboardTheme theme) {
            this.bgColor = theme.chipFill;
            this.glyphColor = theme.suggestionText;
            backgroundPaint.setColor(bgColor);
            glyphPaint.setColor(glyphColor);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) / 2f - dp(4);
            float cx = w / 2f, cy = h / 2f;
            if (drawBackground) {
                c.drawCircle(cx, cy, r, backgroundPaint);
            }
            float strokePx = dp(1.8f);
            glyphPaint.setStrokeWidth(strokePx);
            switch (glyphKind) {
                case GLYPH_MIC: drawMic(c, cx, cy, r); break;
                case GLYPH_PASTE: drawPaste(c, cx, cy, r); break;
                case GLYPH_MENU: drawMenu(c, cx, cy, r); break;
            }
            super.onDraw(c);
        }

        /** Hamburger menu: three rounded horizontal bars. */
        private void drawMenu(Canvas c, float cx, float cy, float r) {
            float w = r * 1.2f;
            float gap = r * 0.32f;
            float left = cx - w / 2f;
            float right = cx + w / 2f;
            c.drawLine(left, cy - gap, right, cy - gap, glyphPaint);
            c.drawLine(left, cy,       right, cy,       glyphPaint);
            c.drawLine(left, cy + gap, right, cy + gap, glyphPaint);
        }

        /** Mic: rounded capsule + curved base + short stem. */
        private void drawMic(Canvas c, float cx, float cy, float r) {
            float bodyW = r * 0.55f;
            float bodyH = r * 0.95f;
            float top = cy - bodyH * 0.55f;
            float bottom = cy + bodyH * 0.10f;
            float cornerR = bodyW / 2f;
            RectF body = new RectF(cx - bodyW / 2f, top, cx + bodyW / 2f, bottom);
            Paint fill = new Paint(glyphPaint);
            fill.setStyle(Paint.Style.FILL);
            c.drawRoundRect(body, cornerR, cornerR, fill);
            float arcR = bodyW * 0.95f;
            RectF arc = new RectF(
                    cx - arcR, cy - arcR * 0.2f, cx + arcR, cy + arcR * 1.2f);
            c.drawArc(arc, 20f, 140f, false, glyphPaint);
            c.drawLine(cx, cy + arcR * 0.55f, cx, cy + arcR * 0.95f, glyphPaint);
        }

        /** Paste: clipboard outline with a small clip at top. */
        private void drawPaste(Canvas c, float cx, float cy, float r) {
            float w = r * 1.05f;
            float h = r * 1.25f;
            RectF body = new RectF(cx - w / 2f, cy - h / 2f + dp(2),
                    cx + w / 2f, cy + h / 2f);
            float corner = dp(3);
            c.drawRoundRect(body, corner, corner, glyphPaint);
            float clipW = w * 0.55f;
            float clipH = dp(7);
            RectF clip = new RectF(cx - clipW / 2f, cy - h / 2f - dp(1),
                    cx + clipW / 2f, cy - h / 2f + clipH);
            Paint fill = new Paint(glyphPaint);
            fill.setStyle(Paint.Style.FILL);
            c.drawRoundRect(clip, dp(2), dp(2), fill);
        }

        private Drawable rippleMask() {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0x00000000);
            return g;
        }

        private float dp(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    getResources().getDisplayMetrics());
        }
    }
}
