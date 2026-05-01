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

    /** Apps Script web app URL (https://script.google.com/macros/s/.../exec). Empty = sync off. */
    public static final String CLOUD_ENDPOINT = "split_cloud_endpoint";

    /** Shared secret sent in the request body; matches the SHARED_TOKEN constant in Code.gs. */
    public static final String CLOUD_TOKEN = "split_cloud_token";

    /** Stable per-install UUID, generated on first sync. Identifies rows in the sheet. */
    public static final String DEVICE_ID = "split_device_id";

    private SplitKeys() {}
}
