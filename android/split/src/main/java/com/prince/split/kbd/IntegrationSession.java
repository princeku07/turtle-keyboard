package com.prince.split.kbd;

/**
 * A live integration bound to the current input session. Created by
 * {@link KeyboardIntegration#activate} when the integration applies to the current host
 * + field; torn down via {@link #onDeactivate} on field change or input end.
 */
public interface IntegrationSession {

    /** Field text changed (cursor move, keystroke, paste). The session decides whether to
     *  surface a chip via the {@link IntegrationContext} it received in
     *  {@link KeyboardIntegration#activate}. */
    void onTextChanged(CharSequence before, CharSequence after);

    /** Cleanup hook — remove panel views, hide chip, drop references. */
    void onDeactivate();
}
