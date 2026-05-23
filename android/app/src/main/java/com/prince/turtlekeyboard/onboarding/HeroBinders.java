package com.prince.turtlekeyboard.onboarding;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.prince.turtlekeyboard.R;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of per-heroKey illustration binders. The activity hands the page's
 * hero slot View to {@link #bind} and the registered binder fills it with the
 * right artwork. Unmapped keys fall through to a labelled placeholder card.
 */
public final class HeroBinders {

    public interface Binder {
        /** Fill the hero slot for this page. Called every bind — keep work cheap. */
        void bind(View slotRoot, OnboardingPage page);
    }

    private static final String HERO_IMAGE_TAG = "hero_image";

    private static final Map<String, Binder> REGISTRY = new HashMap<>();

    static {
        REGISTRY.put("image_grid", drawableBinder(R.drawable.one));
        REGISTRY.put("stickers", drawableBinder(R.drawable.two));
        REGISTRY.put("polls_puzzle", drawableBinder(R.drawable.three));
    }

    private static final Binder PLACEHOLDER = (slot, page) -> {
        View card = slot.findViewById(R.id.hero_placeholder);
        TextView label = slot.findViewById(R.id.hero_placeholder_label);
        if (card != null) card.setVisibility(View.VISIBLE);
        if (label != null) {
            label.setVisibility(View.VISIBLE);
            String key = page.heroKey == null || page.heroKey.isEmpty() ? page.id : page.heroKey;
            label.setText(String.format(Locale.US, "hero · %s", key));
        }
        View image = slot.findViewWithTag(HERO_IMAGE_TAG);
        if (image != null) image.setVisibility(View.GONE);
    };

    private HeroBinders() {}

    public static void register(String heroKey, Binder binder) {
        REGISTRY.put(heroKey, binder);
    }

    public static void bind(View slotRoot, OnboardingPage page) {
        Binder b = REGISTRY.get(page.heroKey);
        (b != null ? b : PLACEHOLDER).bind(slotRoot, page);
    }

    private static Binder drawableBinder(int drawableRes) {
        return (slot, page) -> {
            ViewGroup container = (ViewGroup) slot;
            View card = container.findViewById(R.id.hero_placeholder);
            View label = container.findViewById(R.id.hero_placeholder_label);
            if (card != null) card.setVisibility(View.GONE);
            if (label != null) label.setVisibility(View.GONE);

            ImageView image = container.findViewWithTag(HERO_IMAGE_TAG);
            if (image == null) {
                image = new ImageView(container.getContext());
                image.setTag(HERO_IMAGE_TAG);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                container.addView(image, 0, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            }
            image.setImageResource(drawableRes);
            image.setVisibility(View.VISIBLE);
        };
    }
}
