package com.prince.turtlekeyboard.integration.wyr;

import com.prince.turtlekeyboard.overlay.OverlayUrls;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the {@code turtle-worker} /wyr endpoints. Same shape as
 * {@link com.prince.turtlekeyboard.integration.poll.PollClient}: static methods,
 * network-blocking, embeds Worker error codes in the {@link IOException} message.
 *
 * <p>Game shape: each /wyr artifact has up to 5 dilemma questions and a {@code players}
 * map keyed by opaque device id. {@link #submitAnswers} writes the caller's answers in
 * one shot (after they've played through all 5 locally) and returns the post-write
 * state so the sheet can immediately render either RESULTS (partner also done) or
 * WAITING (partner not yet).
 */
public final class WyrClient {

    public static final String BASE_URL = OverlayUrls.WORKER_BASE_URL;

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    public static final class CreateResult {
        public final String id;
        public final String url;
        public CreateResult(String id, String url) {
            this.id = id;
            this.url = url;
        }
    }

    public static final class Wyr {
        public final String id;
        public final long createdAt;
        public final List<Question> questions;
        /** Device id → list of "A"/"B" answers, one per question, in question order. */
        public final Map<String, List<String>> players;
        public Wyr(String id, long createdAt, List<Question> questions,
                   Map<String, List<String>> players) {
            this.id = id;
            this.createdAt = createdAt;
            this.questions = questions;
            this.players = players;
        }
    }

    public static final class Question {
        public final String a;
        public final String b;
        public Question(String a, String b) {
            this.a = a;
            this.b = b;
        }
    }

    private WyrClient() {}

    /** {@code POST /wyr} — questions array → returns artifact id + shareable App Link URL. */
    public static CreateResult create(List<Question> questions) throws IOException {
        try {
            JSONArray qs = new JSONArray();
            for (Question q : questions) {
                qs.put(new JSONObject().put("a", q.a).put("b", q.b));
            }
            JSONObject body = new JSONObject().put("questions", qs);
            JSONObject resp = postJson(BASE_URL + "/wyr", null, body);
            return new CreateResult(resp.getString("id"), resp.getString("url"));
        } catch (JSONException e) {
            throw new IOException("malformed worker response", e);
        }
    }

    /** {@code GET /wyr/<id>} — full state including any submitted answers. The caller
     *  (sheet view) decides how to render based on which players have submitted. */
    public static Wyr read(String id) throws IOException {
        try {
            JSONObject resp = getJson(BASE_URL + "/wyr/" + id, null);
            return parseWyr(resp);
        } catch (JSONException e) {
            throw new IOException("malformed worker response", e);
        }
    }

    /** {@code POST /wyr/<id>/answer} — submit this device's full answer array. Worker
     *  rejects with 409 {@code already_voted}-style error if the device already answered. */
    public static Wyr submitAnswers(String wyrId, List<String> answers, String deviceId)
            throws IOException {
        try {
            JSONArray arr = new JSONArray();
            for (String s : answers) arr.put(s);
            JSONObject body = new JSONObject().put("answers", arr);
            JSONObject resp = postJson(BASE_URL + "/wyr/" + wyrId + "/answer", deviceId, body);
            return parseWyr(resp);
        } catch (JSONException e) {
            throw new IOException("malformed worker response", e);
        }
    }

    private static Wyr parseWyr(JSONObject resp) throws JSONException {
        String id = resp.getString("id");
        long createdAt = resp.optLong("createdAt", 0L);
        JSONArray qArr = resp.getJSONArray("questions");
        List<Question> questions = new ArrayList<>(qArr.length());
        for (int i = 0; i < qArr.length(); i++) {
            JSONObject q = qArr.getJSONObject(i);
            questions.add(new Question(q.getString("a"), q.getString("b")));
        }
        Map<String, List<String>> players = new HashMap<>();
        JSONObject playersObj = resp.optJSONObject("players");
        if (playersObj != null) {
            java.util.Iterator<String> keys = playersObj.keys();
            while (keys.hasNext()) {
                String deviceId = keys.next();
                JSONArray ansArr = playersObj.optJSONArray(deviceId);
                if (ansArr == null) continue;
                List<String> answers = new ArrayList<>(ansArr.length());
                for (int j = 0; j < ansArr.length(); j++) answers.add(ansArr.optString(j, ""));
                players.put(deviceId, answers);
            }
        }
        return new Wyr(id, createdAt, questions, players);
    }

    // -- HTTP helpers (mirror PollClient) -----------------------------------

    private static JSONObject postJson(String url, String deviceIdOrNull, JSONObject body)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            if (deviceIdOrNull != null && !deviceIdOrNull.isEmpty()) {
                conn.setRequestProperty("X-Turtle-Device", deviceIdOrNull);
            }
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            return readJsonOrThrow(conn);
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject getJson(String url, String deviceIdOrNull) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            if (deviceIdOrNull != null && !deviceIdOrNull.isEmpty()) {
                conn.setRequestProperty("X-Turtle-Device", deviceIdOrNull);
            }
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            return readJsonOrThrow(conn);
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject readJsonOrThrow(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String raw = readAll(stream);
        if (code < 200 || code >= 300) {
            String workerCode = "";
            try {
                workerCode = new JSONObject(raw).optString("error", "");
            } catch (JSONException ignored) { /* body wasn't JSON */ }
            throw new IOException("worker " + code + (workerCode.isEmpty() ? "" : ": " + workerCode));
        }
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            throw new IOException("worker returned non-JSON: "
                    + raw.substring(0, Math.min(raw.length(), 120)), e);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}
