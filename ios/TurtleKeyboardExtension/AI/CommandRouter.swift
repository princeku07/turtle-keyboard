import Foundation

// MARK: - CommandRouter
//
// Maps each slash command to a Gemini model and dispatches to
// `GoogleProvider`. The stack is Gemini-only — earlier multi-provider
// scaffolding was removed once Flash + Pro proved sufficient.
//
// To re-route a command:  edit a `defaultRoutes` entry, or let the user
// pick a model at runtime via `setModel(_:for:)` (persisted across launches).

final class CommandRouter {
    static let shared = CommandRouter()
    private init() {
        // Bootstrap KeyStore from the build-time `.env`. Host-app
        // onboarding can still overwrite this later via
        // `KeyStore.shared.setGoogleKey(_:)`.
        if !Secrets.geminiApiKey.isEmpty,
           (KeyStore.shared[.google] ?? "").isEmpty {
            KeyStore.shared.setGoogleKey(Secrets.geminiApiKey)
        }
    }

    /// Single registered provider — Gemini via direct HTTP.
    private let providers: [ProviderID: AIProvider] = [
        .google: GoogleProvider(),
    ]

    /// `/gif` routes to Nano Banana **Pro** (`gemini-3-pro-image-preview`)
    /// because the 4×N sprite-sheet layout is too complex for Flash to
    /// hold reliably — Flash returns the frames as separate images and
    /// breaks the slice step. Every other image command routes to Flash
    /// (`gemini-2.5-flash-image`).
    private var defaultRoutes: [String: AIModel] {
        let txt = ModelRegistry.geminiFlash
        let img = ModelRegistry.geminiImage
        return [
            "cap":     img,
            "edit":    img,
            "style":   img,
            "sticker": img,
            "gif":     ModelRegistry.geminiImagePro,
            "fix":       txt,
            "proofread": txt,
            "tone":   txt,
            "reply":  txt,
            "tl":     txt,
            "search": txt,
            "ask":    txt,
            "org":    txt,
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
        // Unknown command — fall back to text Flash. If a brand-new
        // image-only command lands here without a route entry it'll
        // still fail the capability check in `execute(...)`, surfacing
        // a clear "unsupportedCommand" error rather than a silent route.
        return defaultRoutes[command] ?? ModelRegistry.geminiFlash
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

    func execute(command: String, prompt: String, context: String,
                 referenceImage: Data? = nil) async throws -> CommandResult {
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
            locale: Locale.current.identifier,
            referenceImage: referenceImage
        )
        return try await provider.execute(payload)
    }

    // MARK: - Capability map  (used by ModelRegistry.compatible(with:))

    static func requiredCapability(for command: String) -> ModelCapability? {
        switch command {
        case "cap", "edit", "style", "sticker", "gif": return .imageGeneration
        case "fix", "proofread", "tone", "org": return .textEdit
        case "reply", "ask", "search": return .chat
        case "tl":                     return .translation
        default:                       return nil
        }
    }

    // MARK: - System prompts
    // Centralised here so all providers share the same prompt logic.

    static func systemPrompt(for command: String, prompt: String) -> String {
        switch command {
        case "edit", "style":
            // /style sends the generic edit guidance as the system
            // instruction and the actual restyle text in the user-turn
            // alongside the image — Gemini's image-edit models follow
            // the content-turn text far more reliably than systemInstruction.
            // See `GoogleProvider.userMessage(from:)` for the user-side
            // expansion via `StylePresets.userPrompt(for:)`.
            return PromptLoader.load(id: "edit")
                ?? "Edit the supplied image following the user's instruction. Return ONLY the edited image; do not explain or annotate."

        case "fix":
            return "You are a grammar and spelling corrector. Fix the grammar, spelling, and punctuation of the given text. Return ONLY the corrected text — no explanation, no preamble."

        case "proofread":
            // Fuller pass than /fix — clarity + flow on top of mechanics —
            // while strictly preserving the author's meaning, voice, tone,
            // and language. No new ideas, no quotes, no notes.
            return """
            You are a meticulous proofreader. Correct spelling, grammar, punctuation, word choice, and awkward phrasing in the given text, and lightly improve clarity and flow. Preserve the author's original meaning, voice, tone, and language exactly — do not add new ideas, do not translate, do not change formatting or emoji. Return ONLY the corrected text — no preamble, no explanation, no surrounding quotes.
            """

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

        case "search":
            // Loaded from `commands/prompts/search.txt` (copied into the
            // appex bundle by the Run Script build phase). Falls back to a
            // search-flavoured tweak of the ask prompt if the asset is
            // missing.
            return PromptLoader.load(id: "search")
                ?? "Treat the input as a search query. Lead with the direct answer in one short paragraph. No preface, no markdown headings, no bullet lists. If you can't be confident, say so in one sentence — never invent specifics."

        case "sticker":
            // Pass-1 prompt — render the subject on a locked PURE WHITE
            // background. `GoogleProvider` then issues a pass-2 edit using
            // `MattePrompts.swapWhiteToBlack` and combines the two via
            // `AlphaMatte.differenceMatte` to recover transparency.
            return PromptLoader.load(id: "sticker")
                ?? "Render the user's subject as a sticker on a pure white #FFFFFF background. Bold simple shapes, clean outlines, vivid flat colors, no gradients on the background. Keep the subject centered. Return ONLY the image."

        case "gif":
            // Pass-1 prompt — produce a 4-column sprite SHEET (the full
            // grid as ONE image) with the subject animated across cells,
            // on a locked white background. `GoogleProvider` slices the
            // sheet with `SpriteSheetSlicer`, runs pass-2 matte, encodes
            // an animated GIF via ImageIO.
            return PromptLoader.load(id: "gif")
                ?? "Return ONE image laid out as a 4-column sprite SHEET of frames depicting the user's subject in motion. Use a 4×4, 4×2, or 4×1 grid (4×4 preferred). All cells equal size, equal spacing, pure white #FFFFFF background between and behind cells. No frame numbers, no captions, no margins. Return ONLY the sheet."

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
