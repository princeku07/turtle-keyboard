import Foundation

// MARK: - OpenAIProvider
//
// Text commands via OpenAI Chat Completions API.
// Supported commands: /fix, /tone, /reply, /tl
// Supported models:   GPT-4o mini, GPT-4o
//
// API key: set via KeyStore.shared[.openai] = "your_key"
// Get a key at: https://platform.openai.com/api-keys

final class OpenAIProvider: AIProvider {
    let id: ProviderID = .openai

    private let endpoint = URL(string: "https://api.openai.com/v1/chat/completions")!

    private let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest  = 15
        cfg.timeoutIntervalForResource = 30
        return URLSession(configuration: cfg)
    }()

    func execute(_ payload: CommandPayload) async throws -> CommandResult {
        let system = CommandRouter.systemPrompt(for: payload.command, prompt: payload.prompt)
        let user   = userMessage(from: payload)
        let raw    = try await chatCompletion(modelID: payload.model.id, system: system, user: user)

        switch payload.command {
        case "reply": return .suggestions(parseSuggestionsJSON(raw))
        default:      return .text(raw)
        }
    }

    // MARK: - Prompt construction

    private func userMessage(from p: CommandPayload) -> String {
        switch p.command {
        case "fix", "tone", "reply", "tl":
            return p.context.isEmpty ? p.prompt : p.context
        default:
            return p.prompt
        }
    }

    // MARK: - API call

    private func chatCompletion(modelID: String, system: String, user: String) async throws -> String {
        let key = try KeyStore.shared.requireKey(for: .openai)

        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")

        let body: [String: Any] = [
            "model": modelID,
            "max_tokens": 512,
            "temperature": 0.7,
            "messages": [
                ["role": "system", "content": system],
                ["role": "user",   "content": user]
            ]
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await fetch(request)
        try validate(response)

        // Response: { "choices": [{ "message": { "content": "..." } }] }
        guard let json    = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = json["choices"] as? [[String: Any]],
              let message = choices.first?["message"] as? [String: Any],
              let text    = message["content"] as? String else {
            throw ProviderError.badResponse("Unexpected OpenAI response shape")
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
