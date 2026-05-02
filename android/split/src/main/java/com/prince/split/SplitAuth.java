package com.prince.split;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;

import androidx.annotation.Nullable;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;

import java.util.Arrays;

/**
 * Google sign-in + OAuth access tokens for the user's own Sheets/Drive.
 *
 * <p>Uses the modern {@code AuthorizationClient} API (part of {@code play-services-auth}).
 * On first sign-in the user sees Google's consent sheet for the {@code drive.file} scope,
 * which lets this app create + edit only the one spreadsheet it owns — never any of the
 * user's other Drive files.
 *
 * <p>Access tokens last ~1 hour and are cached in {@link SplitStore}. When expired,
 * {@link #freshAccessToken(Activity, AuthCallback)} re-runs authorization silently if
 * consent has already been granted, otherwise re-prompts.
 */
public final class SplitAuth {

    /** Sentinel returned in {@link AuthCallback#onError} when the user must complete UI. */
    public static final String ERROR_NEEDS_UI = "needs_ui";

    /** Required for the spreadsheet create + read + write the SDK does. */
    private static final String SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file";
    /** So we can show "signed in as <email>" in the UI; not required for Sheets/Drive REST. */
    private static final String SCOPE_EMAIL = "https://www.googleapis.com/auth/userinfo.email";

    /** Buffer subtracted from token expiry so we refresh before the token actually dies. */
    private static final long REFRESH_SKEW_MS = 60_000L;

    public interface AuthCallback {
        void onToken(String accessToken);
        /** @param reason {@link #ERROR_NEEDS_UI} or a human-readable failure */
        void onError(String reason, @Nullable PendingUi pendingUi);
    }

    /** Wraps an {@link IntentSender} the host {@link Activity} must launch to finish auth. */
    public static final class PendingUi {
        public final IntentSender intentSender;
        PendingUi(IntentSender s) { this.intentSender = s; }
    }

    private final SplitStore store;
    private final Context appContext;

    public SplitAuth(Context appContext, SplitStore store) {
        this.appContext = appContext.getApplicationContext();
        this.store = store;
    }

    public boolean isSignedIn() {
        return "1".equals(store.getString(SplitKeys.SIGNED_IN, ""));
    }

    @Nullable
    public String accountEmail() {
        String e = store.getString(SplitKeys.ACCOUNT_EMAIL, "");
        return e.isEmpty() ? null : e;
    }

    public void signOut() {
        store.putString(SplitKeys.SIGNED_IN, "");
        store.putString(SplitKeys.ACCOUNT_EMAIL, "");
        store.putString(SplitKeys.ACCESS_TOKEN, "");
        store.putString(SplitKeys.TOKEN_EXPIRES_AT, "0");
        // Note: SHEET_ID, MIGRATED_LOCAL, and HISTORY are deliberately left intact so the
        // user can sign in again on the same install without losing their saved splits.
    }

    /** Returns a non-expired access token if one is cached, else {@code null}. */
    @Nullable
    public String cachedAccessToken() {
        String token = store.getString(SplitKeys.ACCESS_TOKEN, "");
        if (token.isEmpty()) return null;
        long expiresAt;
        try {
            expiresAt = Long.parseLong(store.getString(SplitKeys.TOKEN_EXPIRES_AT, "0"));
        } catch (NumberFormatException e) {
            expiresAt = 0;
        }
        if (System.currentTimeMillis() + REFRESH_SKEW_MS >= expiresAt) return null;
        return token;
    }

    /**
     * Hands back a valid access token via {@code cb}. If the cached token is still good,
     * the callback fires synchronously on the calling thread. Otherwise launches the
     * authorization flow (silent if consent was previously granted, UI if not).
     */
    public void freshAccessToken(@Nullable Activity activity, final AuthCallback cb) {
        String cached = cachedAccessToken();
        if (cached != null) {
            cb.onToken(cached);
            return;
        }
        authorize(activity, cb);
    }

    /** Forces a new authorization round-trip; useful after a 401 response. */
    public void authorize(@Nullable Activity activity, final AuthCallback cb) {
        AuthorizationRequest req = AuthorizationRequest.builder()
                .setRequestedScopes(Arrays.asList(
                        new com.google.android.gms.common.api.Scope(SCOPE_DRIVE_FILE),
                        new com.google.android.gms.common.api.Scope(SCOPE_EMAIL)))
                .build();

        Context ctx = activity != null ? activity : appContext;
        Identity.getAuthorizationClient(ctx)
                .authorize(req)
                .addOnSuccessListener(result -> handleAuthSuccess(result, cb))
                .addOnFailureListener(e -> cb.onError(e.getMessage(), null));
    }

    /**
     * Completes authorization after the host {@link Activity} returned from the
     * {@link PendingUi} {@link IntentSender}. Pass the activity result {@link Intent} here.
     */
    public void onAuthorizationResult(@Nullable Activity activity, @Nullable Intent data, final AuthCallback cb) {
        if (activity == null) {
            cb.onError("activity required to finish auth", null);
            return;
        }
        try {
            AuthorizationResult result = Identity.getAuthorizationClient(activity)
                    .getAuthorizationResultFromIntent(data);
            handleAuthSuccess(result, cb);
        } catch (Exception e) {
            cb.onError(e.getMessage(), null);
        }
    }

    private void handleAuthSuccess(AuthorizationResult result, AuthCallback cb) {
        if (result.hasResolution()) {
            cb.onError(ERROR_NEEDS_UI, new PendingUi(result.getPendingIntent().getIntentSender()));
            return;
        }
        String token = result.getAccessToken();
        if (token == null || token.isEmpty()) {
            cb.onError("no access token in result", null);
            return;
        }
        // AuthorizationResult does not expose expiry; tokens are 1h. Cache 55min to be safe.
        long expiresAt = System.currentTimeMillis() + 55L * 60_000L;
        store.putString(SplitKeys.SIGNED_IN, "1");
        store.putString(SplitKeys.ACCESS_TOKEN, token);
        store.putString(SplitKeys.TOKEN_EXPIRES_AT, String.valueOf(expiresAt));

        if (result.toGoogleSignInAccount() != null
                && result.toGoogleSignInAccount().getEmail() != null) {
            store.putString(SplitKeys.ACCOUNT_EMAIL,
                    result.toGoogleSignInAccount().getEmail());
        }
        cb.onToken(token);
    }

    /** Lets the host app stamp the email after a separate Sign-In flow if needed. */
    public void setAccountEmail(String email) {
        store.putString(SplitKeys.ACCOUNT_EMAIL, email == null ? "" : email);
    }
}
