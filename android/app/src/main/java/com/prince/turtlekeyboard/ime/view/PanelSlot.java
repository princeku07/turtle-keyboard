package com.prince.turtlekeyboard.ime.view;

import android.inputmethodservice.KeyboardView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * Mounts a single panel into a host that replaces the keys for its lifetime.
 * Used by Quick / Emoji / History / MoreActions panels — all key-band-sized,
 * all dismissed the same way. AI assist uses its own slot (above the strip)
 * and is not routed through here.
 */
public final class PanelSlot {

    private final ViewGroup host;
    private final KeyboardView keys;
    @Nullable private Runnable onUnmount;

    public PanelSlot(ViewGroup host, KeyboardView keys) {
        this.host = host;
        this.keys = keys;
        // Empty slot = hidden host; enforce the invariant instead of relying on the
        // layout XML's android:visibility attribute (drifts on view re-inflation).
        this.host.setVisibility(View.GONE);
    }

    /** Fires whenever a mounted panel is removed (by replacement or hide), so the IME
     *  can drop any input-target reference pinned to the outgoing panel. Panels that
     *  exit input mode on their own already null the slot themselves; this covers the
     *  external-teardown paths (panel-replaces-panel, IME view recreation) that would
     *  otherwise leave {@code activeInputTarget} pointing at a removed view — the
     *  symptom is delete/backspace silently routing to a dead panel. */
    public void setOnUnmount(@Nullable Runnable r) {
        this.onUnmount = r;
    }

    /** Replaces any existing child with {@code panel}, sizes to the key band, hides keys. */
    public void show(View panel) {
        int targetHeight = keys.getHeight();
        if (host.getChildCount() > 0 && onUnmount != null) onUnmount.run();
        host.removeAllViews();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                targetHeight > 0 ? targetHeight : FrameLayout.LayoutParams.WRAP_CONTENT);
        host.addView(panel, lp);
        host.setVisibility(View.VISIBLE);
        keys.setVisibility(View.GONE);
    }

    public void hide() {
        if (host.getChildCount() > 0 && onUnmount != null) onUnmount.run();
        host.removeAllViews();
        host.setVisibility(View.GONE);
        keys.setVisibility(View.VISIBLE);
    }

    public boolean isVisible() {
        return host.getVisibility() == View.VISIBLE;
    }

    /** First mounted child, or null. Callers use {@code instanceof} to identify the active panel. */
    @Nullable
    public View currentChild() {
        return host.getChildCount() > 0 ? host.getChildAt(0) : null;
    }
}
