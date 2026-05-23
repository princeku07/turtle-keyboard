package com.prince.turtlekeyboard.integration.puzzle;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Puzzle config panel mounted above the keys when {@code /puzzle} dispatches.
 * Triggers the image picker via its Upload button and re-renders the preview when
 * the integration calls {@link #setImage}.
 *
 * <p>The panel is mounted before the picker fires so it lives on a visible window;
 * the picker callback only updates state on the live view.
 */
public final class PuzzleSetupPanel extends LinearLayout {

    private static final int BG          = 0xFF000000;
    private static final int SURFACE     = 0xFF1E1E1E;
    private static final int SURFACE_2   = 0xFF141414;
    private static final int ACCENT      = 0xFF15803D;
    private static final int TEXT        = 0xFFF5F5F5;
    private static final int MUTED       = 0xFF888888;
    private static final int BORDER      = 0xFF2E2E2E;

    private static final int[] DIFFICULTIES = { 3, 4, 5 };
    private static final int DEFAULT_DIFFICULTY = 3;

    public interface Callback {
        /** Integration should fire the system picker and call {@link #setImage} on completion. */
        void onPickImage();
        /** Bytes are non-null; the Create button stays disabled until {@link #setImage} runs. */
        void onConfirm(byte[] bytes, @Nullable String mime, int gridSize);
        void onCancel();
    }

    private final ImageView previewView;
    private final TextView previewPlaceholder;
    private final TextView uploadButton;
    private final TextView[] diffButtons;
    private final TextView createButton;

    @Nullable private byte[] pickedBytes;
    @Nullable private String pickedMime;
    private int selectedDifficulty = DEFAULT_DIFFICULTY;
    @Nullable private Callback callback;

    public PuzzleSetupPanel(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        int padH = dp(18);
        setPadding(padH, dp(14), padH, dp(14));

        TextView title = new TextView(context);
        title.setText("Make a puzzle");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.04f);
        addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setText("Pick an image, pick difficulty, share");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setTextColor(MUTED);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = dp(10);
        subtitle.setLayoutParams(subLp);
        addView(subtitle);

        LinearLayout previewFrame = new LinearLayout(context);
        previewFrame.setBackground(roundedFill(SURFACE_2, BORDER));
        previewFrame.setGravity(Gravity.CENTER);
        int previewSize = dp(120);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(previewSize, previewSize);
        previewLp.gravity = Gravity.CENTER_HORIZONTAL;
        previewLp.topMargin = dp(4);
        previewLp.bottomMargin = dp(10);
        previewFrame.setLayoutParams(previewLp);
        previewFrame.setOnClickListener(v -> {
            if (callback != null) callback.onPickImage();
        });

        previewView = new ImageView(context);
        previewView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewView.setVisibility(GONE);
        previewView.setLayoutParams(new LinearLayout.LayoutParams(previewSize, previewSize));
        previewFrame.addView(previewView);

        previewPlaceholder = new TextView(context);
        previewPlaceholder.setText("Tap to pick");
        previewPlaceholder.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        previewPlaceholder.setTextColor(MUTED);
        previewPlaceholder.setGravity(Gravity.CENTER);
        previewFrame.addView(previewPlaceholder);
        addView(previewFrame);

        uploadButton = secondaryButton(context, "Upload image");
        LinearLayout.LayoutParams uploadLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        uploadLp.bottomMargin = dp(12);
        uploadButton.setLayoutParams(uploadLp);
        uploadButton.setOnClickListener(v -> {
            if (callback != null) callback.onPickImage();
        });
        addView(uploadButton);

        LinearLayout diffRow = new LinearLayout(context);
        diffRow.setOrientation(LinearLayout.HORIZONTAL);
        diffRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams diffRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        diffRowLp.bottomMargin = dp(12);
        diffRow.setLayoutParams(diffRowLp);

        diffButtons = new TextView[DIFFICULTIES.length];
        for (int i = 0; i < DIFFICULTIES.length; i++) {
            final int n = DIFFICULTIES[i];
            TextView btn = pillButton(context, n + "×" + n, n == selectedDifficulty);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnLp.rightMargin = (i < DIFFICULTIES.length - 1) ? dp(8) : 0;
            btn.setLayoutParams(btnLp);
            btn.setOnClickListener(v -> {
                selectedDifficulty = n;
                for (int j = 0; j < diffButtons.length; j++) {
                    setPillSelected(diffButtons[j], DIFFICULTIES[j] == n);
                }
            });
            diffButtons[i] = btn;
            diffRow.addView(btn);
        }
        addView(diffRow);

        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        actionRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView cancel = secondaryButton(context, "Cancel");
        cancel.setOnClickListener(v -> {
            if (callback != null) callback.onCancel();
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelLp.rightMargin = dp(8);
        cancel.setLayoutParams(cancelLp);
        actionRow.addView(cancel);

        createButton = primaryButton(context, "Create");
        applyCreateEnabled(false);
        createButton.setOnClickListener(v -> {
            if (callback != null && pickedBytes != null) {
                callback.onConfirm(pickedBytes, pickedMime, selectedDifficulty);
            }
        });
        LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        createButton.setLayoutParams(createLp);
        actionRow.addView(createButton);

        addView(actionRow);
    }

    public void bind(Callback cb) {
        this.callback = cb;
    }

    /** Called by the integration after the image picker delivers. */
    public void setImage(byte[] bytes, @Nullable String mime) {
        this.pickedBytes = bytes;
        this.pickedMime = mime;
        Bitmap bmp = decodeOrNull(bytes);
        if (bmp != null) {
            previewView.setImageBitmap(bmp);
            previewView.setVisibility(VISIBLE);
            previewPlaceholder.setVisibility(GONE);
        }
        uploadButton.setText("Change image");
        applyCreateEnabled(true);
    }

    private void applyCreateEnabled(boolean enabled) {
        createButton.setEnabled(enabled);
        createButton.setAlpha(enabled ? 1f : 0.35f);
    }

    private TextView pillButton(Context ctx, String text, boolean selected) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        setPillSelected(t, selected);
        return t;
    }

    private void setPillSelected(TextView t, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(999));
        if (selected) {
            bg.setColor(ACCENT);
            bg.setStroke(dp(1), ACCENT);
            t.setTextColor(TEXT);
        } else {
            bg.setColor(SURFACE);
            bg.setStroke(dp(1), BORDER);
            t.setTextColor(MUTED);
        }
        t.setBackground(bg);
    }

    private TextView primaryButton(Context ctx, String label) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(999));
        bg.setColor(ACCENT);
        t.setTextColor(TEXT);
        t.setBackground(bg);
        return t;
    }

    private TextView secondaryButton(Context ctx, String label) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(999));
        bg.setColor(SURFACE);
        bg.setStroke(dp(1), BORDER);
        t.setTextColor(TEXT);
        t.setBackground(bg);
        return t;
    }

    private GradientDrawable roundedFill(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(1), stroke);
        d.setCornerRadius(dp(10));
        return d;
    }

    @Nullable
    private static Bitmap decodeOrNull(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
