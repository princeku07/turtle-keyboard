import Foundation

// MARK: - GoogleProvider
//
// Text + image commands via Google Generative Language API (Gemini).
// Mirrors android/ai/GeminiClient.java:
//   • text   → gemini-2.5-flash-lite  (or any text-capable model in ModelRegistry)
//   • image  → gemini-2.5-flash-image ("Nano Banana"), returned as inline PNG bytes
//
// Supported commands: /fix, /tone, /reply, /tl, /ask, /org, /cap
//
// API key: set via KeyStore.shared[.google] = "your_key"
// Get a key at: https://aistudio.google.com/app/apikey

final class GoogleProvider: AIProvider {
    let id: ProviderID = .google

    private let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest  = 30
        cfg.timeoutIntervalForResource = 90
        return URLSession(configuration: cfg)
    }()

    func execute(_ payload: CommandPayload) async throws -> CommandResult {
        let systemPrompt = CommandRouter.systemPrompt(for: payload.command, prompt: payload.prompt)
        let userContent  = userMessage(from: payload)

        if payload.model.supports(.imageGeneration) {
            // /edit and friends supply a reference image — route through
            // the multi-part path that prepends inlineData to the content.
            if let ref = payload.referenceImage, !ref.isEmpty {
                let png = try await editImage(modelID: payload.model.id,
                                              systemPrompt: systemPrompt,
                                              userPrompt: userContent,
                                              reference: ref)
                return .imageData(png)
            }
            let png = try await generateImage(modelID: payload.model.id,
                                              systemPrompt: systemPrompt,
                                              userPrompt: userContent)
            return .imageData(png)
        }

        let raw = try await generateText(modelID: payload.model.id,
                                         systemPrompt: systemPrompt,
                                         userPrompt: userContent)

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

    // MARK: - Text

    private func generateText(modelID: String, systemPrompt: String, userPrompt: String) async throws -> String {
        let data = try await callGenerateContent(modelID: modelID,
                                                 systemPrompt: systemPrompt,
                                                 userPrompt: userPrompt)
        guard let json       = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidates = json["candidates"] as? [[String: Any]],
              let content    = candidates.first?["content"] as? [String: Any],
              let parts      = content["parts"] as? [[String: Any]] else {
            throw ProviderError.badResponse("Unexpected Gemini response shape")
        }
        let text = parts.compactMap { $0["text"] as? String }.joined()
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Image

    private func generateImage(modelID: String, systemPrompt: String, userPrompt: String) async throws -> Data {
        let data = try await callGenerateContent(modelID: modelID,
                                                 systemPrompt: systemPrompt,
                                                 userPrompt: userPrompt)

        guard let json       = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidates = json["candidates"] as? [[String: Any]],
              let content    = candidates.first?["content"] as? [String: Any],
              let parts      = content["parts"] as? [[String: Any]] else {
            throw ProviderError.badResponse("Unexpected Gemini image response shape")
        }

        for part in parts {
            // Accept both camelCase and snake_case (the API has used both).
            let inline = (part["inlineData"] as? [String: Any]) ?? (part["inline_data"] as? [String: Any])
            if let b64 = inline?["data"] as? String,
               let bytes = Data(base64Encoded: b64, options: [.ignoreUnknownCharacters]) {
                return bytes
            }
        }
        throw ProviderError.badResponse("No inline image part in Gemini response")
    }

    // MARK: - Image edit (multipart: inlineData + text)
    //
    // Mirrors android/.../ai/GeminiClient.doImageEdit. The single `contents`
    // entry carries two parts — the reference image inline (base64 PNG)
    // followed by the user's instruction text. Gemini's flash-image model
    // reads both and returns the edited image as inlineData in the response.

    private func editImage(modelID: String,
                           systemPrompt: String,
                           userPrompt: String,
                           reference: Data) async throws -> Data {
        let data = try await callGenerateContent(modelID: modelID,
                                                 systemPrompt: systemPrompt,
                                                 userPrompt: userPrompt,
                                                 reference: reference)

        guard let json       = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidates = json["candidates"] as? [[String: Any]],
              let content    = candidates.first?["content"] as? [String: Any],
              let parts      = content["parts"] as? [[String: Any]] else {
            throw ProviderError.badResponse("Unexpected Gemini edit response shape")
        }
        for part in parts {
            let inline = (part["inlineData"] as? [String: Any]) ?? (part["inline_data"] as? [String: Any])
            if let b64 = inline?["data"] as? String,
               let bytes = Data(base64Encoded: b64, options: [.ignoreUnknownCharacters]) {
                return bytes
            }
        }
        throw ProviderError.badResponse("No edited-image part in Gemini response")
    }

    // MARK: - HTTP

    private func callGenerateContent(modelID: String,
                                     systemPrompt: String,
                                     userPrompt: String,
                                     reference: Data? = nil) async throws -> Data {
        let key = try KeyStore.shared.requireKey(for: .google)

        guard let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/\(modelID):generateContent") else {
            throw ProviderError.badResponse("Invalid Gemini URL for model \(modelID)")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(key, forHTTPHeaderField: "X-goog-api-key")

        var parts: [[String: Any]] = []
        if let ref = reference, !ref.isEmpty {
            // Inline image first, then the text instruction. Same order
            // Gemini's docs use for image-edit prompts.
            parts.append([
                "inlineData": [
                    "mimeType": "image/png",
                    "data": ref.base64EncodedString(),
                ]
            ])
        }
        parts.append(["text": userPrompt])

        var body: [String: Any] = ["contents": [["parts": parts]]]
        if !systemPrompt.isEmpty {
            body["systemInstruction"] = ["parts": [["text": systemPrompt]]]
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await fetch(request)
        try validate(response, data: data)
        return data
    }

    private func fetch(_ request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await session.data(for: request)
        } catch let e as URLError { throw ProviderError.network(e) }
        catch { throw ProviderError.unknown(error) }
    }

    private func validate(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else {
            throw ProviderError.badResponse("No HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            let snippet = String(data: data, encoding: .utf8)?.prefix(400) ?? ""
            NSLog("🐢[Gemini] HTTP %d %@", http.statusCode, String(snippet))
            throw ProviderError.http(http.statusCode)
        }
    }
}
