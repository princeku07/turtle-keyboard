package com.prince.slack;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.prince.kbd.core.KeyValueStore;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Slack OAuth 2.0 helper. Call {@link #authorizeIntent} to start consent, then
 * {@link #exchangeCode} on the redirect's {@code ?code=}. Requests user-token scopes
 * (not bot scopes) so messages post as the user.
 */
public final class SlackAuth {

    private static final String TAG = "SlackAuth";

    private static final String AUTHORIZE_URL = "https://slack.com/oauth/v2/authorize";
    private static final String TOKEN_URL     = "https://slack.com/api/oauth.v2.access";

    private static final String USER_SCOPES = "chat:write,channels:read,groups:read";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    public interface ExchangeCallback {
        void onSuccess(String accessToken, @Nullable String teamName, @Nullable String teamDomain);
        void onError(String reason);
    }

    private final KeyValueStore store;

    public SlackAuth(KeyValueStore store) {
        this.store = store;
    }

    public boolean isSignedIn() {
        String t = store.getString(SlackKeys.ACCESS_TOKEN, "");
        return t != null && !t.isEmpty();
    }

    @Nullable public String accessToken() {
        String t = store.getString(SlackKeys.ACCESS_TOKEN, "");
        return t == null || t.isEmpty() ? null : t;
    }

    public void clear() {
        store.putString(SlackKeys.ACCESS_TOKEN, "");
        store.putString(SlackKeys.TEAM_NAME, "");
        store.putString(SlackKeys.TEAM_DOMAIN, "");
        store.putString(SlackKeys.DEFAULT_CHANNEL, "");
        store.putString(SlackKeys.DEFAULT_CHANNEL_NAME, "");
    }

    public Intent authorizeIntent() {
        Uri url = Uri.parse(AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("client_id", BuildConfig.OAUTH_CLIENT_ID)
                .appendQueryParameter("user_scope", USER_SCOPES)
                .appendQueryParameter("redirect_uri", BuildConfig.OAUTH_REDIRECT_URI)
                .build();
        Intent i = new Intent(Intent.ACTION_VIEW, url);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    public void exchangeCode(Context appContext, String code, ExchangeCallback cb) {
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                // oauth.v2.access expects form-encoded body with client_id + secret (not Basic auth).
                String body = "code=" + URLEncoder.encode(code, "UTF-8")
                        + "&client_id=" + URLEncoder.encode(BuildConfig.OAUTH_CLIENT_ID, "UTF-8")
                        + "&client_secret=" + URLEncoder.encode(BuildConfig.OAUTH_CLIENT_SECRET, "UTF-8")
                        + "&redirect_uri=" + URLEncoder.encode(BuildConfig.OAUTH_REDIRECT_URI, "UTF-8");

                conn = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(15_000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int httpCode = conn.getResponseCode();
                String response = readAll(httpCode < 400 ? conn.getInputStream() : conn.getErrorStream());
                if (httpCode >= 400) {
                    cb.onError("slack_token_http_" + httpCode + ": " + response);
                    return;
                }

                // Slack returns 200 on errors too; success requires "ok": true.
                JSONObject json = new JSONObject(response);
                if (!json.optBoolean("ok", false)) {
                    cb.onError("slack_oauth: " + json.optString("error", "unknown"));
                    return;
                }
                JSONObject authedUser = json.optJSONObject("authed_user");
                if (authedUser == null) { cb.onError("no authed_user"); return; }
                String token = authedUser.optString("access_token", null);
                if (token == null || token.isEmpty()) { cb.onError("no_user_access_token"); return; }

                JSONObject team = json.optJSONObject("team");
                String teamName = team == null ? null : team.optString("name", null);
                // team domain isn't returned here; SlackConnectActivity fetches it later.

                store.putString(SlackKeys.ACCESS_TOKEN, token);
                if (teamName != null) store.putString(SlackKeys.TEAM_NAME, teamName);
                store.putInt(SlackKeys.ENABLED, 1);
                cb.onSuccess(token, teamName, null);
            } catch (Exception e) {
                Log.w(TAG, "token exchange failed", e);
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String readAll(InputStream is) throws java.io.IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
