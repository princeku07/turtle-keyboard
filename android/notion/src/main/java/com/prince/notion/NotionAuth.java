package com.prince.notion;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import com.prince.split.SplitStore;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Notion OAuth 2.0 helper. Two phases:
 *
 * <ol>
 *   <li>{@link #authorizeIntent} — start the user's browser at Notion's consent screen.</li>
 *   <li>{@link #exchangeCode} — once Notion redirects back to {@code OAUTH_REDIRECT_URI}
 *       with a {@code ?code=}, swap it for a long-lived access token + workspace info.</li>
 * </ol>
 *
 * <p>The token persists in {@link SplitStore} keyed by {@link NotionKeys#ACCESS_TOKEN}.
 * Notion tokens don't currently expire — no refresh flow needed today.
 *
 * <p><b>Security note:</b> Notion's token endpoint requires {@code client_secret} in the
 * Basic-auth header, so the secret rides along in BuildConfig. Acceptable for personal /
 * dev use; before any wider release, move the exchange to a tiny token-exchange Worker.
 */
public final class NotionAuth {

    private static final String TAG = "NotionAuth";

    private static final String AUTHORIZE_URL = "https://api.notion.com/v1/oauth/authorize";
    private static final String TOKEN_URL     = "https://api.notion.com/v1/oauth/token";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    public interface ExchangeCallback {
        /** @param accessToken Notion bearer token (long-lived).
         *  @param workspaceName Human-readable workspace label, may be null. */
        void onSuccess(String accessToken, @Nullable String workspaceName);
        void onError(String reason);
    }

    private final SplitStore store;

    public NotionAuth(SplitStore store) {
        this.store = store;
    }

    public boolean isSignedIn() {
        String t = store.getString(NotionKeys.ACCESS_TOKEN, "");
        return t != null && !t.isEmpty();
    }

    @Nullable public String accessToken() {
        String t = store.getString(NotionKeys.ACCESS_TOKEN, "");
        return t == null || t.isEmpty() ? null : t;
    }

    public void clear() {
        store.putString(NotionKeys.ACCESS_TOKEN, "");
        store.putString(NotionKeys.WORKSPACE_NAME, "");
        store.putString(NotionKeys.DEFAULT_PARENT, "");
        store.putString(NotionKeys.DEFAULT_PARENT_T, "");
    }

    /** Browser intent the connect Activity launches to start OAuth. */
    public Intent authorizeIntent() {
        Uri url = Uri.parse(AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("client_id", BuildConfig.OAUTH_CLIENT_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("owner", "user")
                .appendQueryParameter("redirect_uri", BuildConfig.OAUTH_REDIRECT_URI)
                .build();
        Intent i = new Intent(Intent.ACTION_VIEW, url);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** Phase 2: turn the redirect's {@code ?code=} into a stored access token. */
    public void exchangeCode(Context appContext, String code, ExchangeCallback cb) {
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String basic = Base64.encodeToString(
                        (BuildConfig.OAUTH_CLIENT_ID + ":" + BuildConfig.OAUTH_CLIENT_SECRET)
                                .getBytes(StandardCharsets.UTF_8),
                        Base64.NO_WRAP);

                JSONObject body = new JSONObject()
                        .put("grant_type", "authorization_code")
                        .put("code", code)
                        .put("redirect_uri", BuildConfig.OAUTH_REDIRECT_URI);

                conn = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Basic " + basic);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(15_000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code2 = conn.getResponseCode();
                String response = readAll(code2 < 400 ? conn.getInputStream() : conn.getErrorStream());
                if (code2 >= 400) { cb.onError("notion_token_http_" + code2 + ": " + response); return; }

                JSONObject json = new JSONObject(response);
                String token = json.optString("access_token");
                String workspaceName = json.optString("workspace_name", null);
                if (token == null || token.isEmpty()) { cb.onError("no_access_token"); return; }

                store.putString(NotionKeys.ACCESS_TOKEN, token);
                if (workspaceName != null) store.putString(NotionKeys.WORKSPACE_NAME, workspaceName);
                store.putInt(NotionKeys.ENABLED, 1);
                cb.onSuccess(token, workspaceName);
            } catch (Exception e) {
                Log.w(TAG, "token exchange failed", e);
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String readAll(java.io.InputStream is) throws java.io.IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
