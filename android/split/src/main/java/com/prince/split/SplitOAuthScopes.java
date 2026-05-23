package com.prince.split;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * OAuth scopes the Split module requests from Google: Sheets for the split book,
 * drive.file for owner-side permission management, and userinfo.email to identify
 * owner vs joiner.
 */
public final class SplitOAuthScopes {

    public static final String SCOPE_SPREADSHEETS = "https://www.googleapis.com/auth/spreadsheets";
    public static final String SCOPE_DRIVE_FILE   = "https://www.googleapis.com/auth/drive.file";
    public static final String SCOPE_EMAIL        = "https://www.googleapis.com/auth/userinfo.email";

    public static final Set<String> SCOPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(SCOPE_SPREADSHEETS, SCOPE_DRIVE_FILE, SCOPE_EMAIL)));

    private SplitOAuthScopes() {}
}
