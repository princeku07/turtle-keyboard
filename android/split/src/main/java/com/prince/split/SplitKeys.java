package com.prince.split;

/**
 * Keys the Split module persists into its scoped {@code ctx.store("split")} view.
 * Names are unprefixed — the scoping layer adds {@code split.} on disk.
 */
public final class SplitKeys {

    public static final String HISTORY = "history";
    public static final String DEFAULT_PEOPLE = "default_people";
    public static final String DEVICE_ID = "device_id";
    public static final String ENABLED = "enabled";

    public static final String SIGNED_IN = "signed_in";
    public static final String ACCOUNT_EMAIL = "account_email";
    public static final String ACCESS_TOKEN = "access_token";
    public static final String TOKEN_EXPIRES_AT = "token_expires_at";
    public static final String SHEET_ID = "sheet_id";
    public static final String OWNER_EMAIL = "owner_email";
    public static final String MIGRATED_LOCAL = "migrated_local";
    public static final String ANYONE_PERMISSION_ID = "anyone_permission_id";

    private SplitKeys() {}
}
