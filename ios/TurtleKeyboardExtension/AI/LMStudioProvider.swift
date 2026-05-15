import Foundation

// MARK: - LMStudioProvider
//
// Local OpenAI-compatible inference (LM Studio / llama.cpp / Ollama compatible).
// Used for testing without cloud API keys.
//
// Endpoint: POST http://192.168.0.106:1234/api/v1/chat
// Headers : Content-Type: application/json
// Body    : custom (NOT OpenAI-compatible)
//   {
//     "model": "<model-id>",
//     "system_prompt": "...",
//     "input": "..."
//   }
//
// Response parser tries common field names in order:
//   output / response / text / content / choices[0].message.content
//   Reasoning models still wrap the answer in <think>…</think>; we strip
//   everything up to and including the last </think>.

final class LMStudioProvider: AIProvider {
    let id: ProviderID = .lmstudio

    private let endpoint = URL(string: "http://192.168.0.106:1234/api/v1/chat")!

    private let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest  = 30   // read timeout
        cfg.timeoutIntervalForResource = 60
        return URLSession(configuration: cfg)
    }()

    private func log(_ msg: String) { NSLog("🐢[LMStudio] %@", msg) }

    func execute(_ payload: CommandPayload) async throws -> CommandResult {
        let system = CommandRouter.systemPrompt(for: payload.command, prompt: payload.prompt)
        let user   = userMessage(from: payload)
        let raw    = try await chat(modelID: payload.model.id, system: system, user: user)
        let clean  = stripReasoning(raw)

        switch payload.command {
        case "reply": return .suggestions(parseSuggestionsJSON(clean))
        default:      return .text(clean)
        }
    }

    private func userMessage(from p: CommandPayload) -> String {
        switch p.command {
        case "fix", "tone", "reply", "tl":
            // act on the text BEFORE the slash; fall back to prompt if field was empty
            return p.context.isEmpty ? p.prompt : p.context
        case "ask", "org":
            // open-ended — the prompt IS the user's question/request
            return p.prompt
        default:
            return p.prompt
        }
    }

    // MARK: - Chat completion

    private func chat(modelID: String, system: String, user: String) async throws -> String {
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let body: [String: Any] = [
            "model": modelID,
            "system_prompt": system,
            "input": user,
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        log("POST \(endpoint.absoluteString)  model=\(modelID)")

        let (data, response) = try await fetch(request)
        try validate(response, data: data)

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let content = Self.extractContent(from: json) else {
            let snippet = String(data: data, encoding: .utf8)?.prefix(400) ?? "(non-utf8)"
            log("unexpected response shape: \(snippet)")
            throw ProviderError.badResponse("Unexpected LM Studio response shape")
        }
        log("response chars=\(content.count)")
        return content
    }

    /// Defensive parser — handles three known response shapes:
    ///
    /// 1. New LM Studio: `output` is an array of `{type, content}` entries.
    ///    The user-facing answer is the one with `type == "message"`;
    ///    `type == "reasoning"` is the model's internal monologue and is
    ///    discarded (replaces the old `<think>…</think>` inline form).
    /// 2. Plain string at one of several common field names.
    /// 3. OpenAI-compatible `choices[0].message.content`.
    private static func extractContent(from json: [String: Any]) -> String? {
        // (1) Typed-entries array
        if let outputArr = json["output"] as? [[String: Any]] {
            if let msg = outputArr.first(where: { ($0["type"] as? String) == "message" }),
               let content = msg["content"] as? String, !content.isEmpty {
                return content
            }
            // Fallback if no entry is tagged "message" — concatenate all
            // content strings so the user sees something rather than an error.
            let merged = outputArr
                .compactMap { $0["content"] as? String }
                .joined(separator: "\n")
            if !merged.isEmpty { return merged }
        }
        // (2) Single-string forms
        for key in ["output", "response", "text", "content", "answer", "result"] {
            if let s = json[key] as? String, !s.isEmpty { return s }
        }
        // (3) OpenAI-compatible nested form
        if let choices = json["choices"] as? [[String: Any]],
           let message = choices.first?["message"] as? [String: Any],
           let content = message["content"] as? String {
            return content
        }
        return nil
    }

    // MARK: - Reasoning model output cleanup
    // Model emits:  <think>…internal monologue…</think>\nactual answer
    // We keep only the text after the last </think>.

    private func stripReasoning(_ text: String) -> String {
        if let close = text.range(of: "</think>", options: .backwards) {
            return String(text[close.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - HTTP helpers

    private func fetch(_ request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await session.data(for: request)
        } catch let e as URLError {
            log("URLError [\(e.code.rawValue)]: \(e.localizedDescription)")
            throw ProviderError.network(e)
        } catch {
            throw ProviderError.unknown(error)
        }
    }

    private func validate(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else {
            throw ProviderError.badResponse("No HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            let snippet = String(data: data, encoding: .utf8)?.prefix(400) ?? "(non-utf8)"
            log("HTTP \(http.statusCode): \(snippet)")
            throw ProviderError.http(http.statusCode)
        }
    }
}
