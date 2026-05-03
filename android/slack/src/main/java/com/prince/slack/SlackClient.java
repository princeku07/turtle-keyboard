package com.prince.slack;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thin REST wrapper for the three Slack endpoints this module needs:
 * <ul>
 *   <li>{@code GET conversations.list} — fetch channels for the picker.</li>
 *   <li>{@code GET team.info} — workspace domain so we can build deep links.</li>
 *   <li>{@code POST chat.postMessage} — the dispatch.</li>
 * </ul>
 *
 * <p>All calls run on a single-threaded background executor; callbacks fire off the main
 * thread. Slack's API always returns HTTP 200 — success is signalled by {@code "ok": true}
 * in the JSON body, so each method checks that explicitly.
 */
public final class SlackClient {

    private static final String TAG = "SlackClient";
    private static final String BASE = "https://slack.com/api";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    public interface PostCallback {
        /** {@code permalink} is a deep link to the message; null if Slack didn't return one. */
        void onSuccess(String channelId, String ts, String permalink);
        void onError(String reason);
    }

    public interface ChannelsCallback {
        void onResults(List<Channel> channels);
        void onError(String reason);
    }

    public interface TeamInfoCallback {
        void onSuccess(String teamId, String domain);
        void onError(String reason);
    }

    public static final class Channel {
        public final String id;
        public final String name;
        public final boolean isPrivate;
        public Channel(String id, String name, boolean isPrivate) {
            this.id = id; this.name = name; this.isPrivate = isPrivate;
        }
    }

    private final String accessToken;

    public SlackClient(String accessToken) {
        this.accessToken = accessToken;
    }

    public void teamInfo(TeamInfoCallback cb) {
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = openGet("/team.info");
                int code = conn.getResponseCode();
                String resp = readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
                JSONObject json = new JSONObject(resp);
                if (!json.optBoolean("ok", false)) { cb.onError(json.optString("error", "team_info_failed")); return; }
                JSONObject team = json.optJSONObject("team");
                if (team == null) { cb.onError("no_team"); return; }
                cb.onSuccess(team.optString("id"), team.optString("domain"));
            } catch (Exception e) {
                Log.w(TAG, "team.info failed", e);
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public void listChannels(ChannelsCallback cb) {
        EXEC.execute(() -> {
            try {
                // users.conversations returns only the channels the authenticated user
                // is a member of — naturally avoids the workspace-wide is_member filter
                // we used to do client-side. Pagination is mandatory: workspaces with
                // dozens of channels return the rest under response_metadata.next_cursor.
                List<Channel> out = new ArrayList<>();
                String cursor = "";
                int pages = 0;
                final int MAX_PAGES = 10; // safety net, ~10k channels
                while (pages++ < MAX_PAGES) {
                    String path = "/users.conversations?types=public_channel,private_channel"
                            + "&exclude_archived=true&limit=200";
                    if (!cursor.isEmpty()) {
                        path += "&cursor=" + URLEncoder.encode(cursor, "UTF-8");
                    }
                    HttpURLConnection conn = openGet(path);
                    try {
                        int code = conn.getResponseCode();
                        String resp = readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
                        JSONObject json = new JSONObject(resp);
                        if (!json.optBoolean("ok", false)) {
                            cb.onError("users.conversations: " + json.optString("error", "unknown"));
                            return;
                        }
                        JSONArray arr = json.optJSONArray("channels");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject c = arr.optJSONObject(i);
                                if (c == null) continue;
                                out.add(new Channel(
                                        c.optString("id"),
                                        c.optString("name"),
                                        c.optBoolean("is_private", false)));
                            }
                        }
                        JSONObject meta = json.optJSONObject("response_metadata");
                        cursor = meta == null ? "" : meta.optString("next_cursor", "");
                        if (cursor.isEmpty()) break;
                    } finally {
                        conn.disconnect();
                    }
                }
                cb.onResults(out);
            } catch (Exception e) {
                Log.w(TAG, "users.conversations failed", e);
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    public void postMessage(String channelId, String text, PostCallback cb) {
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject()
                        .put("channel", channelId)
                        .put("text", text == null ? "" : text);
                conn = openPostJson("/chat.postMessage");
                writeBody(conn, body);
                int code = conn.getResponseCode();
                String resp = readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
                JSONObject json = new JSONObject(resp);
                if (!json.optBoolean("ok", false)) {
                    cb.onError("chat.postMessage: " + json.optString("error", "unknown"));
                    return;
                }
                String ts = json.optString("ts");
                String channel = json.optString("channel", channelId);
                // chat.postMessage doesn't return a permalink by default — fetch it so the
                // notification can deep-link. If this fails we still return success with
                // a synthesized URL the receiver can build.
                fetchPermalink(channel, ts, link -> cb.onSuccess(channel, ts, link));
            } catch (Exception e) {
                Log.w(TAG, "chat.postMessage failed", e);
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private interface PermalinkCallback { void onLink(String url); }

    private void fetchPermalink(String channel, String ts, PermalinkCallback cb) {
        HttpURLConnection conn = null;
        try {
            conn = openGet("/chat.getPermalink"
                    + "?channel=" + URLEncoder.encode(channel, "UTF-8")
                    + "&message_ts=" + URLEncoder.encode(ts, "UTF-8"));
            int code = conn.getResponseCode();
            String resp = readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            JSONObject json = new JSONObject(resp);
            String link = json.optBoolean("ok", false) ? json.optString("permalink") : null;
            cb.onLink(link == null || link.isEmpty() ? null : link);
        } catch (Exception e) {
            cb.onLink(null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // --- helpers ----------------------------------------------------------

    private HttpURLConnection openGet(String path) throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        return conn;
    }

    private HttpURLConnection openPostJson(String path) throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        return conn;
    }

    private void writeBody(HttpURLConnection conn, JSONObject body) throws java.io.IOException {
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
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
