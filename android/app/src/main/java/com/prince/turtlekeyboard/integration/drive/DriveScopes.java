package com.prince.turtlekeyboard.integration.drive;

import java.util.Collections;
import java.util.Set;

/**
 * OAuth scopes Drive-backed commands need from Google. Currently just {@code drive.file}
 * — the app can only read / write files it created, never the user's broader Drive.
 * That's the whole point of this scope: minimal consent dialog, no full-Drive Google
 * verification process required.
 *
 * <p>Powers /us (reference photo storage). Future Drive-backed commands extend this
 * set if needed.
 */
public final class DriveScopes {

    public static final String SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file";

    public static final Set<String> SCOPES = Collections.singleton(SCOPE_DRIVE_FILE);

    private DriveScopes() {}
}
