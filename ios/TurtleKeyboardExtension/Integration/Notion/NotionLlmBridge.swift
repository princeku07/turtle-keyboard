import Foundation

/// Turns a free-form user prompt into a structured Notion page via the
/// keyboard's LLM. Mirrors Android's `NotionLlmBridge`.
///
/// Asks the model to return JSON of shape
/// `{title, blocks: [{type, text, checked?}]}` where `type` is one of
/// `heading_2`, `paragraph`, `to_do`. Robust against the LLM wrapping the
/// JSON in markdown fences or trailing prose. If parsing fails we fall back
/// to a single paragraph block containing the raw user prompt.
enum NotionLlmBridge {

    private static let systemPrompt = """
    You convert a user message into a Notion page. Reply with raw JSON only, no markdown fences, no commentary. Schema:
    {
      "title": "<short title, max 80 chars>",
      "blocks": [
        {"type": "heading_2", "text": "..."},
        {"type": "paragraph", "text": "..."},
        {"type": "to_do", "text": "..."}
      ]
    }
    Use heading_2 for sections, paragraph for prose, to_do for actionable tasks. Keep it concise.
    """

    /// Ask the LLM to structure `userPrompt`; calls back with title +
    /// blocks ready for `NotionClient.createPage`.
    static func structure(
        userPrompt: String,
        llm: LlmService,
        completion: @escaping (Result<(title: String, blocks: [[String: Any]]), Error>) -> Void
    ) {
        let prompt = systemPrompt + "\n\nUser message:\n" + userPrompt
        llm.complete(
            prompt: prompt,
            onText: { text in
                if let parsed = parse(llmOutput: text) {
                    completion(.success(parsed))
                    return
                }
                // Fallback: single paragraph, title from first 80 chars.
                let trimmed = userPrompt.isEmpty ? "Untitled" : userPrompt
                let title = trimmed.count > 80 ? String(trimmed.prefix(80)) : trimmed
                let blocks = [NotionClient.buildBlock(type: "paragraph", text: userPrompt, checked: false)]
                completion(.success((title, blocks)))
            },
            onError: { reason in
                completion(.failure(NSError(
                    domain: "NotionLlmBridge", code: -1,
                    userInfo: [NSLocalizedDescriptionKey: reason])))
            }
        )
    }

    // MARK: - Parsing

    private static func parse(llmOutput: String) -> (title: String, blocks: [[String: Any]])? {
        let cleaned = stripThinkBlocks(llmOutput)
        let body = (extractLastFencedBlock(cleaned) ?? stripFences(cleaned))
            .trimmingCharacters(in: .whitespacesAndNewlines)

        guard let start = body.firstIndex(of: "{"),
              let end = body.lastIndex(of: "}"),
              start < end
        else { return nil }
        let jsonSlice = String(body[start...end])

        guard let data = jsonSlice.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }

        let title = (obj["title"] as? String) ?? "Untitled"
        var dst: [[String: Any]] = []
        for src in (obj["blocks"] as? [[String: Any]]) ?? [] {
            let type0 = (src["type"] as? String) ?? "paragraph"
            let text = (src["text"] as? String) ?? ""
            let checked = (src["checked"] as? Bool) ?? false
            if text.isEmpty { continue }
            let type = isSupported(type0) ? type0 : "paragraph"
            dst.append(NotionClient.buildBlock(type: type, text: text, checked: checked))
        }
        // If the LLM gave us nothing usable, treat as parse failure so we
        // hit the fallback.
        if dst.isEmpty { return nil }
        return (title, dst)
    }

    /// Remove every `[THINK]…[/THINK]` (or `<think>…</think>`) reasoning
    /// block. Same shape as Android — case-insensitive, both forms.
    private static func stripThinkBlocks(_ s: String) -> String {
        var out = s
        out = out.replacingOccurrences(
            of: #"(?is)\[think\].*?\[/think\]"#,
            with: "",
            options: .regularExpression)
        out = out.replacingOccurrences(
            of: #"(?is)<think>.*?</think>"#,
            with: "",
            options: .regularExpression)
        return out
    }

    private static func stripFences(_ s: String) -> String {
        guard s.hasPrefix("```"),
              let firstNl = s.firstIndex(of: "\n"),
              let closing = s.range(of: "```", options: .backwards),
              closing.lowerBound > firstNl
        else { return s }
        return String(s[s.index(after: firstNl)..<closing.lowerBound])
    }

    /// Find the LAST triple-backtick fenced block in `s` and return its
    /// body (without the fences). Skips any ```language``` info string.
    private static func extractLastFencedBlock(_ s: String) -> String? {
        guard let closing = s.range(of: "```", options: .backwards) else { return nil }
        let beforeClose = s[s.startIndex..<closing.lowerBound]
        guard let opening = beforeClose.range(of: "```", options: .backwards) else { return nil }
        // Skip the optional info string after the opener.
        let afterOpener = s.index(opening.lowerBound, offsetBy: 3)
        let bodyStart: String.Index
        if let nl = s[afterOpener..<closing.lowerBound].firstIndex(of: "\n") {
            bodyStart = s.index(after: nl)
        } else {
            bodyStart = afterOpener
        }
        return String(s[bodyStart..<closing.lowerBound])
    }

    private static func isSupported(_ t: String) -> Bool {
        t == "heading_2" || t == "paragraph" || t == "to_do"
    }
}
