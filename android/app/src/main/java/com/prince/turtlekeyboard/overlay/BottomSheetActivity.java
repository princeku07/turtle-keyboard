package com.prince.turtlekeyboard.overlay;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
 * Hosts a bottom-sheet overlay triggered by a Turtle App Link
 * ({@code https://www.turtlekeyboard.com/<routeKey>/<id>}). Routes only — no input, no keyboard.
 * Parses the incoming URL, looks up the registered {@link SheetViewFactory}, mounts the
 * resulting view inside a card pinned to the bottom of the screen with a dimmed
 * tap-to-dismiss backdrop.
 *
 * <p>Translucent theme + slide-from-bottom window animation gives the perceived
 * "overlay over the user's previous app" without needing
 * {@code SYSTEM_ALERT_WINDOW}: the activity is launched in its own task, sits on top
 * of the chat, and dismisses back to wherever the user was.
 *
 * <p>Unknown routes (no factory registered for the path's first segment) finish
 * silently. Malformed URLs (no id segment, wrong scheme) finish silently.
 */
public class BottomSheetActivity extends AppCompatActivity {

    private static final String TAG = "BottomSheetActivity";

    private static final int BACKDROP = 0x99000000;        // ~60% black
    private static final int SHEET_FILL = 0xFFFFFFFF;
    private static final int SHEET_BORDER = 0xFF0C0C0C;    // ink
    private static final int SHEET_CORNER_RADIUS_DP = 18;

    @Nullable private SheetView sheetView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(R.anim.sheet_slide_in, 0);

        // Don't pop the soft keyboard up under us — the sheet has no input target.
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
    }

    @Override
    public void finish() {
        if (sheetView != null) {
            try { sheetView.onDismiss(); } catch (Throwable t) { Log.w(TAG, "onDismiss threw", t); }
            sheetView = null;
        }
        super.finish();
        overridePendingTransition(0, R.anim.sheet_slide_out);
    }

    /** Builds: full-screen FrameLayout with a tap-to-dismiss backdrop + a bottom-anchored
     *  card holding the SheetView's content. */
    private View buildSheetContainer(View content) {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(BACKDROP);
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
        // Inner padding keeps sheet content off the rounded corners + drag-affordance area.
        int padTop = dp(16), padH = dp(0), padBottom = dp(0);
        contentLp.setMargins(padH, padTop, padH, padBottom);
        card.addView(content, contentLp);

        root.addView(card);
        return root;
    }

    /** Rounded-top white card with a thin ink border on top of the dimmed backdrop. */
    private GradientDrawable buildCardBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SHEET_FILL);
        bg.setStroke(dp(1), SHEET_BORDER);
        float r = dp(SHEET_CORNER_RADIUS_DP);
        // Top-left, top-right rounded; bottom corners flush with screen edge.
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return bg;
    }

    /** Parses {@code https://www.turtlekeyboard.com/<routeKey>/<id>[?<query>]}. Returns null on
     *  any malformed URL — caller finishes the activity in that case. */
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
            // Opaque URI — no query params to parse, fine.
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
