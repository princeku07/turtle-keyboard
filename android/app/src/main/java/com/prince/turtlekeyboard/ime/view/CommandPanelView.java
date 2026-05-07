package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.theme.KeyboardTheme;

/**
 * Inline panel shown above the keyboard while the user is typing a command argument
 * (e.g. the URL after "/search "). Mirrors the keystrokes captured by the composer and
 * exposes a "Go" button that dispatches the command.
 *
 * <p>Also surfaces a paste affordance when the clipboard has text: a chip rendered to
 * the left of Go, plus long-press on the query area as a backup. The IME wires the
 * listener to {@code CommandComposer.appendString}.
 */
public class CommandPanelView extends LinearLayout {

    public interface OnGoListener { void onGo(); }
    public interface OnPasteListener { void onPaste(String text); }

    private ImageView stagedThumb;
    private TextView stagedClose;
    private TextView labelView;
    private TextView queryView;
    private LinearLayout pasteRow;
    private TextView pasteChip;
    private TextView pasteClose;
    private TextView goButton;
    private OnGoListener onGo;
    private OnPasteListener onPaste;
    @Nullable private Runnable onStagedClear;
    private String hint = "type and tap →";
    @Nullable private String clipboardText;
    /** True after the user dismissed the paste chip in this prompt session. Reset
     *  on {@link #hide()} so the chip can show again next time the user enters
     *  prompt mode (clipboard contents may also have changed by then). */
    private boolean pasteDismissed;

    public CommandPanelView(Context context) { super(context); init(); }
    public CommandPanelView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setOrientation(HORIZONTAL);
        setVisibility(GONE);
        setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(12), padV = dp(10);
        setPadding(padH, padV, padH, padV);

        // Leading slot: thumbnail of a staged /edit image with a small × to clear it.
        // Hidden by default; shown when setStagedImage(...) is called with a bitmap.
        // Rounded corners via outline clipping so the panel feels less rectangular.
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

        labelView = new TextView(getContext());
        labelView.setTextColor(Color.WHITE);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        labelView.setTypeface(labelView.getTypeface(), Typeface.BOLD);
        addView(labelView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        queryView = new TextView(getContext());
        queryView.setTextColor(Color.WHITE);
        queryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        queryView.setSingleLine(true);
        queryView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        queryView.setPadding(dp(10), 0, dp(10), 0);
        queryView.setLongClickable(true);
        // Long-press anywhere on the query area pastes the clipboard — backup for users
        // who don't notice the dedicated chip.
        queryView.setOnLongClickListener(v -> firePaste());
        LayoutParams qLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        addView(queryView, qLp);

        // Paste suggestion: a pill-shaped "📋 Paste" chip with a small circular ×
        // button next to it. The label is generic — the user already knows what
        // they copied, so showing a long preview just adds noise.
        pasteRow = new LinearLayout(getContext());
        pasteRow.setOrientation(HORIZONTAL);
        pasteRow.setGravity(Gravity.CENTER_VERTICAL);
        pasteRow.setVisibility(GONE);
        LayoutParams rowLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rowLp.rightMargin = dp(8);
        addView(pasteRow, rowLp);

        pasteChip = new TextView(getContext());
        pasteChip.setText("📋 Paste");
        pasteChip.setTextColor(Color.WHITE);
        pasteChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        pasteChip.setTypeface(pasteChip.getTypeface(), Typeface.BOLD);
        pasteChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        pasteChip.setSingleLine(true);
        pasteChip.setBackground(pillBackground(0x33FFFFFF, dp(14)));
        pasteChip.setClickable(true);
        pasteChip.setFocusable(true);
        pasteChip.setOnClickListener(v -> firePaste());
        LayoutParams chipLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        chipLp.rightMargin = dp(4);
        pasteRow.addView(pasteChip, chipLp);

        pasteClose = makeCloseButton();
        pasteClose.setOnClickListener(v -> {
            pasteDismissed = true;
            pasteRow.setVisibility(GONE);
        });
        pasteRow.addView(pasteClose, new LayoutParams(dp(20), dp(20)));

        goButton = new TextView(getContext());
        goButton.setText("➤");
        goButton.setTextColor(Color.WHITE);
        goButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        goButton.setTypeface(goButton.getTypeface(), Typeface.BOLD);
        goButton.setGravity(Gravity.CENTER);
        goButton.setPadding(dp(16), dp(8), dp(16), dp(8));
        goButton.setBackground(pillBackground(Color.parseColor("#2E7D32"), dp(18)));
        goButton.setClickable(true);
        goButton.setFocusable(true);
        goButton.setOnClickListener(v -> { if (onGo != null) onGo.onGo(); });
        addView(goButton, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    /** Pill-shaped solid background. {@code radius} should usually be roughly half
     *  the view's height so the corners merge into a true pill. */
    private GradientDrawable pillBackground(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Small circular × control used both for clearing the staged image and for
     *  dismissing the paste suggestion. Subtle white wash so it reads as tappable
     *  without competing with the chip next to it. */
    private TextView makeCloseButton() {
        TextView t = new TextView(getContext());
        t.setText("×");
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x33FFFFFF);
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

    public void show(String label, String hint, String query) {
        labelView.setText(label);
        this.hint = hint == null ? "" : hint;
        renderQuery(query);
        setVisibility(VISIBLE);
    }

    public void update(String query) {
        renderQuery(query);
        // Once the user has typed anything the chip becomes visual noise — hide it.
        if (query != null && !query.isEmpty()) pasteRow.setVisibility(GONE);
    }

    public void hide() {
        setVisibility(GONE);
        clipboardText = null;
        pasteRow.setVisibility(GONE);
        pasteDismissed = false;
        setStagedImage(null, null);
    }

    /** Tell the panel what text is on the clipboard. Pass {@code null} (or empty) to
     *  hide the paste affordance. The chip shows a generic "Paste" label — the user
     *  already knows what they copied. Once they dismiss the suggestion via × it
     *  stays hidden until {@link #hide()} resets the session. */
    public void setPasteAvailable(@Nullable String text) {
        this.clipboardText = (text == null || text.isEmpty()) ? null : text;
        if (clipboardText == null || pasteDismissed) {
            pasteRow.setVisibility(GONE);
            return;
        }
        pasteRow.setVisibility(VISIBLE);
    }

    /** Show a thumbnail of a staged image (e.g. picked for {@code /edit}) at the
     *  start of the panel. The × clears it via the supplied {@code onClear} callback,
     *  which the IME wires to {@code LmStudioAiClient.stageEditImage(null, null)}.
     *  Pass {@code null} to hide. */
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
    }

    private void renderQuery(String q) {
        if (q == null || q.isEmpty()) {
            queryView.setText(hint);
            queryView.setAlpha(0.55f);
        } else {
            queryView.setText(q);
            queryView.setAlpha(1f);
        }
    }

    public void setOnGoListener(OnGoListener l) { onGo = l; }
    public void setOnPasteListener(OnPasteListener l) { onPaste = l; }

    public void applyTheme(KeyboardTheme theme) {
        setBackgroundColor(theme.bannerBg);
        labelView.setTextColor(theme.bannerText);
        queryView.setTextColor(theme.bannerText);
        pasteChip.setTextColor(theme.bannerText);
        pasteClose.setTextColor(theme.bannerText);
        stagedClose.setTextColor(theme.bannerText);
        // Re-apply pill background tinted by the theme accent so the Go button
        // doesn't lose its rounded shape after a theme switch.
        goButton.setBackground(pillBackground(theme.accent, dp(18)));
        goButton.setTextColor(theme.background);
    }
}
