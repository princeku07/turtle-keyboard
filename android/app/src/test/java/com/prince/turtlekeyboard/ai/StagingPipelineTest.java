package com.prince.turtlekeyboard.ai;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.prince.kbd.core.IntegrationContext;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class StagingPipelineTest {

    private StagingPipeline pipeline;

    @Before
    public void setUp() {
        pipeline = new StagingPipeline();
    }

    @Test
    public void edit_stage_then_consume_returns_payload_once() {
        byte[] bytes = {1, 2, 3};
        pipeline.stageEditImage(bytes, "image/png");
        ClipImage first = pipeline.consumeEditImage();
        assertNotNull(first);
        assertArrayEquals(bytes, first.bytes);
        assertEquals("image/png", first.mime);
        // Second consume returns null — read-and-clear semantics.
        assertNull(pipeline.consumeEditImage());
    }

    @Test
    public void edit_stage_null_clears_slot_and_fires_listener_with_null() {
        final boolean[] fired = {false};
        final byte[][] bytesSeen = {null};
        pipeline.setEditListener((b, m) -> {
            fired[0] = true;
            bytesSeen[0] = b;
        });
        pipeline.stageEditImage(null, null);
        assertTrue("listener not fired", fired[0]);
        assertNull(bytesSeen[0]);
        assertNull(pipeline.consumeEditImage());
    }

    @Test
    public void edit_stage_defaults_mime_to_png_when_null() {
        pipeline.stageEditImage(new byte[]{1}, null);
        assertEquals("image/png", pipeline.consumeEditImage().mime);
    }

    @Test
    public void edit_listener_null_is_silent() {
        pipeline.setEditListener(null);
        // Must not throw.
        pipeline.stageEditImage(new byte[]{1}, "image/jpeg");
    }

    @Test
    public void us_stage_then_consume_returns_list_once() {
        List<byte[]> imgs = Arrays.asList(new byte[]{1}, new byte[]{2});
        List<String> mimes = Arrays.asList("image/jpeg", "image/png");
        pipeline.stageUsImages(imgs, mimes);
        List<ReferenceImage> consumed = pipeline.consumeUsImages();
        assertNotNull(consumed);
        assertEquals(2, consumed.size());
        assertEquals("image/jpeg", consumed.get(0).mime);
        assertEquals("image/png", consumed.get(1).mime);
        assertNull(pipeline.consumeUsImages());
    }

    @Test
    public void us_stage_empty_list_clears_slot() {
        pipeline.stageUsImages(Arrays.asList(new byte[]{1}), Arrays.asList("image/png"));
        pipeline.stageUsImages(Collections.emptyList(), Collections.emptyList());
        assertNull(pipeline.consumeUsImages());
    }

    @Test
    public void us_stage_defaults_mime_when_missing() {
        // Empty mime list → entries default to image/jpeg.
        pipeline.stageUsImages(Arrays.asList(new byte[]{1}), Collections.emptyList());
        assertEquals("image/jpeg", pipeline.consumeUsImages().get(0).mime);
    }

    @Test
    public void us_listener_fires_on_stage() {
        final boolean[] fired = {false};
        pipeline.setUsListener((bytes, mimes) -> fired[0] = true);
        pipeline.stageUsImages(
                Arrays.asList(new byte[]{1}), Arrays.asList("image/jpeg"));
        assertTrue(fired[0]);
    }

    @Test
    public void consumeEditImageAsPicked_wraps_in_spi_type_and_clears() {
        pipeline.stageEditImage(new byte[]{9, 8}, "image/png");
        IntegrationContext.PickedImage picked = pipeline.consumeEditImageAsPicked();
        assertNotNull(picked);
        assertArrayEquals(new byte[]{9, 8}, picked.bytes);
        assertEquals("image/png", picked.mime);
        assertNull(pipeline.consumeEditImageAsPicked());
    }

    @Test
    public void consumeEditImageAsPicked_when_empty_returns_null() {
        assertNull(pipeline.consumeEditImageAsPicked());
    }

    @Test
    public void listener_changes_after_setting_a_new_one() {
        final int[] firstCount = {0}, secondCount = {0};
        StagingPipeline.EditImageListener first = (b, m) -> firstCount[0]++;
        StagingPipeline.EditImageListener second = (b, m) -> secondCount[0]++;
        pipeline.setEditListener(first);
        pipeline.stageEditImage(new byte[]{1}, "image/png");
        pipeline.setEditListener(second);
        pipeline.stageEditImage(new byte[]{2}, "image/png");
        assertEquals(1, firstCount[0]);
        assertEquals(1, secondCount[0]);
    }

    @Test
    public void consume_independence_between_slots() {
        pipeline.stageEditImage(new byte[]{1}, "image/png");
        pipeline.stageUsImages(
                Arrays.asList(new byte[]{2}), Arrays.asList("image/jpeg"));
        assertNotNull(pipeline.consumeEditImage());
        // Consuming edit slot must not affect us slot.
        assertNotNull(pipeline.consumeUsImages());
        assertFalse(false);  // sanity
    }
}
