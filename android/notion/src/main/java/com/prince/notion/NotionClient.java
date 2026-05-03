package com.prince.notion;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thin REST wrapper for the two Notion endpoints this module needs:
 * <ul>
 *   <li>{@code POST /v1/search} — fetch granted top-level pages so the user can pick a
 *       default parent during connect.</li>
 *   <li>{@code POST /v1/pages} — create a child page under the chosen parent. The body
 *       is built from the structured output of {@link NotionLlmBridge}.</li>
 * </ul>
 *
 * <p>All calls run on a single-threaded background executor so the IME thread is never
 * blocked. Callbacks are invoked off the main thread — call sites that touch UI must
 * post to a Handler themselves.
 */
public final class NotionClient {

    private static final String TAG = "NotionClient";
    private static final String API_VERSION = "2022-06-28";
    private static final String BASE = "https://api.notion.com";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    public interface PageCallback {
        void onSuccess(String pageId, String pageUrl);
        void onError(String reason);
    }

    public interface SearchCallback {
        void onResults(List<Page> pages);
        void onError(String reason);
    }

    public static final class Page {
        public final String id;
        public final String title;
        public Page(String id, String title) { this.id = id; this.title = title; }
    }

    private final String accessToken;

    public NotionClient(String accessToken) {
        this.accessToken = accessToken;
    }

    public void searchPages(SearchCallback cb) {
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject()
                        .put("filter", new JSONObject()
                                .put("value", "page")
                                .put("property", "object"));
                conn = openPost("/v1/search");
                writeBody(conn, body);
                int code = conn.getResponseCode();
                String resp = readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
                if (code >= 400) { cb.onError("search_http_" + code + ": " + resp); return; }

                JSONObject json = new JSONObject(resp);
                JSONArray arr = json.optJSONArray("results");
                java.util.List<Page> out = new java.util.ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject p = arr.getJSONObject(i);
                        String id = p.optString("id");
                        String title = extractTitle(p);
                        if (id != null && !id.isEmpty()) {
                            out.add(new Page(id, title == null || title.isEmpty() ? "(untitled)" : title));
                        }
                    }
                }
                cb.onResults(out);
            } catch (Exception e) {
                Log.w(TAG, "search failed", e);
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public void createPage(String parentPageId, String title, JSONArray blocks, PageCallback cb) {
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject()
                        .put("parent", new JSONObject().put("page_id", parentPageId))
                        .put("properties", new JSONObject()
                                .put("title", new JSONArray()
                                        .put(textObject(title == null ? "Untitled" : title))))
                        .put("children", blocks == null ? new JSONArray() : blocks);
                conn = openPost("/v1/pages");
                writeBody(conn, body);
                int code = conn.getResponseCode();
                String resp = readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
                if (code >= 400) { cb.onError("create_http_" + code + ": " + resp); return; }

                JSONObject json = new JSONObject(resp);
                String id = json.optString("id");
                String url = json.optString("url");
                if (id == null || id.isEmpty()) { cb.onError("no_page_id"); return; }
                cb.onSuccess(id, url);
            } catch (Exception e) {
                Log.w(TAG, "createPage failed", e);
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // --- helpers ----------------------------------------------------------

    private HttpURLConnection openPost(String path) throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Notion-Version", API_VERSION);
        conn.setRequestProperty("Content-Type", "application/json");
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

    private static String extractTitle(JSONObject page) {
        JSONObject props = page.optJSONObject("properties");
        if (props == null) return null;
        // Walk properties to find the one whose type is "title".
        java.util.Iterator<String> keys = props.keys();
        while (keys.hasNext()) {
            JSONObject p = props.optJSONObject(keys.next());
            if (p == null) continue;
            if (!"title".equals(p.optString("type"))) continue;
            JSONArray rich = p.optJSONArray("title");
            if (rich == null || rich.length() == 0) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rich.length(); i++) {
                JSONObject seg = rich.optJSONObject(i);
                if (seg != null) sb.append(seg.optString("plain_text", ""));
            }
            return sb.toString();
        }
        return null;
    }

    /** Build a Notion {@code rich_text} object wrapping plain text. */
    static JSONObject textObject(String text) {
        try {
            return new JSONObject()
                    .put("type", "text")
                    .put("text", new JSONObject().put("content", text == null ? "" : text));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
