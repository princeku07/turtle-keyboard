package com.prince.kbd.core;

import androidx.annotation.Nullable;

import java.util.Set;

/**
 * Lookup for {@link AppProfile}s the keyboard knows about, plus per-app enrollment state.
 * Integrations call {@link #get} to decide chip/activation; the IME calls
 * {@link #statusFor} to decide whether to offer the user an enrollment banner.
 */
public interface AppProfileRegistry {

    enum Status {
        UNKNOWN,
        ENROLLED,
        SUPPRESSED
    }

    @Nullable AppProfile get(@Nullable String pkg);
    Status statusFor(@Nullable String pkg);
    void enroll(String pkg);
    void suppress(String pkg);
    Set<String> enrolledPackages();
    Set<String> suppressedPackages();
    void unenroll(String pkg);
    void unsuppress(String pkg);
}
