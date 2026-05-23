package com.prince.turtlekeyboard.integration.drive;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Drive REST helpers for files this app created (drive.file scope). All methods are
 * network-blocking; a 401 means the access token expired and the caller should refresh
 * via {@link com.prince.kbd.core.GoogleAuth} and retry once.
 */
public final class DriveFilesClient {

    private static final String UPLOAD_URL =
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart";
    private static final String FILES_URL = "https://www.googleapis.com/drive/v3/files";
    private static final String BOUNDARY = "turtleboundary7e9c4f3a";
    private static final String CRLF = "\r\n";

    private DriveFilesClient() {}

    /** Uploads {@code bytes} as a Drive file owned by the user. Returns the new file id. */
    public static String uploadImage(String accessToken, String name, String mimeType,
                                     byte[] bytes) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(UPLOAD_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=" + BOUNDARY);
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);

        try {
            JSONObject meta = new JSONObject();
            meta.put("name", name);
            meta.put("mimeType", mimeType);

            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                writeUtf8(out, "--" + BOUNDARY + CRLF);
                writeUtf8(out, "Content-Type: application/json; charset=UTF-8" + CRLF + CRLF);
                writeUtf8(out, meta.toString());
                writeUtf8(out, CRLF + "--" + BOUNDARY + CRLF);
                writeUtf8(out, "Content-Type: " + mimeType + CRLF + CRLF);
                out.write(bytes);
                writeUtf8(out, CRLF + "--" + BOUNDARY + "--" + CRLF);
                out.flush();
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readBody(conn.getErrorStream());
                throw new IOException("drive upload failed: HTTP " + code
                        + (err.isEmpty() ? "" : " — " + err));
            }
            String body = readBody(conn.getInputStream());
            try {
                return new JSONObject(body).getString("id");
            } catch (JSONException e) {
                throw new IOException("malformed drive response: " + body, e);
            }
        } catch (JSONException e) {
            throw new IOException("could not build upload metadata", e);
        } finally {
            conn.disconnect();
        }
    }

    /** Lists files this app has created. Returns at most 100 entries (no pagination). */
    public static List<FileEntry> listAppFiles(String accessToken) throws IOException {
        String q = URLEncoder.encode("trashed=false", "UTF-8");
        String fields = URLEncoder.encode("files(id,name,mimeType,createdTime)", "UTF-8");
        String url = FILES_URL + "?q=" + q + "&fields=" + fields + "&pageSize=100";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("drive list failed: HTTP " + code);
            }
            String body = readBody(conn.getInputStream());
            JSONObject root = new JSONObject(body);
            JSONArray files = root.optJSONArray("files");
            List<FileEntry> out = new ArrayList<>();
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    out.add(new FileEntry(
                            f.optString("id"),
                            f.optString("name"),
                            f.optString("mimeType"),
                            f.optString("createdTime")));
                }
            }
            return out;
        } catch (JSONException e) {
            throw new IOException("malformed drive list response", e);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Grants {@code anyone with the link} reader permission on a file the app created,
     * enabling fetch via {@link #publicImageUrl} without per-viewer auth. Idempotent.
     */
    public static void makePublicReadable(String accessToken, String fileId) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(FILES_URL + "/" + fileId + "/permissions").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        try {
            JSONObject body = new JSONObject();
            body.put("role", "reader");
            body.put("type", "anyone");
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                writeUtf8(out, body.toString());
                out.flush();
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readBody(conn.getErrorStream());
                throw new IOException("drive permission failed: HTTP " + code
                        + (err.isEmpty() ? "" : " — " + err));
            }
        } catch (JSONException e) {
            throw new IOException("could not build permission body", e);
        } finally {
            conn.disconnect();
        }
    }

    /** URL that serves the raw image bytes of a publicly-readable Drive file. */
    public static String publicImageUrl(String fileId) {
        return "https://drive.google.com/thumbnail?id=" + fileId + "&sz=w1200";
    }

    /** Deletes a Drive file by id. {@code 404} is treated as success. */
    public static void deleteFile(String accessToken, String fileId) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(FILES_URL + "/" + fileId).openConnection();
        conn.setRequestMethod("DELETE");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        try {
            int code = conn.getResponseCode();
            if (code != 204 && code != 404) {
                throw new IOException("drive delete failed: HTTP " + code);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void writeUtf8(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String readBody(InputStream in) {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    public static final class FileEntry {
        public final String id;
        public final String name;
        public final String mimeType;
        public final String createdTime;
        public FileEntry(String id, String name, String mimeType, String createdTime) {
            this.id = id;
            this.name = name;
            this.mimeType = mimeType;
            this.createdTime = createdTime;
        }
    }
}
