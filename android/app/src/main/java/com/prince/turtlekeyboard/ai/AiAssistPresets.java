package com.prince.turtlekeyboard.ai;

import java.util.Arrays;
import java.util.List;

/**
 * Preset text-transform actions for the in-keyboard AI assist panel.
 * Each preset is a system prompt sent alongside the field's current text;
 * the model is instructed to return ONLY the rewritten text.
 */
public final class AiAssistPresets {

    /** Suffix appended to every preset (and any custom prompt) so the model returns clean text. */
    public static final String OUTPUT_RULES =
            "\nOutput ONLY the rewritten text. No preface, no quotes, no markdown, no explanation.";

    public static final class Preset {
        public final String label;
        public final String systemPrompt;
        public Preset(String label, String systemPrompt) {
            this.label = label;
            this.systemPrompt = systemPrompt;
        }
    }

    public static final List<Preset> DEFAULTS = Arrays.asList(
            new Preset("Fix grammar",
                    "Fix all grammar, spelling, and punctuation in the user's text. Preserve meaning, tone, and length."),
            new Preset("Proofread",
                    "Proofread the user's text. Fix grammar, spelling, punctuation, and any awkward phrasing while preserving meaning and tone."),
            new Preset("Make formal",
                    "Rewrite the user's text in a formal, professional tone. Preserve meaning."),
            new Preset("Make casual",
                    "Rewrite the user's text in a casual, friendly, conversational tone. Preserve meaning."),
            new Preset("Shorten",
                    "Rewrite the user's text to be as concise as possible without losing meaning."),
            new Preset("Translate to English",
                    "Translate the user's text into natural English. If it is already in English, return it unchanged.")
    );

    private AiAssistPresets() {}
}
