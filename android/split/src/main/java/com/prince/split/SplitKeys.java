package com.prince.split;

/**
 * Persistent keys owned by the Split SDK. Both the keyboard and any future standalone
 * Split APK reference these so storage layouts stay aligned.
 */
public final class SplitKeys {

    /** Newline-delimited {@code amount|people|timestampMs} entries, most-recent first. */
    public static final String HISTORY = "split_history";

    /** Default number of people pre-filled in the stepper. */
    public static final String DEFAULT_PEOPLE = "split_default_people";

    private SplitKeys() {}
}
