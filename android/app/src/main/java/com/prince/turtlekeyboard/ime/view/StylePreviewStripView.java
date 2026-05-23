package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.theme.KeyboardTheme;

import java.util.List;

/**
 * Horizontal strip of image-preview cards shown during {@code /style} prompt
 * mode. Preview images resolve at runtime as {@code R.drawable.style_preview_&lt;key&gt;};
 * missing assets render a hash-coloured gradient placeholder so the strip is
 * always populated.
 */
public class StylePreviewStripView extends HorizontalScrollView {

    public interface OnPresetTap { void onTap(String preset); }

    private static final int BG = 0xFF000000;
    private static final int CARD_W_DP = 100;
    private static final int CARD_H_DP = 130;
    private static final int CARD_RADIUS_DP = 16;
    private static final int LABEL_GAP_DP = 6;

    private LinearLayout row;

    public StylePreviewStripView(Context c) { super(c); init(); }
    public StylePreviewStripView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        setHorizontalScrollBarEnabled(false);
        setVisibility(GONE);
        setBackgroundColor(BG);
        row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(12), padV = dp(10);
        row.setPadding(padH, padV, padH, padV);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    /** Show one card per preset key. Null/empty list hides the strip. */
    public void setPresets(@Nullable List<String> values, @Nullable OnPresetTap listener) {
        row.removeAllViews();
        if (values == null || values.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        for (String value : values) {
            row.addView(makeCard(value, listener));
        }
        scrollTo(0, 0);
        setVisibility(VISIBLE);
    }

    public void hide() {
        setVisibility(GONE);
        row.removeAllViews();
    }

    public void applyTheme(KeyboardTheme theme) {
        // No-op; chrome is fixed by the dark gradient design.
    }

    private View makeCard(String presetKey, @Nullable OnPresetTap l) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> { if (l != null) l.onTap(presetKey); });

        FrameLayout thumb = new FrameLayout(getContext());
        thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        dp(CARD_RADIUS_DP));
            }
        });
        thumb.setClipToOutline(true);

        ImageView image = new ImageView(getContext());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);

        int drawableId = resolvePreviewDrawable(presetKey);
        if (drawableId != 0) {
            image.setImageResource(drawableId);
        } else {
            image.setBackground(placeholderGradient(presetKey));
        }
        thumb.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Transparent overlay placeholder; vendor-themed selectableItemBackground was inconsistent.
        View ripple = new View(getContext());
        ripple.setBackgroundColor(0x00000000);
        thumb.addView(ripple, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams thumbLp =
                new LinearLayout.LayoutParams(dp(CARD_W_DP), dp(CARD_H_DP));
        card.addView(thumb, thumbLp);

        TextView label = new TextView(getContext());
        label.setText(titleCase(presetKey));
        label.setTextColor(0xFFEDEDED);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        label.setTypeface(label.getTypeface(), Typeface.BOLD);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);

        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(LABEL_GAP_DP);
        card.addView(label, labelLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    /** Returns 0 if the asset is missing; callers paint a placeholder gradient instead. */
    private int resolvePreviewDrawable(String key) {
        if (key == null || key.isEmpty()) return 0;
        return getResources().getIdentifier(
                "style_preview_" + key.toLowerCase(),
                "drawable",
                getContext().getPackageName());
    }

    /** Deterministic two-colour gradient derived from the key's hash. */
    private GradientDrawable placeholderGradient(String key) {
        int h = (key == null ? 0 : key.hashCode()) & 0xFFFFFF;
        float hueA = ((h         & 0xFF) / 255f) * 360f;
        float hueB = (((h >> 16) & 0xFF) / 255f) * 360f;
        int colorA = Color.HSVToColor(new float[]{ hueA, 0.55f, 0.32f });
        int colorB = Color.HSVToColor(new float[]{ hueB, 0.65f, 0.18f });
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{ colorA, colorB });
        g.setShape(GradientDrawable.RECTANGLE);
        return g;
    }

    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
