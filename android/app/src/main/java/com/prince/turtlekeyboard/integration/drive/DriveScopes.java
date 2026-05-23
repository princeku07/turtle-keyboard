package com.prince.turtlekeyboard.integration.drive;

import java.util.Collections;
import java.util.Set;

/**
 * OAuth scopes Drive-backed commands need. Limited to {@code drive.file} — the app can
 * only access files it created.
 */
public final class DriveScopes {

    public static final String SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file";

    public static final Set<String> SCOPES = Collections.singleton(SCOPE_DRIVE_FILE);

    private DriveScopes() {}
}
