package com.prince.split;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * OAuth scopes Split needs from Google. Spreadsheets for the Sheets-backed split book,
 * drive.file so the OWNER can call drive.permissions.create on sheets they created,
 * userinfo.email so we can show "signed in as &lt;email&gt;" and identify owner vs joiner.
 *
 * <p>Constants live in their own file so callers don't pull in the rest of {@code split/}
 * just to reference scope strings. Used at every {@link com.prince.kbd.core.GoogleAuth}
 * call from {@link SplitCloudSync} and the Split host activities.
 */
public final class SplitOAuthScopes {

    public static final String SCOPE_SPREADSHEETS = "https://www.googleapis.com/auth/spreadsheets";
    public static final String SCOPE_DRIVE_FILE   = "https://www.googleapis.com/auth/drive.file";
    public static final String SCOPE_EMAIL        = "https://www.googleapis.com/auth/userinfo.email";

    public static final Set<String> SCOPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(SCOPE_SPREADSHEETS, SCOPE_DRIVE_FILE, SCOPE_EMAIL)));

    private SplitOAuthScopes() {}
}
