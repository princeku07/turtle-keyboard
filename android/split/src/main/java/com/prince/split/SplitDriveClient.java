package com.prince.split;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Minimal Drive v3 client — the {@code permissions} subset we need to share the owner's
 * "Turtle Splits" sheet via an anyone-with-link writer permission, and revoke it later.
 *
 * <p>Auth model mirrors {@link SplitSheetsClient}: caller passes a fresh access token;
 * 401/403 surfaces as {@link SplitSheetsClient.UnauthorizedException}.
 */
final class SplitDriveClient {

    private static final String BASE = "https://www.googleapis.com/drive/v3/files/";
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;

    private SplitDriveClient() {}

    /**
     * Adds an anyone-with-link writer permission. Returns the new {@code permissionId}
     * so the caller can revoke it later via {@link #revokePermission}. Does not send
     * notification emails (there's nobody to email).
     */
    static String grantAnyoneWriter(String accessToken, String fileId) throws IOException {
        String url = BASE + fileId + "/permissions?sendNotificationEmail=false";
        JSONObject body;
        try {
            body = new JSONObject()
                    .put("role", "writer")
                    .put("type", "anyone")
                    .put("allowFileDiscovery", false);
        } catch (Exception e) {
            throw new IOException(e);
        }
        JSONObject resp = postJson(url, accessToken, body.toString());
        String id = resp.optString("id", "");
        if (id.isEmpty()) throw new IOException("grantAnyoneWriter: empty permissionId");
        return id;
    }

    /** Removes a specific permission by ID. Idempotent — 404s are swallowed. */
    static void revokePermission(String accessToken, String fileId, String permissionId)
            throws IOException {
        String url = BASE + fileId + "/permissions/" + encode(permissionId);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("DELETE");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                throw new SplitSheetsClient.UnauthorizedException(
                        "HTTP " + code + " revokePermission");
            }
            if (code == 404) return; // already gone — fine
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + readStream(conn.getErrorStream()));
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static JSONObject postJson(String url, String accessToken, String body)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                throw new SplitSheetsClient.UnauthorizedException("HTTP " + code + " " + url);
            }
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + readStream(conn.getErrorStream()));
            }
            String resp = readStream(conn.getInputStream());
            return resp.isEmpty() ? new JSONObject() : new JSONObject(resp);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(@Nullable InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
