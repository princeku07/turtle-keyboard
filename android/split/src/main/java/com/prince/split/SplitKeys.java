package com.prince.split;

/**
 * Keys the Split module persists into its scoped {@code ctx.store("split")} view.
 * Names are unprefixed — the scoping layer adds {@code split.} on disk.
 *
 * <p>Auth-related keys (signed-in flag, access token, expiry, account email) live in the
 * shared {@code google} namespace owned by {@link com.prince.kbd.core.GoogleAuthImpl},
 * so multiple modules can share one Google sign-in. See {@link SplitOAuthScopes} for the
 * scopes Split requests at authorization time.
 */
public final class SplitKeys {

    public static final String HISTORY = "history";
    public static final String DEFAULT_PEOPLE = "default_people";
    public static final String DEVICE_ID = "device_id";
    public static final String ENABLED = "enabled";

    public static final String SHEET_ID = "sheet_id";
    public static final String OWNER_EMAIL = "owner_email";
    public static final String MIGRATED_LOCAL = "migrated_local";
    public static final String ANYONE_PERMISSION_ID = "anyone_permission_id";

    private SplitKeys() {}
}
