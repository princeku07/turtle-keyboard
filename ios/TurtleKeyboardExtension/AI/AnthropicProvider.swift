import Foundation

// MARK: - AnthropicProvider
//
// Text commands via Anthropic's Messages API.
// Supported commands: /fix, /tone, /reply, /tl
// Supported models:   Claude Haiku, Claude Sonnet
//
// API key: set via KeyStore.shared[.anthropic] = "your_key"
// Get a key at: https://console.anthropic.com/settings/keys

final class AnthropicProvider: AIProvider {
    let id: ProviderID = .anthropic

    private let endpoint = URL(string: "https://api.anthropic.com/v1/messages")!
    private let apiVersion = "2023-06-01"

    private let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest  = 15
        cfg.timeoutIntervalForResource = 30
        return URLSession(configuration: cfg)
    }()

    func execute(_ payload: CommandPayload) async throws -> CommandResult {
        let system = CommandRouter.systemPrompt(for: payload.command, prompt: payload.prompt)
        let user   = userMessage(from: payload)
        let raw    = try await callMessages(modelID: payload.model.id, system: system, user: user)

        switch payload.command {
        case "reply": return .suggestions(parseSuggestionsJSON(raw))
        default:      return .text(raw)
        }
    }

    // MARK: - Prompt construction

    private func userMessage(from p: CommandPayload) -> String {
        switch p.command {
        case "fix", "tone", "reply", "tl":
            // context = text before the slash; fall back to prompt if field was empty
            return p.context.isEmpty ? p.prompt : p.context
        default:
            return p.prompt
        }
    }

    // MARK: - API call

    private func callMessages(modelID: String, system: String, user: String) async throws -> String {
        let key = try KeyStore.shared.requireKey(for: .anthropic)

        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(key, forHTTPHeaderField: "x-api-key")
        request.setValue(apiVersion, forHTTPHeaderField: "anthropic-version")

        let body: [String: Any] = [
            "model": modelID,
            "max_tokens": 512,
            "system": system,
            "messages": [["role": "user", "content": user]]
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await fetch(request)
        try validate(response)

        // Response: { "content": [{ "type": "text", "text": "..." }], ... }
        guard let json    = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let content = json["content"] as? [[String: Any]],
              let text    = content.first?["text"] as? String else {
            throw ProviderError.badResponse("Unexpected Anthropic response shape")
        }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Helpers

    private func fetch(_ request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await session.data(for: request)
        } catch let e as URLError { throw ProviderError.network(e) }
        catch { throw ProviderError.unknown(error) }
    }

    private func validate(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else {
            throw ProviderError.badResponse("No HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw ProviderError.http(http.statusCode)
        }
    }
}
