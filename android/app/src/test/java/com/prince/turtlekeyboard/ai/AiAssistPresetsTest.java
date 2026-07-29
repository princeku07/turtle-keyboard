package com.prince.turtlekeyboard.ai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AiAssistPresetsTest {

    @Test
    public void defaults_not_empty() {
        assertFalse(AiAssistPresets.DEFAULTS.isEmpty());
    }

    @Test
    public void every_preset_has_label_and_prompt() {
        for (AiAssistPresets.Preset p : AiAssistPresets.DEFAULTS) {
            assertNotNull(p.label);
            assertNotNull(p.systemPrompt);
            assertFalse("empty label", p.label.isEmpty());
            assertFalse("empty prompt for " + p.label, p.systemPrompt.isEmpty());
        }
    }

    @Test
    public void output_rules_instructs_model_to_emit_only_rewrite() {
        // The suffix is appended to every prompt so the model returns clean text
        // ready to drop straight into the host editor — no preface, no markdown.
        assertTrue(AiAssistPresets.OUTPUT_RULES.toLowerCase().contains("output only"));
        assertTrue(AiAssistPresets.OUTPUT_RULES.toLowerCase().contains("no preface"));
    }

    @Test
    public void presets_cover_core_text_actions() {
        // Loose contract: the strip should at minimum offer Fix grammar +
        // a tone/translate path. Lock the labels we expose in the UI.
        boolean hasFix = false, hasTone = false, hasTranslate = false;
        for (AiAssistPresets.Preset p : AiAssistPresets.DEFAULTS) {
            String l = p.label.toLowerCase();
            if (l.contains("fix")) hasFix = true;
            if (l.contains("formal") || l.contains("casual")) hasTone = true;
            if (l.contains("translate")) hasTranslate = true;
        }
        assertTrue("missing grammar preset", hasFix);
        assertTrue("missing tone preset", hasTone);
        assertTrue("missing translate preset", hasTranslate);
    }
}
