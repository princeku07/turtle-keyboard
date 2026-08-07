import Foundation

// MARK: - CommandRouter
//
// Picks *where* a command runs, then dispatches it there.
//
// Every command has a tier plan (`ExecutionTier.plan(for:)`) listing the
// backends that can serve it, cheapest-first:
//
//     .deterministic  LocalTextEngine   — no model, free, offline, instant
//     .onDevice       Apple FoundationModels — free, offline, no tokens
//     .cloud          Gemini over HTTPS — spends tokens
//
// `run(...)` walks that plan in order and returns the first tier that
// succeeds, escalating only on *capability* failures (tier unavailable, model
// produced something unusable). A tier that declines on content grounds does
// not escalate — see `isEscalatable(_:)`.
//
// The user can constrain the whole thing with `InferenceMode` from the host
// app's Personalization screen, which is applied before the plan is walked.
//
// To re-route the cloud tier for a command: edit a `defaultRoutes` entry, or
// pin a model at runtime via `setModel(_:for:)` (persisted across launches).

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

    // One provider per tier. Stored individually rather than in a dictionary
    // so `provider(for:)` stays exhaustive and `localProvider` keeps its
    // concrete type — the last-resort pass needs `executeLenient`.
    private let googleProvider = GoogleProvider()
    private let appleProvider  = AppleOnDeviceProvider()
    private let localProvider  = LocalProvider()

    private func provider(for id: ProviderID) -> AIProvider {
        switch id {
        case .google: return googleProvider
        case .apple:  return appleProvider
        case .local:  return localProvider
        }
    }

    /// Shared App Group suite, so the mode the host app's Personalization
    /// screen writes is the mode the keyboard reads.
    private let store: SplitStore = UserDefaultsSplitStore(suiteName: SplitContract.storageSuiteName)

    var inferenceMode: InferenceMode { InferenceMode.current(store: store) }

    /// Tier that served the most recent execution, for banner text only.
    /// Safe to read without synchronisation because the keyboard runs exactly
    /// one command at a time (`isGenerating` gates the send path) — treat it
    /// as diagnostic, never as control flow.
    private(set) var lastServedTier: ExecutionTier?

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
            "poll":   txt,
        ]
    }

    // Model pins are extension-local by design — they're a debug/power-user
    // affordance, not shared state the host app writes.
    private let defaults: UserDefaults = .standard

    // MARK: - Tier planning

    /// Tiers that may serve `command`, cheapest-first, after applying the
    /// user's `InferenceMode` and dropping tiers with no usable backend.
    /// Empty means the command cannot run at all right now.
    func resolvedPlan(for command: String) -> [ExecutionTier] {
        let mode = inferenceMode

        // A pinned model overrides the plan — but not the privacy mode. If the
        // user pinned Gemini and then chose On-device, On-device wins.
        if let pinned = pinnedModel(for: command) {
            let tier = ExecutionTier(provider: pinned.provider)
            return mode.allows(tier) ? [tier] : []
        }

        return ExecutionTier.plan(for: command).filter { tier in
            guard mode.allows(tier) else { return false }
            switch tier {
            case .deterministic:
                return LocalTextEngine.supportedCommands.contains(command)
            case .onDevice:
                // Checked up front so the loading message and banner can be
                // honest, and so we don't spend a round trip discovering it.
                return OnDeviceModel.availability.isAvailable
            case .cloud:
                return true
            }
        }
    }

    /// Where `command` will run if it were sent right now. Drives the
    /// "on-device" hint in the generating overlay.
    func plannedTier(for command: String) -> ExecutionTier {
        resolvedPlan(for: command).first ?? .cloud
    }

    /// The model that would serve `command` right now, on its cheapest
    /// available tier.
    func model(for command: String) -> AIModel {
        if let pinned = pinnedModel(for: command) { return pinned }
        if let tier = resolvedPlan(for: command).first,
           let model = resolveModel(command: command, tier: tier) {
            return model
        }
        return cloudModel(for: command)
    }

    private func pinnedModel(for command: String) -> AIModel? {
        guard let savedID = defaults.string(forKey: "turtle_route_\(command)") else { return nil }
        return ModelRegistry.find(id: savedID)
    }

    private func resolveModel(command: String, tier: ExecutionTier) -> AIModel? {
        if let pinned = pinnedModel(for: command),
           ExecutionTier(provider: pinned.provider) == tier {
            return pinned
        }
        if tier == .cloud { return cloudModel(for: command) }
        return ModelRegistry.model(for: command, tier: tier)
    }

    /// Unknown command — fall back to text Flash. If a brand-new image-only
    /// command lands here without a route entry it'll still fail the
    /// capability check in `run(...)`, surfacing a clear error rather than a
    /// silent mis-route.
    private func cloudModel(for command: String) -> AIModel {
        defaultRoutes[command] ?? ModelRegistry.geminiFlash
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
        try await run(command: command, prompt: prompt, context: context,
                      referenceImage: referenceImage, onDelta: nil)
    }

    /// Streaming counterpart of `execute` — same routing, but the provider
    /// emits text chunks through `onDelta` as they arrive. Providers that
    /// don't stream fall back to one-shot via the `AIProvider` default.
    func executeStreaming(command: String, prompt: String, context: String,
                          referenceImage: Data? = nil,
                          onDelta: @escaping (String) -> Void) async throws -> CommandResult {
        try await run(command: command, prompt: prompt, context: context,
                      referenceImage: referenceImage, onDelta: onDelta)
    }

    /// Walks the command's tier plan cheapest-first and returns the first
    /// success.
    private func run(command: String, prompt: String, context: String,
                     referenceImage: Data?,
                     onDelta: ((String) -> Void)?) async throws -> CommandResult {
        lastServedTier = nil

        let plan = resolvedPlan(for: command)

        // Command name, tiers, and availability only — never the user's text
        // (the privacy invariant applies to logs too). Without this a tier
        // that silently declines is invisible: `run` reports only the *last*
        // tier's error, so an on-device miss looks like a cloud failure.
        NSLog("🐢[Router] /%@ mode=%@ plan=[%@] appleIntelligence=%@",
              command,
              inferenceMode.rawValue,
              plan.map(\.debugName).joined(separator: " → "),
              OnDeviceModel.availability.reason ?? "available")

        guard !plan.isEmpty else { throw planError(for: command) }

        guard let capability = Self.requiredCapability(for: command) else {
            throw ProviderError.unsupportedCommand(command)
        }

        // Once a tier has streamed text into the user's field we're committed
        // to it — escalating would append a second answer on top of a partial
        // one. The gate flips on the first delta and blocks all escalation
        // from that point.
        let gate = DeltaGate()
        let sink: ((String) -> Void)? = onDelta.map { downstream in
            { chunk in
                gate.mark()
                downstream(chunk)
            }
        }

        var lastError: Error = ProviderError.unsupportedCommand(command)

        for tier in plan {
            guard let model = resolveModel(command: command, tier: tier),
                  model.supports(capability) else { continue }

            let payload = CommandPayload(
                command: command,
                model: model,
                prompt: prompt,
                context: context,
                locale: Locale.current.identifier,
                referenceImage: referenceImage
            )

            do {
                let backend = provider(for: tier.providerID)
                let result: CommandResult
                if let sink = sink {
                    result = try await backend.executeStreaming(payload, onDelta: sink)
                } else {
                    result = try await backend.execute(payload)
                }
                lastServedTier = tier
                return result
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                lastError = error
                NSLog("🐢[Router] /%@ tier=%@ failed (escalatable=%@): %@",
                      command, tier.debugName,
                      Self.isEscalatable(error) ? "yes" : "no",
                      error.localizedDescription)
                guard !gate.didEmit, Self.isEscalatable(error) else { throw error }
                continue
            }
        }

        // Every tier declined. For commands the deterministic engine can
        // serve, take one lenient pass so `/fix` returns cleaned-up text
        // rather than an error on a device with no Apple Intelligence and no
        // network. Skipped when the plan already ran it and the user's mode
        // forbids it.
        if !gate.didEmit,
           LocalTextEngine.supportedCommands.contains(command),
           inferenceMode.allows(.deterministic) {
            let payload = CommandPayload(
                command: command,
                model: ModelRegistry.localDeterministic,
                prompt: prompt,
                context: context,
                locale: Locale.current.identifier
            )
            if let result = try? await localProvider.executeLenient(payload) {
                lastServedTier = .deterministic
                return result
            }
        }

        throw lastError
    }

    /// Why an empty plan is empty, phrased for the keyboard banner.
    private func planError(for command: String) -> ProviderError {
        guard inferenceMode == .onDeviceOnly else {
            return .unsupportedCommand(command)
        }
        // No non-cloud tier exists for this command at all (images, /search).
        if !ExecutionTier.plan(for: command).contains(where: { !$0.costsTokens }) {
            return .requiresCloud(command)
        }
        // A non-cloud tier exists but isn't usable on this device right now.
        if let why = OnDeviceModel.availability.reason {
            return .onDeviceUnavailable(why)
        }
        return .unsupportedCommand(command)
    }

    /// Whether a failure at one tier justifies trying the next one.
    ///
    /// The rule is: escalate on *capability* failures — the tier couldn't run,
    /// or produced output that failed validation. Do not escalate a content
    /// decision. `onDeviceRefused` means Apple's guardrails declined the text;
    /// quietly forwarding it to a cloud provider would ship content the user
    /// had every reason to believe stayed local.
    static func isEscalatable(_ error: Error) -> Bool {
        if error is CancellationError { return false }
        guard let providerError = error as? ProviderError else {
            // URLError and friends — only ever raised by the cloud tier,
            // which is last, so this just becomes the reported error.
            return true
        }
        switch providerError {
        case .onDeviceRefused:
            return false
        case .onDeviceUnavailable, .noLocalImprovement, .badResponse,
             .unsupportedCommand, .missingAPIKey, .http, .network,
             .requiresCloud, .unknown:
            return true
        }
    }
}

// MARK: - DeltaGate

/// Thread-safe one-way latch recording whether any streamed delta has already
/// been inserted into the user's text field.
private final class DeltaGate {
    private let lock = NSLock()
    private var emitted = false

    func mark() {
        lock.lock()
        emitted = true
        lock.unlock()
    }

    var didEmit: Bool {
        lock.lock()
        defer { lock.unlock() }
        return emitted
    }
}

extension CommandRouter {

    // MARK: - Capability map  (used by ModelRegistry.compatible(with:))

    static func requiredCapability(for command: String) -> ModelCapability? {
        switch command {
        case "cap", "edit", "style", "sticker", "gif": return .imageGeneration
        case "fix", "proofread", "tone", "org": return .textEdit
        case "reply", "ask", "search", "poll": return .chat
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

        case "poll":
            // Shapes the user's line into `{question, options}` for
            // `PollIntegration`. The on-device tier overrides this with
            // `AppleOnDeviceProvider.pollInstructions` — see the note there
            // on why guided generation wants the format contract dropped.
            return PromptLoader.load(id: "poll") ?? """
            Generate a poll based on the user's request. Output ONLY a JSON object — no markdown fences, no preamble: {"question": "<single-sentence question ending in ?>", "options": ["<short label>", ...]}. Provide 2 to 6 options, each 1-4 words. Match the language and tone of the user's prompt. No emojis in the question.
            """

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
