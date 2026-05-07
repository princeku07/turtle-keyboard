package com.prince.split;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;

import androidx.annotation.Nullable;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;

import com.prince.kbd.core.KeyValueStore;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Google sign-in + OAuth access tokens for the user's own Sheets/Drive.
 *
 * <p>Uses the modern {@code AuthorizationClient} API (part of {@code play-services-auth}).
 * On first sign-in the user sees Google's consent sheet for the {@code drive.file} scope,
 * which lets this app create + edit only the one spreadsheet it owns — never any of the
 * user's other Drive files.
 *
 * <p>Access tokens last ~1 hour and are cached in {@link KeyValueStore}. When expired,
 * {@link #freshAccessToken(Activity, AuthCallback)} re-runs authorization silently if
 * consent has already been granted, otherwise re-prompts.
 */
public final class SplitAuth {

    /** Sentinel returned in {@link AuthCallback#onError} when the user must complete UI. */
    public static final String ERROR_NEEDS_UI = "needs_ui";

    /** Read/write any spreadsheet the user has been granted access to (sensitive scope). */
    private static final String SCOPE_SPREADSHEETS = "https://www.googleapis.com/auth/spreadsheets";
    /** Lets the OWNER call drive.permissions.create on sheets they created. */
    private static final String SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file";
    /** So we can show "signed in as <email>" in the UI and identify owner vs joiner. */
    private static final String SCOPE_EMAIL = "https://www.googleapis.com/auth/userinfo.email";

    /** Buffer subtracted from token expiry so we refresh before the token actually dies. */
    private static final long REFRESH_SKEW_MS = 60_000L;

    private static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo";
    private static final ExecutorService EMAIL_EXEC = Executors.newSingleThreadExecutor();

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

    private final KeyValueStore store;
    private final Context appContext;

    public SplitAuth(Context appContext, KeyValueStore store) {
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
                        new com.google.android.gms.common.api.Scope(SCOPE_SPREADSHEETS),
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
        } else if (store.getString(SplitKeys.ACCOUNT_EMAIL, "").isEmpty()) {
            // AuthorizationResult won't surface the account when sign-in wasn't part of
            // this request; fall back to userinfo for the email scope we did grant.
            fetchAndStoreEmail(token);
        }
        cb.onToken(token);
    }

    /**
     * Background fetch of the signed-in user's email from Google's userinfo endpoint
     * and persists it in {@link SplitKeys#ACCOUNT_EMAIL}. Best-effort — silent on failure.
     * Public so the host can also call this on resume if the local email is empty and a
     * cached token is available.
     */
    public void fetchAndStoreEmailIfMissing() {
        if (!store.getString(SplitKeys.ACCOUNT_EMAIL, "").isEmpty()) return;
        String token = cachedAccessToken();
        if (token == null) return;
        fetchAndStoreEmail(token);
    }

    private void fetchAndStoreEmail(final String accessToken) {
        EMAIL_EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(USERINFO_URL).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) return;
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                String email = new JSONObject(sb.toString()).optString("email", "");
                if (!email.isEmpty()) {
                    store.putString(SplitKeys.ACCOUNT_EMAIL, email);
                    // Also backfill OWNER_EMAIL for legacy installs that have a sheet but
                    // no owner stamp — they necessarily own it (joining didn't exist yet).
                    if (!store.getString(SplitKeys.SHEET_ID, "").isEmpty()
                            && store.getString(SplitKeys.OWNER_EMAIL, "").isEmpty()) {
                        store.putString(SplitKeys.OWNER_EMAIL, email);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /** Lets the host app stamp the email after a separate Sign-In flow if needed. */
    public void setAccountEmail(String email) {
        store.putString(SplitKeys.ACCOUNT_EMAIL, email == null ? "" : email);
    }
}
