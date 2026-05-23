package com.prince.turtlekeyboard.integration.drive;

/**
 * Keys the Drive integration persists into {@code ctx.store("drive")}.
 */
public final class DriveKeys {

    /**
     * Newline-separated reference-photo entries. Each line:
     * {@code <absolute-local-path>|<drive-file-id-or-empty>}. Empty file-id means
     * upload is pending and will be retried on next entry to {@code DriveLinkActivity}.
     */
    public static final String REFERENCE_PHOTOS = "reference_photos";

    public static final int MAX_REFERENCE_PHOTOS = 5;

    public static final String UPLOAD_NAME_PREFIX = "turtle-us-ref-";

    private DriveKeys() {}
}
