package com.prince.notion;

import android.util.Log;

import com.prince.kbd.core.GeminiService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Turns a free-form user prompt into a structured Notion page via the LLM. Asks for
 * JSON {@code {title, blocks: [{type, text, ...}]}}. Falls back to a single paragraph
 * block when parsing fails so a page is always created.
 */
public final class NotionLlmBridge {

    private static final String TAG = "NotionLlmBridge";

    public interface Callback {
        void onStructured(String title, JSONArray blocks);
        void onError(String reason);
    }

    private static final String SYSTEM = ""
            + "You convert a user message into a Notion page. Reply with raw JSON only, no markdown fences, "
            + "no commentary. Schema:\n"
            + "{\n"
            + "  \"title\": \"<short title, max 80 chars>\",\n"
            + "  \"blocks\": [\n"
            + "    {\"type\": \"heading_2\", \"text\": \"...\"},\n"
            + "    {\"type\": \"paragraph\", \"text\": \"...\"},\n"
            + "    {\"type\": \"to_do\", \"text\": \"...\"}\n"
            + "  ]\n"
            + "}\n"
            + "Use heading_2 for sections, paragraph for prose, to_do for actionable tasks. Keep it concise.";

    public static void structure(String userPrompt, GeminiService ai, Callback cb) {
        ai.text(SYSTEM, userPrompt, new GeminiService.TextCallback() {
            @Override public void onText(String text) {
                Parsed p = parse(text);
                if (p != null) { cb.onStructured(p.title, p.blocks); return; }
                String title = userPrompt == null ? "Untitled" :
                        userPrompt.length() > 80 ? userPrompt.substring(0, 80) : userPrompt;
                JSONArray blocks = new JSONArray();
                blocks.put(buildBlock("paragraph", userPrompt == null ? "" : userPrompt, false));
                cb.onStructured(title, blocks);
            }
            @Override public void onError(String reason) { cb.onError(reason); }
        });
    }

    public static JSONObject buildBlock(String type, String text, boolean checked) {
        try {
            JSONObject block = new JSONObject()
                    .put("object", "block")
                    .put("type", type);
            JSONObject inner = new JSONObject()
                    .put("rich_text", new JSONArray().put(NotionClient.textObject(text)));
            if ("to_do".equals(type)) inner.put("checked", checked);
            block.put(type, inner);
            return block;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- parsing ----------------------------------------------------------

    private static final class Parsed {
        final String title;
        final JSONArray blocks;
        Parsed(String t, JSONArray b) { title = t; blocks = b; }
    }

    private static Parsed parse(String llmOutput) {
        if (llmOutput == null) return null;
        // Strip reasoning [THINK]/<think> traces before brace-scanning.
        String cleaned = stripThinkBlocks(llmOutput);
        // Prefer the LAST fenced block — final answer comes after any in-trace fences.
        String body = extractLastFencedBlock(cleaned);
        if (body == null) body = stripFences(cleaned);
        body = body.trim();

        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JSONObject obj = new JSONObject(body.substring(start, end + 1));
            String title = obj.optString("title", "Untitled");
            JSONArray src = obj.optJSONArray("blocks");
            JSONArray dst = new JSONArray();
            if (src != null) {
                for (int i = 0; i < src.length(); i++) {
                    JSONObject b = src.optJSONObject(i);
                    if (b == null) continue;
                    String type = b.optString("type", "paragraph");
                    String text = b.optString("text", "");
                    boolean checked = b.optBoolean("checked", false);
                    if (text.isEmpty()) continue;
                    if (!isSupportedType(type)) type = "paragraph";
                    dst.put(buildBlock(type, text, checked));
                }
            }
            if (dst.length() == 0) return null;
            return new Parsed(title, dst);
        } catch (Exception e) {
            Log.d(TAG, "parse failed: " + e.getMessage());
            return null;
        }
    }

    private static String stripFences(String s) {
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int closing = s.lastIndexOf("```");
            if (firstNl > 0 && closing > firstNl) return s.substring(firstNl + 1, closing);
        }
        return s;
    }

    /** Strip {@code [THINK]…[/THINK]} and {@code <think>…</think>} reasoning blocks. */
    private static String stripThinkBlocks(String s) {
        if (s == null) return null;
        String out = s;
        out = out.replaceAll("(?is)\\[think\\].*?\\[/think\\]", "");
        out = out.replaceAll("(?is)<think>.*?</think>", "");
        return out;
    }

    /** @return body of the last triple-backtick fenced block, or null if none. */
    private static String extractLastFencedBlock(String s) {
        if (s == null) return null;
        int closing = s.lastIndexOf("```");
        if (closing < 0) return null;
        int opening = s.lastIndexOf("```", closing - 1);
        if (opening < 0 || opening >= closing) return null;
        // Skip the optional info string ("json", …) after the opener.
        int firstNl = s.indexOf('\n', opening + 3);
        int bodyStart = (firstNl > 0 && firstNl < closing) ? firstNl + 1 : opening + 3;
        return s.substring(bodyStart, closing);
    }

    private static boolean isSupportedType(String t) {
        return "heading_2".equals(t) || "paragraph".equals(t) || "to_do".equals(t);
    }

    private NotionLlmBridge() {}
}
