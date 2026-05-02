package com.prince.split.kbd;

import androidx.annotation.Nullable;

/**
 * Lookup for {@link AppProfile}s the keyboard knows about, plus per-app enrollment state.
 * Integrations call {@link #get} to decide chip/activation; the IME calls
 * {@link #statusFor} to decide whether to offer the user an enrollment banner.
 *
 * <p>{@link Status#UNKNOWN} packages are candidates for enrollment. Seeded apps that the
 * IME pre-enrolls (e.g. payment apps that already light up the chip) start
 * {@link Status#ENROLLED} so the user is never prompted twice for an already-active app.
 */
public interface AppProfileRegistry {

    enum Status {
        /** Never seen by the user / not enrolled / not suppressed — banner candidate. */
        UNKNOWN,
        /** User accepted the app (or it was auto-enrolled because it's pre-seeded). */
        ENROLLED,
        /** User asked us to stop offering this app. */
        SUPPRESSED
    }

    /** @return the profile for {@code pkg}, or null if it can't be resolved on this device. */
    @Nullable AppProfile get(@Nullable String pkg);

    Status statusFor(@Nullable String pkg);

    /** User-driven: mark this package as enrolled. No-op if already enrolled. */
    void enroll(String pkg);

    /** User-driven: never auto-prompt for this package again. */
    void suppress(String pkg);

    /** Snapshot of all currently-enrolled package names. Used by the keyboard to replay
     *  per-app shortcut registrations on cold start. */
    java.util.Set<String> enrolledPackages();

    /** Snapshot of all suppressed package names. Used by the settings UI to let the user
     *  undo a "don't ask again" decision. */
    java.util.Set<String> suppressedPackages();

    /** Reverse {@link #enroll}: forget that this pkg was enrolled. The user will be
     *  offered the enrollment banner again next time they type in this app. */
    void unenroll(String pkg);

    /** Reverse {@link #suppress}: clear the "don't ask again" flag so the enrollment
     *  banner can re-appear. */
    void unsuppress(String pkg);
}
