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

    public PanelSlot(ViewGroup host, KeyboardView keys) {
        this.host = host;
        this.keys = keys;
        // Establish the empty/hidden initial state ourselves rather than relying on the
        // host's inflated visibility — show()/hide() own this property, so the constructor
        // should too. No-op in production where the layout already marks the host GONE.
        host.setVisibility(View.GONE);
    }

    /** Replaces any existing child with {@code panel}, sizes to the key band, hides keys. */
    public void show(View panel) {
        int targetHeight = keys.getHeight();
        host.removeAllViews();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                targetHeight > 0 ? targetHeight : FrameLayout.LayoutParams.WRAP_CONTENT);
        host.addView(panel, lp);
        host.setVisibility(View.VISIBLE);
        keys.setVisibility(View.GONE);
    }

    public void hide() {
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
