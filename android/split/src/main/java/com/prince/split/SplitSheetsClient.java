package com.prince.split;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thin REST client for the bits of Sheets v4 + Drive v3 the SDK needs.
 *
 * <p>Auth is callers' problem — pass a fresh access token to every method. A 401 response
 * surfaces as {@link UnauthorizedException} so {@link SplitCloudSync} can re-auth and
 * retry without baking refresh logic into this layer.
 */
final class SplitSheetsClient {

    private static final String SHEETS_BASE = "https://sheets.googleapis.com/v4/spreadsheets";
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;

    /** Tab + columns the SDK uses; mirrors the legacy Apps Script schema. */
    static final String TAB = "Splits";
    private static final String[] HEADERS = {
            "timestampIso", "timestampMs", "deviceId", "amount", "people", "perPerson"
    };
    /** Columns A:F — full schema range for reads. */
    private static final String READ_RANGE = TAB + "!A2:F";

    static final class UnauthorizedException extends IOException {
        UnauthorizedException(String msg) { super(msg); }
    }

    static final class Row {
        final long timestampMs;
        final String deviceId;
        final double amount;
        final int people;
        Row(long timestampMs, String deviceId, double amount, int people) {
            this.timestampMs = timestampMs;
            this.deviceId = deviceId;
            this.amount = amount;
            this.people = people;
        }
    }

    private SplitSheetsClient() {}

    /**
     * Creates a new spreadsheet titled {@code "Turtle Splits"} with a {@code Splits} tab
     * and header row. Returns the new spreadsheet ID.
     */
    static String createSpreadsheet(String accessToken) throws IOException {
        JSONObject body = new JSONObject();
        try {
            JSONObject props = new JSONObject().put("title", "Turtle Splits");
            JSONObject sheet = new JSONObject().put("properties",
                    new JSONObject().put("title", TAB));
            body.put("properties", props)
                .put("sheets", new JSONArray().put(sheet));
        } catch (Exception e) {
            throw new IOException(e);
        }
        JSONObject resp = request("POST", SHEETS_BASE, accessToken, body.toString());
        String id = resp.optString("spreadsheetId", "");
        if (id.isEmpty()) throw new IOException("createSpreadsheet: empty spreadsheetId");
        // Stamp headers in row 1.
        appendRows(accessToken, id, Collections.singletonList(asStringRow(HEADERS)));
        return id;
    }

    /** Appends one or more value rows to the {@code Splits} tab. Each inner list is one row. */
    static void appendRows(String accessToken, String spreadsheetId, List<List<Object>> rows)
            throws IOException {
        appendRowsToTab(accessToken, spreadsheetId, TAB, rows);
    }

    private static void appendRowsToTab(String accessToken, String spreadsheetId,
                                        String tab, List<List<Object>> rows) throws IOException {
        if (rows.isEmpty()) return;
        String url = SHEETS_BASE + "/" + spreadsheetId + "/values/"
                + encode(tab + "!A1") + ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS";
        JSONArray values = new JSONArray();
        for (List<Object> row : rows) {
            JSONArray jsonRow = new JSONArray();
            for (Object cell : row) jsonRow.put(cell);
            values.put(jsonRow);
        }
        JSONObject body;
        try {
            body = new JSONObject().put("values", values);
        } catch (Exception e) {
            throw new IOException(e);
        }
        request("POST", url, accessToken, body.toString());
    }

    /** Owner-only: nukes every data row from the {@code Splits} tab while preserving the header. */
    static void deleteAllDataRows(String accessToken, String spreadsheetId) throws IOException {
        String url = SHEETS_BASE + "/" + spreadsheetId + "/values/"
                + encode(TAB + "!A2:F") + ":clear";
        request("POST", url, accessToken, "{}");
    }

    /** Reads every data row from the {@code Splits} tab into typed {@link Row}s. */
    static List<Row> listRows(String accessToken, String spreadsheetId) throws IOException {
        String url = SHEETS_BASE + "/" + spreadsheetId + "/values/" + encode(READ_RANGE);
        JSONObject resp = request("GET", url, accessToken, null);
        JSONArray values = resp.optJSONArray("values");
        if (values == null) return Collections.emptyList();
        List<Row> out = new ArrayList<>(values.length());
        for (int i = 0; i < values.length(); i++) {
            JSONArray r = values.optJSONArray(i);
            if (r == null || r.length() < 5) continue;
            try {
                long ts = parseLong(r.optString(1, "0"));
                String dev = r.optString(2, "");
                double amt = parseDouble(r.optString(3, "0"));
                int people = (int) parseDouble(r.optString(4, "0"));
                out.add(new Row(ts, dev, amt, people));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /**
     * Deletes all data rows whose {@code deviceId} (column C) matches {@code deviceId}.
     * Iterates row indices from the bottom up so deletions don't shift indexes mid-loop.
     */
    static void deleteRowsForDevice(String accessToken, String spreadsheetId, String deviceId)
            throws IOException {
        // Need numeric sheetId for batchUpdate, not the title.
        int sheetId = resolveSheetId(accessToken, spreadsheetId, TAB);
        // Read all current values to find matching indexes.
        String url = SHEETS_BASE + "/" + spreadsheetId + "/values/" + encode(READ_RANGE);
        JSONObject resp = request("GET", url, accessToken, null);
        JSONArray values = resp.optJSONArray("values");
        if (values == null || values.length() == 0) return;

        // 0-based row indexes within the data range; absolute sheet rows are these + 1 (header).
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONArray r = values.optJSONArray(i);
            if (r == null || r.length() < 3) continue;
            if (deviceId.equals(r.optString(2, ""))) matches.add(i + 1); // +1 = absolute row index (0-based with header at 0)
        }
        if (matches.isEmpty()) return;
        Collections.sort(matches, Collections.reverseOrder());

        JSONArray requests = new JSONArray();
        try {
            for (Integer idx : matches) {
                JSONObject delReq = new JSONObject()
                        .put("deleteDimension", new JSONObject()
                                .put("range", new JSONObject()
                                        .put("sheetId", sheetId)
                                        .put("dimension", "ROWS")
                                        .put("startIndex", idx)
                                        .put("endIndex", idx + 1)));
                requests.put(delReq);
            }
            JSONObject body = new JSONObject().put("requests", requests);
            request("POST", SHEETS_BASE + "/" + spreadsheetId + ":batchUpdate",
                    accessToken, body.toString());
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static int resolveSheetId(String accessToken, String spreadsheetId, String tabName)
            throws IOException {
        String url = SHEETS_BASE + "/" + spreadsheetId + "?fields=sheets.properties";
        JSONObject resp = request("GET", url, accessToken, null);
        JSONArray sheets = resp.optJSONArray("sheets");
        if (sheets == null) throw new IOException("no sheets in response");
        for (int i = 0; i < sheets.length(); i++) {
            JSONObject props = sheets.optJSONObject(i).optJSONObject("properties");
            if (props != null && tabName.equals(props.optString("title"))) {
                return props.optInt("sheetId", 0);
            }
        }
        throw new IOException("tab '" + tabName + "' not found");
    }

    // -- core HTTP --------------------------------------------------------------

    private static JSONObject request(String method, String url, String accessToken,
                                      @Nullable String jsonBody) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Accept", "application/json");
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream os = conn.getOutputStream();
                os.write(jsonBody.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                throw new UnauthorizedException("HTTP " + code + " from " + url);
            }
            if (code < 200 || code >= 300) {
                String err = readStream(conn.getErrorStream());
                throw new IOException("HTTP " + code + ": " + err);
            }
            String body = readStream(conn.getInputStream());
            return body.isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(@Nullable java.io.InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    // -- helpers ----------------------------------------------------------------

    private static List<Object> asStringRow(String[] cols) {
        List<Object> row = new ArrayList<>(cols.length);
        Collections.addAll(row, cols);
        return row;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static long parseLong(String s) {
        try { return (long) Double.parseDouble(s); } catch (Exception e) { return 0L; }
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
    }
}
