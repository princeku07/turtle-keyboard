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

    /** Spreadsheet ID of the user's active "Turtle Splits" sheet. May be a sheet this user
     *  owns (default), or one they joined via an invite link. */
    public static final String SHEET_ID = "split_sheet_id";

    /** Email of the user who owns the active sheet. When equal to {@link #ACCOUNT_EMAIL},
     *  this user is the owner; otherwise they're a joined collaborator. */
    public static final String OWNER_EMAIL = "split_owner_email";

    /** "1" once existing local rows have been pushed up to the user's new sheet. */
    public static final String MIGRATED_LOCAL = "split_migrated_local";

    /** Drive permissionId for the active "anyone with link can edit" share, when the
     *  owner has membership open. Empty / unset means no link share is active. */
    public static final String ANYONE_PERMISSION_ID = "split_anyone_permission_id";

    private SplitKeys() {}
}
