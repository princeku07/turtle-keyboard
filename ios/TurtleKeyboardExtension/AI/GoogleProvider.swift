import Foundation

// MARK: - GoogleProvider
//
// Text commands via Google Generative Language API (Gemini).
// Supported commands: /fix, /tone, /reply, /tl
// Supported models:   Gemini Flash, Gemini Pro
//
// API key: set via KeyStore.shared[.google] = "your_key"
// Get a key at: https://aistudio.google.com/app/apikey

final class GoogleProvider: AIProvider {
    let id: ProviderID = .google

    private let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest  = 15
        cfg.timeoutIntervalForResource = 30
        return URLSession(configuration: cfg)
    }()

    func execute(_ payload: CommandPayload) async throws -> CommandResult {
        let systemPrompt = CommandRouter.systemPrompt(for: payload.command, prompt: payload.prompt)
        let userContent  = userMessage(from: payload)
        // Gemini doesn't have a separate system field — prepend to user content
        let fullPrompt   = systemPrompt + "\n\n" + userContent
        let raw          = try await generateContent(modelID: payload.model.id, prompt: fullPrompt)

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

    private func generateContent(modelID: String, prompt: String) async throws -> String {
        let key = try KeyStore.shared.requireKey(for: .google)

        let urlString = "https://generativelanguage.googleapis.com/v1beta/models/\(modelID):generateContent?key=\(key)"
        guard let url = URL(string: urlString) else {
            throw ProviderError.badResponse("Invalid Gemini URL for model \(modelID)")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let body: [String: Any] = [
            "contents": [["parts": [["text": prompt]]]],
            "generationConfig": ["maxOutputTokens": 512, "temperature": 0.7]
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await fetch(request)
        try validate(response)

        // Response: { "candidates": [{ "content": { "parts": [{ "text": "..." }] } }] }
        guard let json       = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidates = json["candidates"] as? [[String: Any]],
              let content    = candidates.first?["content"] as? [String: Any],
              let parts      = content["parts"] as? [[String: Any]],
              let text       = parts.first?["text"] as? String else {
            throw ProviderError.badResponse("Unexpected Gemini response shape")
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
