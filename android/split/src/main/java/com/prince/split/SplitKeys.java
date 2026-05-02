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

    /** Stable per-install UUID. Identifies rows written by this device in the user's sheet. */
    public static final String DEVICE_ID = "split_device_id";

    // -- Cloud sync (Google Sign-In + user-owned sheet) ----------------------

    /** "1" once the user has completed sign-in successfully. Survives token expiry —
     *  expired tokens just need a silent re-authorize, not a fresh consent flow. */
    public static final String SIGNED_IN = "split_signed_in";

    /** Google account email of the signed-in user, when the {@code email} scope is granted. */
    public static final String ACCOUNT_EMAIL = "split_account_email";

    /** Cached OAuth access token for the Sheets/Drive scope. */
    public static final String ACCESS_TOKEN = "split_access_token";

    /** Epoch millis at which {@link #ACCESS_TOKEN} expires. */
    public static final String TOKEN_EXPIRES_AT = "split_token_expires_at";

    /** Spreadsheet ID of the user's "Turtle Splits" sheet, created on first sign-in. */
    public static final String SHEET_ID = "split_sheet_id";

    /** "1" once existing local rows have been pushed up to the user's new sheet. */
    public static final String MIGRATED_LOCAL = "split_migrated_local";

    private SplitKeys() {}
}
