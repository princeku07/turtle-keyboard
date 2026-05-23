package com.prince.turtlekeyboard.overlay;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.prince.kbd.core.SheetContext;
import com.prince.kbd.core.SheetRouter;
import com.prince.kbd.core.SheetView;
import com.prince.kbd.core.SheetViewFactory;
import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.TurtleApp;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hosts a bottom-sheet overlay triggered by a Turtle App Link. Parses the URL, looks
 * up the registered {@link SheetViewFactory}, and mounts the view in a card with a
 * dimmed tap-to-dismiss backdrop. Unknown or malformed routes finish silently.
 *
 * <p>Translucent theme + in-content animators give an "overlay over previous app"
 * feel without needing {@code SYSTEM_ALERT_WINDOW}.
 */
public class BottomSheetActivity extends AppCompatActivity {

    private static final String TAG = "BottomSheetActivity";

    private static final int BACKDROP = 0x99000000;        // ~60% black
    // Black sheet fill so WebView games on a #000 canvas merge seamlessly with the sheet edge.
    private static final int SHEET_FILL = 0xFF000000;
    private static final int SHEET_BORDER = 0xFF2E2E2E;
    private static final int SHEET_CORNER_RADIUS_DP = 18;
    private static final int ANIM_IN_MS = 220;
    private static final int ANIM_OUT_MS = 180;

    @Nullable private SheetView sheetView;
    @Nullable private FrameLayout rootView;
    @Nullable private FrameLayout cardView;
    @Nullable private ColorDrawable backdropDrawable;
    private boolean dismissing;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Window enter is instant; in-content animators handle fades and slide.
        overridePendingTransition(0, 0);

        // Sheet has no input target; suppress the soft keyboard.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        Intent intent = getIntent();
        Uri data = intent == null ? null : intent.getData();
        if (data == null) {
            Log.w(TAG, "no Uri on intent");
            finish();
            return;
        }

        ParsedRoute route = parseRoute(data);
        if (route == null) {
            Log.w(TAG, "malformed sheet URL: " + data);
            finish();
            return;
        }

        SheetRouter router = TurtleApp.from(this).sheetRouter();
        SheetViewFactory factory = router.factoryFor(route.routeKey);
        if (factory == null) {
            Log.w(TAG, "no SheetView registered for routeKey=" + route.routeKey);
            finish();
            return;
        }

        sheetView = factory.create();
        SheetContext ctx = new ActivitySheetContext(route);
        View content;
        try {
            content = sheetView.buildView(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "buildView threw for routeKey=" + route.routeKey, t);
            finish();
            return;
        }
        if (content == null) {
            finish();
            return;
        }

        setContentView(buildSheetContainer(content));
        sheetView.onShow();
        playEnterAnimation();
    }

    @Override
    public void finish() {
        if (dismissing) return;
        dismissing = true;
        if (sheetView != null) {
            try { sheetView.onDismiss(); } catch (Throwable t) { Log.w(TAG, "onDismiss threw", t); }
            sheetView = null;
        }
        if (rootView == null || cardView == null || backdropDrawable == null) {
            super.finish();
            overridePendingTransition(0, 0);
            return;
        }
        // Disable interaction during exit so taps can't re-trigger finish().
        rootView.setOnClickListener(null);
        cardView.setClickable(false);
        ObjectAnimator.ofInt(backdropDrawable, "alpha", backdropDrawable.getAlpha(), 0)
                .setDuration(ANIM_OUT_MS)
                .start();
        int slideTo = cardView.getHeight();
        if (slideTo <= 0) slideTo = getResources().getDisplayMetrics().heightPixels;
        cardView.animate()
                .translationY(slideTo)
                .setDuration(ANIM_OUT_MS)
                .setInterpolator(new AccelerateInterpolator(2f))
                .withEndAction(() -> {
                    BottomSheetActivity.super.finish();
                    overridePendingTransition(0, 0);
                })
                .start();
    }

    private void playEnterAnimation() {
        if (rootView == null || cardView == null || backdropDrawable == null) return;
        ObjectAnimator.ofInt(backdropDrawable, "alpha", 0, 255)
                .setDuration(ANIM_IN_MS)
                .start();
        final FrameLayout card = cardView;
        // post() waits for layout so getHeight() is non-zero before we translate off-screen.
        card.post(() -> {
            int h = card.getHeight();
            if (h <= 0) h = getResources().getDisplayMetrics().heightPixels / 2;
            card.setTranslationY(h);
            card.animate()
                    .translationY(0)
                    .setDuration(ANIM_IN_MS)
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .start();
        });
    }

    /** Full-screen FrameLayout with tap-to-dismiss backdrop and a bottom-anchored card. */
    private View buildSheetContainer(View content) {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Mutable ColorDrawable so we can animate alpha for fade-in/out.
        ColorDrawable backdrop = new ColorDrawable(BACKDROP);
        backdrop.setAlpha(0);
        root.setBackground(backdrop);
        root.setOnClickListener(v -> finish());

        FrameLayout card = new FrameLayout(this);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.BOTTOM;
        card.setLayoutParams(cardLp);
        card.setBackground(buildCardBackground());
        // Absorb taps so they don't fall through to the backdrop's dismiss listener.
        card.setClickable(true);
        card.setFocusable(true);

        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int padTop = dp(16), padH = dp(0), padBottom = dp(0);
        contentLp.setMargins(padH, padTop, padH, padBottom);
        card.addView(content, contentLp);

        root.addView(card);
        rootView = root;
        cardView = card;
        backdropDrawable = backdrop;
        return root;
    }

    private GradientDrawable buildCardBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SHEET_FILL);
        bg.setStroke(dp(1), SHEET_BORDER);
        float r = dp(SHEET_CORNER_RADIUS_DP);
        // Top corners rounded; bottom flush with screen edge.
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return bg;
    }

    /** Parses {@code .../<routeKey>/<id>[?<query>]}. Returns null on malformed URLs. */
    @Nullable
    private static ParsedRoute parseRoute(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() < 2) return null;
        String routeKey = segments.get(0);
        String id = segments.get(1);
        if (routeKey == null || routeKey.isEmpty() || id == null || id.isEmpty()) return null;
        Map<String, String> params = new HashMap<>();
        try {
            for (String k : uri.getQueryParameterNames()) {
                String v = uri.getQueryParameter(k);
                params.put(k, v == null ? "" : v);
            }
        } catch (UnsupportedOperationException ignored) {
            // Opaque URI: no query params to parse.
        }
        return new ParsedRoute(routeKey, id, Collections.unmodifiableMap(params));
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private static final class ParsedRoute {
        final String routeKey;
        final String artifactId;
        final Map<String, String> params;
        ParsedRoute(String routeKey, String artifactId, Map<String, String> params) {
            this.routeKey = routeKey;
            this.artifactId = artifactId;
            this.params = params;
        }
    }

    /** Activity-backed {@link SheetContext} handed to the SheetView at build time. */
    private final class ActivitySheetContext implements SheetContext {
        private final ParsedRoute route;
        ActivitySheetContext(ParsedRoute route) { this.route = route; }
        @Override public android.content.Context androidContext() { return BottomSheetActivity.this; }
        @Override public String routeKey() { return route.routeKey; }
        @Override public String artifactId() { return route.artifactId; }
        @Override public Map<String, String> params() { return route.params; }
        @Override public void dismiss() { finish(); }
    }
}
