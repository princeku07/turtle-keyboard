import Foundation

// MARK: - CommandRouter
//
// Maps each slash command to an AIModel, builds the right system prompt,
// and dispatches to the correct provider.
//
// Plug in a new model:
//   • Add it to ModelRegistry.
//   • Change a defaultRoute entry, or let the user pick it via setModel(_:for:).
//
// Plug in a new provider:
//   • Implement AIProvider.
//   • Register an instance in the providers dictionary below.

final class CommandRouter {
    static let shared = CommandRouter()
    private init() {
        // Surface the Gemini key from the build-time `.env` to KeyStore so
        // GoogleProvider can pick it up. Host-app onboarding can still
        // overwrite this later via KeyStore.shared.setGoogleKey(_:).
        if !Secrets.geminiApiKey.isEmpty,
           (KeyStore.shared[.google] ?? "").isEmpty {
            KeyStore.shared.setGoogleKey(Secrets.geminiApiKey)
        }
    }

    // Registered providers.
    // GoogleProvider (Gemini) is registered so text commands can route to
    // Gemini when a key is present in `.env`. Anthropic/OpenAI remain
    // unregistered until we wire host-app key entry for them.
    private let providers: [ProviderID: AIProvider] = [
        .fal:      FalProvider(),
        .lmstudio: LMStudioProvider(),
        .google:   GoogleProvider(),
    ]

    // Default model per command.
    // When a Gemini key is present in Secrets, every command (text + /cap)
    // routes to Gemini, matching the Android client. Without a key, text falls
    // back to local Gemma via LM Studio and /cap falls back to Flux 2 via the
    // Spark gateway.
    private var hasGeminiKey: Bool { !Secrets.geminiApiKey.isEmpty }

    private var textDefault: AIModel {
        hasGeminiKey ? ModelRegistry.geminiFlash : ModelRegistry.gemma4
    }

    private var imageDefault: AIModel {
        hasGeminiKey ? ModelRegistry.geminiImage : ModelRegistry.flux2
    }

    private var defaultRoutes: [String: AIModel] {
        let txt = textDefault
        return [
            "cap":   imageDefault,
            "fix":   txt,
            "tone":  txt,
            "reply": txt,
            "tl":    txt,
            "ask":   txt,
            "org":   txt,
        ]
    }

    // TODO: migrate to shared App Group defaults once App Groups are wired
    private let defaults: UserDefaults = .standard

    // MARK: - Routing

    func model(for command: String) -> AIModel {
        if let savedID = defaults.string(forKey: "turtle_route_\(command)"),
           let model = ModelRegistry.find(id: savedID) {
            return model
        }
        return defaultRoutes[command] ?? ModelRegistry.claudeHaiku
    }

    /// Override which model handles a command. Persisted across launches.
    func setModel(_ model: AIModel, for command: String) {
        defaults.set(model.id, forKey: "turtle_route_\(command)")
    }

    /// Reset a command's model to the built-in default.
    func resetModel(for command: String) {
        defaults.removeObject(forKey: "turtle_route_\(command)")
    }

    // MARK: - Execution

    func execute(command: String, prompt: String, context: String) async throws -> CommandResult {
        let model = model(for: command)

        guard let capability = Self.requiredCapability(for: command),
              model.supports(capability) else {
            throw ProviderError.unsupportedCommand(command)
        }

        guard let provider = providers[model.provider] else {
            throw ProviderError.unsupportedCommand(command)
        }

        let payload = CommandPayload(
            command: command,
            model: model,
            prompt: prompt,
            context: context,
            locale: Locale.current.identifier
        )
        return try await provider.execute(payload)
    }

    // MARK: - Capability map  (used by ModelRegistry.compatible(with:))

    static func requiredCapability(for command: String) -> ModelCapability? {
        switch command {
        case "cap":               return .imageGeneration
        case "fix", "tone", "org": return .textEdit
        case "reply", "ask":      return .chat
        case "tl":                return .translation
        default:                  return nil
        }
    }

    // MARK: - System prompts
    // Centralised here so all providers share the same prompt logic.

    static func systemPrompt(for command: String, prompt: String) -> String {
        switch command {
        case "fix":
            return "You are a grammar and spelling corrector. Fix the grammar, spelling, and punctuation of the given text. Return ONLY the corrected text — no explanation, no preamble."

        case "tone":
            let style = prompt.isEmpty ? "professional" : prompt
            return "Rewrite the given text in a \(style) tone. Return ONLY the rewritten text — no explanation, no preamble."

        case "reply":
            return """
            You are a helpful communication assistant. Based on the message given, suggest exactly 3 short, natural reply options.
            Return ONLY a valid JSON array of 3 strings. No markdown, no explanation. Example: ["Sure!", "Sounds good!", "Let me check."]
            """

        case "tl":
            let lang = prompt.isEmpty ? "English" : prompt
            return "Translate the given text to \(lang). Return ONLY the translated text — no explanation, no preamble."

        case "ask":
            return PromptLoader.load(id: "ask")
                ?? "Answer concisely. No preface, no markdown headings."

        case "org":
            return PromptLoader.load(id: "org") ?? """
            You are a layout assistant. Convert the user request into a single self-contained HTML fragment.
            Use inline CSS via the style attribute only. No external CSS, no JavaScript, no <html>/<head>/<body> tags.
            Keep it compact and visually clean. Output ONLY the HTML fragment — no preface, no explanation, no markdown code fences.
            """

        default:
            return "You are a helpful assistant."
        }
    }
}
