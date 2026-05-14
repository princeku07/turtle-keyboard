package com.prince.turtlekeyboard.integration.drive;

/**
 * Keys the Drive integration persists into its scoped {@code ctx.store("drive")} view.
 * Names are unprefixed — the scoping layer adds {@code drive.} on disk.
 *
 * <p>Auth-related state (signed-in flag, access token, expiry, account email) lives in
 * the shared {@code google} namespace owned by
 * {@link com.prince.kbd.core.GoogleAuthImpl} — not here.
 */
public final class DriveKeys {

    /**
     * Newline-separated reference-photo entries for /us. Each line has the format
     * {@code <absolute-local-path>|<drive-file-id-or-empty>}; an empty file-id means
     * upload is pending or failed and the entry will be retried on next entry to
     * {@code DriveLinkActivity}.
     *
     * <p>Local copies live under {@code getFilesDir/drive_photos/} so thumbnails work
     * offline and the upload retry path doesn't depend on the source URI still being
     * valid.
     */
    public static final String REFERENCE_PHOTOS = "reference_photos";

    /** Soft cap mirroring the PRD-style "3–5 selfies" guidance. */
    public static final int MAX_REFERENCE_PHOTOS = 5;

    /** Name prefix used when uploading a reference photo to Drive. Kept short so the
     *  file is recognizable in the user's Drive without leaking marketing copy. */
    public static final String UPLOAD_NAME_PREFIX = "turtle-us-ref-";

    private DriveKeys() {}
}
