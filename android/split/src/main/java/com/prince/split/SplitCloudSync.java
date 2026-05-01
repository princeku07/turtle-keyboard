package com.prince.split;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local-first cloud mirror for {@link SplitHistory}. The on-device store remains the
 * source of truth (the panel reads/writes it synchronously); this class fires a fire-and-
 * forget POST to a Google Apps Script web app after each save / clear so the rows show
 * up in a Google Sheet too.
 *
 * <p>Endpoint + token are read from the {@link SplitStore} (keys in {@link SplitKeys}).
 * If either is missing the sync is a no-op — the SDK still works fully offline.
 */
public final class SplitCloudSync {

    /** Result delivered to {@link SyncCallback#onComplete} on the main thread. */
    public interface SyncCallback {
        /** @param changed true if local history was modified by the merge */
        void onComplete(boolean changed);
    }

    /** Default Apps Script web app — overridable via {@link SplitKeys#CLOUD_ENDPOINT}. */
    private static final String DEFAULT_ENDPOINT =
            "https://script.google.com/macros/s/AKfycbzlXzBkPxb0MRgBnBMlvrTux7mPZobVEqImjgmiCEWZe2yE2KZXE0BqPAsik59lc5qE/exec";

    /** Default shared token — overridable via {@link SplitKeys#CLOUD_TOKEN}.
     *  Empty string means the deployed script does not enforce a token. */
    private static final String DEFAULT_TOKEN = "agwgwour9ww5wjls533";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 4000;

    private SplitCloudSync() {}

    static void postSave(SplitStore store, double amount, int people, long timestampMs) {
        String endpoint = endpoint(store);
        if (endpoint.isEmpty()) return;
        String token = token(store);
        String deviceId = ensureDeviceId(store);
        String body = "{"
                + "\"action\":\"save\","
                + "\"token\":" + jsonString(token) + ","
                + "\"deviceId\":" + jsonString(deviceId) + ","
                + "\"amount\":" + amount + ","
                + "\"people\":" + people + ","
                + "\"timestampMs\":" + timestampMs
                + "}";
        post(endpoint, body);
    }

    static void postClear(SplitStore store) {
        String endpoint = endpoint(store);
        if (endpoint.isEmpty()) return;
        String token = token(store);
        String deviceId = ensureDeviceId(store);
        String body = "{"
                + "\"action\":\"clear\","
                + "\"token\":" + jsonString(token) + ","
                + "\"deviceId\":" + jsonString(deviceId)
                + "}";
        post(endpoint, body);
    }

    /**
     * Pulls all rows from the sheet, merges anything new into the local history (dedupe by
     * {@code (timestampMs, amount, people)}), and dispatches {@code cb} on the main thread.
     * Safe to call frequently — silent no-op when the endpoint isn't configured or the
     * network call fails.
     */
    public static void syncFromCloud(final SplitStore store, @Nullable final SyncCallback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        final String endpoint = endpoint(store);
        if (endpoint.isEmpty()) {
            deliver(main, cb, false);
            return;
        }
        final String token = token(store);
        EXEC.execute(new Runnable() {
            @Override public void run() {
                List<RemoteRow> rows = fetchRows(endpoint, token);
                if (rows == null) {
                    deliver(main, cb, false);
                    return;
                }
                boolean changed = mergeIntoLocal(store, rows);
                deliver(main, cb, changed);
            }
        });
    }

    @Nullable
    private static List<RemoteRow> fetchRows(String endpoint, String token) {
        HttpURLConnection conn = null;
        try {
            String url = endpoint + "?action=list&token="
                    + URLEncoder.encode(token, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject obj = new JSONObject(sb.toString());
            if (!obj.optBoolean("ok")) return null;
            JSONArray arr = obj.optJSONArray("rows");
            if (arr == null) return Collections.emptyList();
            List<RemoteRow> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                out.add(new RemoteRow(
                        e.optDouble("amount", 0),
                        e.optInt("people", 0),
                        e.optLong("timestampMs", 0)));
            }
            return out;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean mergeIntoLocal(SplitStore store, List<RemoteRow> remote) {
        String existing = store.getString(SplitKeys.HISTORY, "");
        Set<String> seen = new HashSet<>();
        List<RemoteRow> merged = new ArrayList<>();
        if (!existing.isEmpty()) {
            for (String line : existing.split("\n")) {
                String[] parts = line.split("\\|");
                if (parts.length != 3) continue;
                try {
                    RemoteRow row = new RemoteRow(
                            Double.parseDouble(parts[0]),
                            Integer.parseInt(parts[1]),
                            Long.parseLong(parts[2]));
                    if (seen.add(row.dedupeKey())) merged.add(row);
                } catch (NumberFormatException ignored) {}
            }
        }
        boolean changed = false;
        for (RemoteRow r : remote) {
            if (seen.add(r.dedupeKey())) {
                merged.add(r);
                changed = true;
            }
        }
        if (!changed) return false;
        Collections.sort(merged, new java.util.Comparator<RemoteRow>() {
            @Override public int compare(RemoteRow a, RemoteRow b) {
                return Long.compare(b.timestampMs, a.timestampMs);
            }
        });
        if (merged.size() > SplitHistory.MAX) {
            merged = merged.subList(0, SplitHistory.MAX);
        }
        StringBuilder out = new StringBuilder();
        for (RemoteRow row : merged) {
            if (out.length() > 0) out.append('\n');
            out.append(row.amount).append('|').append(row.people).append('|').append(row.timestampMs);
        }
        store.putString(SplitKeys.HISTORY, out.toString());
        return true;
    }

    private static void deliver(Handler main, @Nullable final SyncCallback cb, final boolean changed) {
        if (cb == null) return;
        main.post(new Runnable() {
            @Override public void run() { cb.onComplete(changed); }
        });
    }

    private static final class RemoteRow {
        final double amount;
        final int people;
        final long timestampMs;
        RemoteRow(double amount, int people, long timestampMs) {
            this.amount = amount;
            this.people = people;
            this.timestampMs = timestampMs;
        }
        String dedupeKey() { return timestampMs + "|" + amount + "|" + people; }
    }

    private static String endpoint(SplitStore store) {
        String override = store.getString(SplitKeys.CLOUD_ENDPOINT, "");
        return override.isEmpty() ? DEFAULT_ENDPOINT : override;
    }

    private static String token(SplitStore store) {
        String override = store.getString(SplitKeys.CLOUD_TOKEN, "");
        return override.isEmpty() ? DEFAULT_TOKEN : override;
    }

    private static String ensureDeviceId(SplitStore store) {
        String id = store.getString(SplitKeys.DEVICE_ID, "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            store.putString(SplitKeys.DEVICE_ID, id);
        }
        return id;
    }

    private static void post(final String endpoint, final String body) {
        EXEC.execute(new Runnable() {
            @Override public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(endpoint);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    conn.getResponseCode(); // drain — we don't surface failures, this is best-effort
                } catch (Exception ignored) {
                    // Local copy is the source of truth; cloud failures are silent by design.
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });
    }

    private static String jsonString(@Nullable String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
