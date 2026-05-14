package com.prince.turtlekeyboard.integration.poll;

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
import java.util.List;

/**
 * HTTP client for the {@code turtle-worker} /poll endpoints. Mirrors
 * {@code SplitSheetsClient}'s shape — static methods, network-blocking, throws
 * {@link IOException} on non-2xx with the Worker's error code embedded in the message.
 *
 * <p>Configure {@link #BASE_URL} for your environment:
 * <ul>
 *   <li>Local {@code wrangler dev} from Android emulator: {@code http://10.0.2.2:8787}</li>
 *   <li>Local {@code wrangler dev} from real device on same WiFi:
 *       {@code http://<dev-machine-LAN-IP>:8787} (host must appear in
 *       {@code res/xml/network_security_config.xml})</li>
 *   <li>Production: {@code https://turtle-worker.<acct>.workers.dev}</li>
 * </ul>
 */
public final class PollClient {

    /** Worker base URL — points at {@link OverlayUrls#WORKER_BASE_URL} so all overlay
     *  clients share the same deploy target. For local Worker iteration, swap the
     *  constant in {@link OverlayUrls} (one edit covers PollClient + WyrClient + any
     *  future overlay client). */
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

    public static final class Poll {
        public final String id;
        public final String question;
        public final List<Option> options;
        public final long createdAt;
        public Poll(String id, String question, List<Option> options, long createdAt) {
            this.id = id;
            this.question = question;
            this.options = options;
            this.createdAt = createdAt;
        }
    }

    public static final class Option {
        public final String label;
        public final int votes;
        public Option(String label, int votes) {
            this.label = label;
            this.votes = votes;
        }
    }

    private PollClient() {}

    /** {@code POST /poll} — returns the artifact id and the shareable App Link URL. */
    public static CreateResult createPoll(String question, List<String> options) throws IOException {
        try {
            JSONArray opts = new JSONArray();
            for (String o : options) opts.put(o);
            JSONObject body = new JSONObject()
                    .put("question", question)
                    .put("options", opts);
            JSONObject resp = postJson(BASE_URL + "/poll", null, body);
            return new CreateResult(resp.getString("id"), resp.getString("url"));
        } catch (JSONException e) {
            throw new IOException("malformed worker response", e);
        }
    }

    /** {@code GET /poll/<id>} — fetches the public poll shape (vote counts, options).
     *  {@code voters} is stripped server-side; we never see it. */
    public static Poll readPoll(String id) throws IOException {
        try {
            JSONObject resp = getJson(BASE_URL + "/poll/" + id, null);
            String question = resp.getString("question");
            long createdAt = resp.optLong("createdAt", 0L);
            JSONArray optsArr = resp.getJSONArray("options");
            List<Option> options = new ArrayList<>(optsArr.length());
            for (int i = 0; i < optsArr.length(); i++) {
                JSONObject o = optsArr.getJSONObject(i);
                options.add(new Option(o.getString("label"), o.optInt("votes", 0)));
            }
            return new Poll(resp.getString("id"), question, options, createdAt);
        } catch (JSONException e) {
            throw new IOException("malformed worker response", e);
        }
    }

    /** {@code POST /poll/<id>/vote} — increments the option's count. Worker dedups per
     *  {@code X-Turtle-Device} header; double-vote returns HTTP 409 with
     *  {@code error: "already_voted"}. */
    public static void vote(String pollId, int optionIndex, String deviceId) throws IOException {
        try {
            JSONObject body = new JSONObject().put("optionIndex", optionIndex);
            postJson(BASE_URL + "/poll/" + pollId + "/vote", deviceId, body);
        } catch (JSONException e) {
            throw new IOException("malformed request body", e);
        }
    }

    // -- HTTP helpers --------------------------------------------------------

    private static JSONObject postJson(String url, String deviceIdOrNull, JSONObject body) throws IOException {
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

    /** Reads the response body. On 2xx returns the parsed JSON; on non-2xx throws an
     *  {@link IOException} whose message embeds the Worker's {@code error: "<code>"}
     *  string so callers can switch on it (e.g. {@code already_voted}). */
    private static JSONObject readJsonOrThrow(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String raw = readAll(stream);
        if (code < 200 || code >= 300) {
            String workerCode = "";
            try {
                workerCode = new JSONObject(raw).optString("error", "");
            } catch (JSONException ignored) {
                // Body wasn't JSON — fall back to bare HTTP code.
            }
            throw new IOException("worker " + code + (workerCode.isEmpty() ? "" : ": " + workerCode));
        }
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            throw new IOException("worker returned non-JSON: " + raw.substring(0, Math.min(raw.length(), 120)), e);
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
