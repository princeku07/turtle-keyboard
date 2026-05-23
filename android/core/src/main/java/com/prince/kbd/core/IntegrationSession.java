package com.prince.kbd.core;

/**
 * A live integration bound to the current input session. Created by
 * {@link KeyboardIntegration#activate}; torn down via {@link #onDeactivate} on field
 * change or input end.
 */
public interface IntegrationSession {

    /** Field text changed (cursor move, keystroke, paste). */
    void onTextChanged(CharSequence before, CharSequence after);

    /** Cleanup hook — remove panel views, hide chip, drop references. */
    void onDeactivate();
}
