package com.prince.kbd.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.Nullable;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.BeginSignInRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.SignInCredential;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Play Services-backed implementation of {@link GoogleAuth}. Caches one access token
 * across all modules in the {@code google} namespace store; AuthorizationClient
 * incrementally grants scopes — so a module asking for a scope already granted to another
 * feature gets the token without a second consent dialog.
 *
 * <p>Construct one per Activity, or one for the IME service. Every instance backed by the
 * same {@link KeyValueStore} sees the same signed-in state.
 */
public final class GoogleAuthImpl implements GoogleAuth {

    /** Buffer subtracted from token expiry so we refresh before the token actually dies. */
    private static final long REFRESH_SKEW_MS = 60_000L;

    private static final String KEY_SIGNED_IN = "signed_in";
    private static final String KEY_ACCOUNT_EMAIL = "account_email";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_TOKEN_EXPIRES_AT = "token_expires_at";

    private static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo";
    private static final ExecutorService EMAIL_EXEC = Executors.newSingleThreadExecutor();

    private final Context appContext;
    private final KeyValueStore store;
    @Nullable private final String webClientId;

    /** @param store typically {@code prefsRoot.scoped("google")} so all modules share state */
    public GoogleAuthImpl(Context appContext, KeyValueStore store) {
        this(appContext, store, null);
    }

    /**
     * @param webClientId OAuth 2.0 web client ID from Firebase. Required for
     *                    {@link #freshIdToken}; pass {@code null} from modules
     *                    that only need access tokens for Google APIs (split,
     *                    notion, slack) — those keep working unchanged.
     */
    public GoogleAuthImpl(Context appContext, KeyValueStore store,
                          @Nullable String webClientId) {
        this.appContext = appContext.getApplicationContext();
        this.store = store;
        this.webClientId = (webClientId == null || webClientId.isEmpty()) ? null : webClientId;
    }

    @Override public boolean isSignedIn() {
        return "1".equals(store.getString(KEY_SIGNED_IN, ""));
    }

    @Override @Nullable
    public String accountEmail() {
        String e = store.getString(KEY_ACCOUNT_EMAIL, "");
        return e.isEmpty() ? null : e;
    }

    @Override public void signOut() {
        store.putString(KEY_SIGNED_IN, "");
        store.putString(KEY_ACCOUNT_EMAIL, "");
        store.putString(KEY_ACCESS_TOKEN, "");
        store.putString(KEY_TOKEN_EXPIRES_AT, "0");
    }

    @Override @Nullable
    public String cachedToken() {
        String token = store.getString(KEY_ACCESS_TOKEN, "");
        if (token.isEmpty()) return null;
        long expiresAt;
        try {
            expiresAt = Long.parseLong(store.getString(KEY_TOKEN_EXPIRES_AT, "0"));
        } catch (NumberFormatException e) {
            expiresAt = 0;
        }
        if (System.currentTimeMillis() + REFRESH_SKEW_MS >= expiresAt) return null;
        return token;
    }

    @Override
    public void freshToken(@Nullable Activity activity, Set<String> scopes, Callback cb) {
        String cached = cachedToken();
        if (cached != null) {
            cb.onToken(cached);
            return;
        }
        authorize(activity, scopes, cb);
    }

    @Override
    public void authorize(@Nullable Activity activity, Set<String> scopes, Callback cb) {
        List<com.google.android.gms.common.api.Scope> scopeList = new ArrayList<>(scopes.size());
        for (String s : scopes) scopeList.add(new com.google.android.gms.common.api.Scope(s));
        AuthorizationRequest req = AuthorizationRequest.builder()
                .setRequestedScopes(scopeList)
                .build();

        Context ctx = activity != null ? activity : appContext;
        Identity.getAuthorizationClient(ctx)
                .authorize(req)
                .addOnSuccessListener(result -> handleAuthSuccess(result, cb))
                .addOnFailureListener(e -> cb.onError(e.getMessage(), null));
    }

    @Override
    public void onAuthorizationResult(@Nullable Activity activity, @Nullable Intent data,
                                      Callback cb) {
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

    @Override
    public void freshIdToken(@Nullable Activity activity, Callback cb) {
        if (webClientId == null) {
            cb.onError("id_token_not_configured", null);
            return;
        }
        if (activity == null) {
            cb.onError("activity required for id token", null);
            return;
        }
        BeginSignInRequest req = BeginSignInRequest.builder()
                .setGoogleIdTokenRequestOptions(
                        BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                                .setSupported(true)
                                .setServerClientId(webClientId)
                                // First-time + Firebase-bootstrap sign-in: show every Google
                                // account on the device. Once the user picks once, Play Services
                                // remembers the grant — One Tap returns silently on subsequent
                                // calls without needing this flipped to true.
                                .setFilterByAuthorizedAccounts(false)
                                .build())
                .setAutoSelectEnabled(true)
                .build();
        Identity.getSignInClient(activity).beginSignIn(req)
                .addOnSuccessListener(result -> cb.onError(ERROR_NEEDS_UI,
                        new PendingUi(result.getPendingIntent().getIntentSender())))
                .addOnFailureListener(e -> cb.onError(e.getMessage(), null));
    }

    @Override
    public void onSignInResult(@Nullable Activity activity, @Nullable Intent data, Callback cb) {
        if (activity == null) {
            cb.onError("activity required to finish sign-in", null);
            return;
        }
        try {
            SignInCredential credential = Identity.getSignInClient(activity)
                    .getSignInCredentialFromIntent(data);
            String idToken = credential.getGoogleIdToken();
            if (idToken == null || idToken.isEmpty()) {
                cb.onError("no id token in result", null);
                return;
            }
            // SignInCredential.getId() is the user's email when the credential came
            // from a Google account — same source of truth as the access-token path
            // so the shared "google" store stays consistent across both flows.
            String email = credential.getId();
            if (email != null && !email.isEmpty()) {
                store.putString(KEY_SIGNED_IN, "1");
                store.putString(KEY_ACCOUNT_EMAIL, email);
            }
            cb.onToken(idToken);
        } catch (Exception e) {
            cb.onError(e.getMessage(), null);
        }
    }

    private void handleAuthSuccess(AuthorizationResult result, Callback cb) {
        if (result.hasResolution()) {
            cb.onError(ERROR_NEEDS_UI,
                    new PendingUi(result.getPendingIntent().getIntentSender()));
            return;
        }
        String token = result.getAccessToken();
        if (token == null || token.isEmpty()) {
            cb.onError("no access token in result", null);
            return;
        }
        // AuthorizationResult does not expose expiry; tokens are 1h. Cache 55min to be safe.
        long expiresAt = System.currentTimeMillis() + 55L * 60_000L;
        store.putString(KEY_SIGNED_IN, "1");
        store.putString(KEY_ACCESS_TOKEN, token);
        store.putString(KEY_TOKEN_EXPIRES_AT, String.valueOf(expiresAt));

        if (result.toGoogleSignInAccount() != null
                && result.toGoogleSignInAccount().getEmail() != null) {
            store.putString(KEY_ACCOUNT_EMAIL, result.toGoogleSignInAccount().getEmail());
        } else if (store.getString(KEY_ACCOUNT_EMAIL, "").isEmpty()) {
            // AuthorizationResult won't surface the account when sign-in wasn't part of
            // this request; fall back to userinfo for the email scope we did grant.
            fetchAndStoreEmail(token);
        }
        cb.onToken(token);
    }

    @Override
    public void fetchAndStoreEmailIfMissing() {
        if (!store.getString(KEY_ACCOUNT_EMAIL, "").isEmpty()) return;
        String token = cachedToken();
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
                    store.putString(KEY_ACCOUNT_EMAIL, email);
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    @Override
    public void setAccountEmail(String email) {
        store.putString(KEY_ACCOUNT_EMAIL, email == null ? "" : email);
    }
}
