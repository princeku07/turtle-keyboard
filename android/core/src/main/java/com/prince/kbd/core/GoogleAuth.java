package com.prince.kbd.core;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;

import androidx.annotation.Nullable;

import java.util.Set;

/**
 * Cross-module Google OAuth port. Modules declare per-call which scopes they need;
 * tokens are cached in shared storage so a scope already granted to one module is
 * reused by another without a second consent dialog. Opt-in — modules that don't
 * need Google APIs simply don't call this.
 */
public interface GoogleAuth {

    /** Sentinel reason in {@link Callback#onError} when the user must complete UI. */
    String ERROR_NEEDS_UI = "needs_ui";

    interface Callback {
        void onToken(String accessToken);
        /** @param reason {@link #ERROR_NEEDS_UI} or a human-readable failure */
        void onError(String reason, @Nullable PendingUi pendingUi);
    }

    /** Wraps an {@link IntentSender} the host {@link Activity} must launch to finish auth. */
    final class PendingUi {
        public final IntentSender intentSender;
        public PendingUi(IntentSender s) { this.intentSender = s; }
    }

    boolean isSignedIn();

    @Nullable String accountEmail();

    /** Drops the cached token + signed-in state. Does not revoke server-side. */
    void signOut();

    /** @return cached non-expired token if present, else null. Caller is responsible for
     *  retrying via {@link #freshToken} on a 401 response. */
    @Nullable String cachedToken();

    /**
     * Hands back a valid access token for {@code scopes} via {@code cb}. Fires
     * synchronously when the cache is good; otherwise launches authorization
     * (silent if scopes were already granted, UI prompt if not).
     *
     * @param activity host activity for consent UI; null only when the cached token
     *                 already covers the scopes (no UI possible from a Service).
     */
    void freshToken(@Nullable Activity activity, Set<String> scopes, Callback cb);

    /** Forces a new authorization round-trip — useful after a 401 from a Google API. */
    void authorize(@Nullable Activity activity, Set<String> scopes, Callback cb);

    /** Completes authorization after the host {@link Activity} returned from the
     *  {@link PendingUi} {@link IntentSender}. */
    void onAuthorizationResult(@Nullable Activity activity, @Nullable Intent data, Callback cb);

    /**
     * Hands back a Google ID token (JWT) via {@code cb}. ID tokens bootstrap a
     * Firebase session; they are distinct from the access tokens {@link #freshToken}
     * returns. No cache: ID tokens are only needed once per sign-in.
     *
     * <p>Requires the impl to be constructed with a non-null {@code webClientId}.
     * Without one, fails with {@code "id_token_not_configured"}.
     *
     * @param activity required for the One Tap consent UI; null returns
     *                 {@code "activity required for id token"}.
     */
    void freshIdToken(@Nullable Activity activity, Callback cb);

    /** Completes the ID token flow after the host {@link Activity} returned from the
     *  {@link PendingUi} {@link IntentSender}. */
    void onSignInResult(@Nullable Activity activity, @Nullable Intent data, Callback cb);

    /** Best-effort fetch of the signed-in user's email if it's missing locally.
     *  Silent on failure. */
    void fetchAndStoreEmailIfMissing();

    /** Stamp the email manually after a separate sign-in flow. */
    void setAccountEmail(String email);
}
