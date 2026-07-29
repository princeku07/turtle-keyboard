package com.prince.turtlekeyboard.ime.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.inputmethodservice.KeyboardView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class PanelSlotTest {

    private Context ctx;
    private FrameLayout host;
    private KeyboardView keys;
    private PanelSlot slot;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        host = new FrameLayout(ctx);
        keys = new KeyboardView(ctx, null);
        slot = new PanelSlot(host, keys);
    }

    @Test
    public void initial_state_invisible_and_empty() {
        assertFalse(slot.isVisible());
        assertNull(slot.currentChild());
    }

    @Test
    public void show_mounts_panel_and_hides_keys() {
        View panel = new View(ctx);
        slot.show(panel);
        assertEquals(View.VISIBLE, host.getVisibility());
        assertEquals(View.GONE, keys.getVisibility());
        assertTrue(slot.isVisible());
        assertSame(panel, slot.currentChild());
    }

    @Test
    public void show_replaces_existing_child() {
        View first = new View(ctx);
        View second = new View(ctx);
        slot.show(first);
        slot.show(second);
        assertSame(second, slot.currentChild());
        assertEquals(1, host.getChildCount());
    }

    @Test
    public void hide_clears_host_and_reveals_keys() {
        slot.show(new View(ctx));
        slot.hide();
        assertEquals(View.GONE, host.getVisibility());
        assertEquals(View.VISIBLE, keys.getVisibility());
        assertFalse(slot.isVisible());
        assertNull(slot.currentChild());
    }

    @Test
    public void hide_when_already_hidden_is_safe() {
        // Defensive call before any show().
        slot.hide();
        assertFalse(slot.isVisible());
    }
}
