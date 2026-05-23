package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import androidx.core.content.ContextCompat;

import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

import java.util.List;

/**
 * Top bar above the keys: emoji + settings on the leading edge, mic on the
 * trailing edge, and a centre region that shows either suggestion slots or a
 * paste preview pill when the clipboard has text.
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

    private static final int SLOT_COUNT = 3;

    private final IconButton emojiButton;
    private final IconButton settingsButton;
    private final IconButton micButton;
    private final FrameLayout centerHost;
    private final LinearLayout slotsRow;
    private final PastePreviewChip pasteChip;
    private final TextView[] slotViews = new TextView[SLOT_COUNT];
    private final View[] slotDividers = new View[SLOT_COUNT - 1];

    private OnPickListener listener;
    @Nullable private OnIconTapListener micListener;
    @Nullable private OnIconTapListener pasteListener;
    @Nullable private OnIconTapListener settingsListener;
    @Nullable private OnIconTapListener emojiListener;
    private KeyboardTheme theme;

    public SuggestionStripView(Context context) {
        super(context);
        emojiButton = new IconButton(context, IconButton.GLYPH_EMOJI);
        settingsButton = new IconButton(context, IconButton.GLYPH_MENU);
        micButton = new IconButton(context, IconButton.GLYPH_MIC);
        centerHost = new FrameLayout(context);
        slotsRow = new LinearLayout(context);
        pasteChip = new PastePreviewChip(context);
        init();
    }

    public SuggestionStripView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        emojiButton = new IconButton(context, IconButton.GLYPH_EMOJI);
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

        addView(emojiButton, new LayoutParams(dp(40), dp(40)));
        emojiButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (emojiListener != null) emojiListener.onTap();
        });

        addView(settingsButton, new LayoutParams(dp(40), dp(40)));
        settingsButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (settingsListener != null) settingsListener.onTap();
        });

        slotsRow.setOrientation(HORIZONTAL);
        slotsRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams slotsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        centerHost.addView(slotsRow, slotsLp);

        for (int i = 0; i < SLOT_COUNT; i++) {
            if (i > 0) {
                View div = new View(getContext());
                div.setBackgroundColor(STRIP_DIVIDER);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(1), dp(22));
                dlp.gravity = Gravity.CENTER_VERTICAL;
                div.setVisibility(GONE);
                slotsRow.addView(div, dlp);
                slotDividers[i - 1] = div;
            }
            TextView tv = new TextView(getContext());
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setSingleLine(true);
            tv.setIncludeFontPadding(false);
            tv.setTextColor(STRIP_SLOT_TEXT);
            tv.setBackgroundResource(R.drawable.suggestion_pick_ripple);
            tv.setClickable(true);
            tv.setFocusable(true);
            tv.setVisibility(GONE);
            final int idx = i;
            tv.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                CharSequence text = ((TextView) v).getText();
                if (listener != null && text != null && text.length() > 0) {
                    listener.onPick(text.toString());
                }
            });
            slotsRow.addView(tv, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
            slotViews[i] = tv;
        }

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
    public void setOnEmojiTapListener(@Nullable OnIconTapListener l) { this.emojiListener = l; }

    /**
     * Shows the paste preview pill with the given clipboard text, or hides it
     * (and reveals the suggestion slots) when {@code text} is null or empty.
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
        String oneLine = s.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= PASTE_PREVIEW_MAX_CHARS) return oneLine;
        return oneLine.substring(0, PASTE_PREVIEW_MAX_CHARS) + "…";
    }

    /** Strip text/divider colours are theme-independent — the strip always sits on the dark keyboard surface. */
    private static final int STRIP_SLOT_TEXT = 0xFFF5F5F5;
    private static final int STRIP_DIVIDER = 0x22FFFFFF;

    public void applyTheme(KeyboardTheme theme) {
        this.theme = theme;
        setBackgroundColor(theme.background);
        emojiButton.applyTheme(theme);
        settingsButton.applyTheme(theme);
        micButton.applyTheme(theme);
        pasteChip.applyTheme(theme);
        for (int i = 0; i < slotsRow.getChildCount(); i++) {
            View v = slotsRow.getChildAt(i);
            if (v instanceof TextView) {
                ((TextView) v).setTextColor(STRIP_SLOT_TEXT);
            } else {
                v.setBackgroundColor(STRIP_DIVIDER);
            }
        }
    }

    public void setSuggestions(List<String> suggestions) {
        int count = suggestions == null ? 0 : Math.min(suggestions.size(), SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            TextView slot = slotViews[i];
            if (i < count) {
                slot.setText(suggestions.get(i));
                slot.setVisibility(VISIBLE);
            } else {
                slot.setText("");
                slot.setVisibility(GONE);
            }
            if (i > 0) {
                slotDividers[i - 1].setVisibility(i < count ? VISIBLE : GONE);
            }
        }
    }

    public void clear() { setSuggestions(null); }

    /** Returns the mic icon view so callers can read its window position. */
    public View micButton() { return micButton; }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** Rounded pill with a paste glyph and truncated clipboard preview. */
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
            bg.setColor(theme.keyFace);
            preview.setTextColor(0xFFFFFFFF);
            glyph.applyTheme(theme);
        }

        private float dp(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    getResources().getDisplayMetrics());
        }
    }

    /** Circular tap target with a vector glyph (mic / paste / menu / emoji). */
    private static class IconButton extends FrameLayout {
        static final int GLYPH_MIC = 0;
        static final int GLYPH_PASTE = 1;
        static final int GLYPH_MENU = 2;
        static final int GLYPH_EMOJI = 3;

        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int glyphKind;
        /** Skipped when this button is decoration inside another tappable container. */
        private boolean drawBackground = true;
        private int bgColor = 0x00000000;
        private int glyphColor = 0xFF0C0C0C;
        @Nullable private Drawable glyph;

        IconButton(Context context, int glyphKind) {
            super(context);
            this.glyphKind = glyphKind;
            backgroundPaint.setStyle(Paint.Style.FILL);
            setClickable(true);
            setFocusable(true);
            setBackground(rippleMask());
            setWillNotDraw(false);
            int resId = drawableFor(glyphKind);
            if (resId != 0) {
                Drawable d = ContextCompat.getDrawable(context, resId);
                // mutate() prevents per-instance tints from bleeding across shared drawables.
                glyph = d == null ? null : d.mutate();
            }
        }

        private static int drawableFor(int glyphKind) {
            switch (glyphKind) {
                case GLYPH_MIC:   return R.drawable.baseline_mic_24;
                case GLYPH_PASTE: return R.drawable.baseline_paste_24;
                case GLYPH_MENU:  return R.drawable.baseline_menu_24;
                case GLYPH_EMOJI: return R.drawable.baseline_mood_24;
                default:          return 0;
            }
        }

        @Override
        public void setClickable(boolean clickable) {
            super.setClickable(clickable);
            this.drawBackground = clickable;
        }

        void applyTheme(KeyboardTheme theme) {
            this.bgColor = theme.keyFace;
            this.glyphColor = 0xFFFFFFFF;
            backgroundPaint.setColor(bgColor);
            if (glyph != null) glyph.setTint(glyphColor);
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
            if (glyph != null) {
                int half = (int) (r * 0.85f);
                glyph.setBounds((int) (cx - half), (int) (cy - half),
                        (int) (cx + half), (int) (cy + half));
                glyph.draw(c);
            }
            super.onDraw(c);
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
