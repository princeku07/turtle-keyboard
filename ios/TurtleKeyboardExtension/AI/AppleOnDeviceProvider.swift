import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

// MARK: - AppleOnDeviceProvider
//
// The `.onDevice` tier: Apple's ~3B on-device foundation model via
// FoundationModels (iOS 26+). Free, offline, zero tokens, and the text never
// leaves the phone — which is why this is the default for every text command
// that doesn't need fresh web knowledge.
//
// Why this is safe inside a keyboard extension: the model is hosted by a
// system daemon on the Neural Engine, not loaded into our address space, so
// it does not count against the appex's memory ceiling the way a bundled
// GGUF would. All we hold is a session handle.
//
// System prompts are shared verbatim with the cloud tier via
// `CommandRouter.systemPrompt(for:prompt:)` so a command behaves the same
// wherever it runs — the only difference should be latency and cost.
//
// Everything model-facing lives behind `#if canImport(FoundationModels)` plus
// a runtime `#available` check. The target's deployment floor stays at iOS
// 15; on anything older (or an SDK without the framework) this provider
// simply reports unavailable and the router escalates.

final class AppleOnDeviceProvider: AIProvider {
    let id: ProviderID = .apple

    func execute(_ payload: CommandPayload) async throws -> CommandResult {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            return try await generate(payload, onDelta: nil)
        }
        #endif
        throw Self.unavailable
    }

    func executeStreaming(_ payload: CommandPayload,
                          onDelta: @escaping (String) -> Void) async throws -> CommandResult {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            // `/reply` returns a JSON array and `/org` returns a JSON
            // document rendered to an image — neither has anything useful to
            // show mid-generation, and both need the whole payload before
            // they can be validated.
            let streamable = !Self.nonStreamingCommands.contains(payload.command)
            return try await generate(payload, onDelta: streamable ? onDelta : nil)
        }
        #endif
        throw Self.unavailable
    }

    private static let nonStreamingCommands: Set<String> = ["reply", "org", "poll"]

    private static var unavailable: ProviderError {
        .onDeviceUnavailable(OnDeviceModel.availability.reason ?? "On-device AI is unavailable")
    }
}

// MARK: - FoundationModels implementation

#if canImport(FoundationModels)

// MARK: - Guided generation schemas
//
// Must live at file scope — `@Generable` attaches an extension, and Swift
// only permits extension macros on types declared at the top level.

/// `/poll`'s output shape. Handing this to `respond(generating:)` constrains
/// decoding to the schema, so the model *cannot* return a malformed poll —
/// which is the difference between an on-device tier that usually works and
/// one that's worth defaulting to on a 3B model.
@available(iOS 26.0, *)
@Generable(description: "A shareable poll: one question and its answer options.")
struct OnDevicePollDraft {

    @Guide(description: "The poll question, a single sentence ending in a question mark. No emoji.")
    var question: String

    @Guide(description: "Answer options, 1-4 words each. An emoji at the start of an option is fine.",
           .count(2...6))
    var options: [String]
}

@available(iOS 26.0, *)
private extension AppleOnDeviceProvider {

    func generate(_ payload: CommandPayload,
                  onDelta: ((String) -> Void)?) async throws -> CommandResult {
        // Re-check at call time. Apple Intelligence can be toggled off, or
        // the model asset can still be downloading, between keyboard mounts.
        guard case .available = OnDeviceModel.availability else {
            throw Self.unavailable
        }

        let instructions = Self.instructions(for: payload)
        let userPrompt   = Self.userMessage(from: payload)
        guard !userPrompt.isEmpty else {
            throw ProviderError.badResponse("Nothing to send to the on-device model")
        }

        let session = LanguageModelSession(instructions: instructions)
        let options = Self.options(for: payload.command)

        let raw: String
        do {
            if payload.command == "poll" {
                raw = try await Self.pollJSON(session: session, prompt: userPrompt, options: options)
            } else if let onDelta = onDelta {
                raw = try await Self.stream(session: session, prompt: userPrompt,
                                            options: options, onDelta: onDelta)
            } else {
                raw = try await session.respond(to: userPrompt, options: options).content
            }
        } catch let error as LanguageModelSession.GenerationError {
            throw Self.map(error)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ProviderError.unknown(error)
        }

        return try Self.postProcess(raw, command: payload.command)
    }

    /// Streams cumulative snapshots and forwards only the newly-appended
    /// suffix, because callers insert deltas straight into the user's text
    /// field.
    ///
    /// `ResponseStream<String>.Snapshot.content` is the *whole* partial
    /// response so far, not a delta, and nothing in the contract promises it
    /// only ever grows. If a snapshot ever revises earlier text we stop
    /// emitting rather than typing corrupted output — the full string is
    /// still returned, and `KeyboardViewController.streamTextCommand`
    /// reconciles what it inserted against it.
    static func stream(session: LanguageModelSession,
                       prompt: String,
                       options: GenerationOptions,
                       onDelta: @escaping (String) -> Void) async throws -> String {
        var emitted = ""
        var latest = ""
        var diverged = false

        for try await snapshot in session.streamResponse(to: prompt, options: options) {
            try Task.checkCancellation()
            latest = snapshot.content
            guard !diverged else { continue }

            if latest.hasPrefix(emitted) {
                let delta = String(latest.dropFirst(emitted.count))
                if !delta.isEmpty {
                    onDelta(delta)
                    emitted = latest
                }
            } else {
                diverged = true
            }
        }
        return latest
    }

    /// Guided generation for `/poll`. Constrained decoding fills
    /// `OnDevicePollDraft` directly, so the only way this fails is the model
    /// declining outright — no fence-stripping, no "did it emit valid JSON".
    /// Re-serialised to the same `{question, options}` string the cloud tier
    /// returns, because `PollIntegration` parses one wire format either way.
    static func pollJSON(session: LanguageModelSession,
                         prompt: String,
                         options: GenerationOptions) async throws -> String {
        let draft = try await session.respond(to: prompt,
                                              generating: OnDevicePollDraft.self,
                                              options: options).content
        let payload: [String: Any] = ["question": draft.question, "options": draft.options]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let json = String(data: data, encoding: .utf8) else {
            throw ProviderError.badResponse("Could not encode the on-device poll")
        }
        return json
    }

    /// System prompt for a command. Shared verbatim with the cloud tier via
    /// `CommandRouter.systemPrompt(for:prompt:)` — except for `/poll`.
    ///
    /// `/poll`'s shared prompt spends most of its words specifying a JSON
    /// serialisation contract. Under guided generation the schema already
    /// enforces that, and leaving the instruction in actively hurts: a small
    /// model told "output ONLY a JSON object" while being decoded *into* a
    /// schema tends to emit a JSON blob inside the `question` field. So the
    /// on-device tier keeps the content rules and drops the format contract.
    /// Keep this in sync with the content half of `commands/prompts/poll.txt`.
    static func instructions(for payload: CommandPayload) -> String {
        guard payload.command == "poll" else {
            return CommandRouter.systemPrompt(for: payload.command, prompt: payload.prompt)
        }
        return """
        You write short, shareable polls. Turn the user's request into one \
        question and its answer options. The question is a single sentence \
        ending in a question mark, with no emoji. Give 2 to 6 options, each \
        1-4 words. Match the language and tone of the user's request; for \
        couple, dating, or hangout prompts lean playful.
        """
    }

    static func options(for command: String) -> GenerationOptions {
        switch command {
        // Faithful transforms — greedy sampling keeps the model from
        // paraphrasing text it was only asked to correct or translate.
        case "fix", "proofread", "tl":
            return GenerationOptions(sampling: .greedy, maximumResponseTokens: 400)
        case "org":
            return GenerationOptions(sampling: .greedy, maximumResponseTokens: 1_200)
        case "reply":
            return GenerationOptions(temperature: 0.6, maximumResponseTokens: 200)
        case "tone":
            return GenerationOptions(temperature: 0.5, maximumResponseTokens: 400)
        case "ask":
            return GenerationOptions(temperature: 0.6, maximumResponseTokens: 600)
        // Poll options should feel written, not enumerated — the schema
        // holds the shape, so sampling is free to be a bit loose.
        case "poll":
            return GenerationOptions(temperature: 0.8, maximumResponseTokens: 300)
        default:
            return GenerationOptions(maximumResponseTokens: 400)
        }
    }

    /// Mirrors `GoogleProvider.userMessage(from:)` so a command sees the same
    /// input on either tier, with the field text clamped to the on-device
    /// context window.
    static func userMessage(from p: CommandPayload) -> String {
        switch p.command {
        case "fix", "proofread", "tone", "reply", "tl":
            let source = p.context.isEmpty ? p.prompt : p.context
            return OnDeviceModel.clampedContext(source)
        default:
            return OnDeviceModel.clampedContext(p.prompt)
        }
    }

    // MARK: - Response validation
    //
    // A 3B model needs its structured output checked before it reaches a
    // renderer. Anything that fails validation throws an escalatable error,
    // so the request lands on the cloud tier instead of surfacing broken
    // output — and the discarded on-device attempt cost nothing.

    static func postProcess(_ raw: String, command: String) throws -> CommandResult {
        let text = strippingFences(raw)
        guard !text.isEmpty else {
            throw ProviderError.badResponse("On-device model returned nothing")
        }

        switch command {
        case "reply":
            var items = parseSuggestionsJSON(text)
            // The shared parser falls back to `[raw]` when the JSON doesn't
            // decode. A small model often answers with one reply per line
            // instead of an array, so try that before giving up.
            if items.count <= 1 {
                let lines = text
                    .components(separatedBy: .newlines)
                    .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: " -*•\"'0123456789.")) }
                    .filter { !$0.isEmpty }
                if lines.count >= 2 { items = Array(lines.prefix(3)) }
            }
            guard items.count >= 2 else {
                throw ProviderError.badResponse("On-device model didn't return usable replies")
            }
            return .suggestions(items)

        case "poll":
            // The schema guarantees the shape, not the substance — it can
            // still hand back a blank question or empty option labels. A
            // poll that thin is worth one cloud retry rather than a POST.
            guard let data = text.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let question = (obj["question"] as? String)?
                      .trimmingCharacters(in: .whitespacesAndNewlines),
                  !question.isEmpty else {
                throw ProviderError.badResponse("On-device model didn't return a usable poll")
            }
            let options = ((obj["options"] as? [Any]) ?? []).compactMap { raw -> String? in
                let s = (raw as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                return s.isEmpty ? nil : s
            }
            guard options.count >= 2 else {
                throw ProviderError.badResponse("On-device poll had fewer than two options")
            }
            guard let normalized = try? JSONSerialization.data(
                      withJSONObject: ["question": question, "options": options]),
                  let json = String(data: normalized, encoding: .utf8) else {
                throw ProviderError.badResponse("Could not encode the on-device poll")
            }
            return .text(json)

        case "org":
            // Must decode into what `OrgImageRenderer` expects, or the
            // keyboard would show "Layout render failed".
            guard let data = text.data(using: .utf8),
                  let doc = try? JSONDecoder().decode(OrgDocument.self, from: data),
                  !doc.blocks.isEmpty else {
                throw ProviderError.badResponse("On-device layout JSON was not renderable")
            }
            return .text(text)

        default:
            return .text(unwrappingQuotes(text))
        }
    }

    /// Small models wrap output in markdown fences even when told not to.
    static func strippingFences(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard s.hasPrefix("```") else { return s }
        // Drop the opening fence line (which may carry a language tag) and a
        // trailing fence if present.
        if let firstNewline = s.firstIndex(of: "\n") {
            s = String(s[s.index(after: firstNewline)...])
        } else {
            s = String(s.dropFirst(3))
        }
        if let fence = s.range(of: "```", options: .backwards) {
            s = String(s[s.startIndex..<fence.lowerBound])
        }
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Strip a single pair of wrapping quotes — a common artefact when the
    /// prompt says "return only the corrected text".
    static func unwrappingQuotes(_ text: String) -> String {
        guard text.count >= 2 else { return text }
        let pairs: [(Character, Character)] = [("\"", "\""), ("“", "”"), ("'", "'")]
        for (open, close) in pairs where text.first == open && text.last == close {
            let inner = String(text.dropFirst().dropLast())
            // Only unwrap when the quotes really are wrapping the whole
            // string, not when the body itself contains the delimiter.
            if !inner.contains(close) { return inner }
        }
        return text
    }

    // MARK: - Error mapping

    static func map(_ error: LanguageModelSession.GenerationError) -> ProviderError {
        switch error {
        case .exceededContextWindowSize:
            return .badResponse("Text is too long for the on-device model")
        case .assetsUnavailable:
            return .onDeviceUnavailable("Apple Intelligence assets aren't ready yet")
        case .unsupportedLanguageOrLocale:
            return .badResponse("The on-device model doesn't support this language")
        case .rateLimited, .concurrentRequests:
            return .badResponse("On-device model is busy")
        case .unsupportedGuide, .decodingFailure:
            return .badResponse("On-device model returned an unusable response")

        // Content decisions, not capability failures. These deliberately do
        // NOT escalate: quietly forwarding text the on-device model declined
        // would ship it to a third party, which is exactly the trade the
        // privacy invariant exists to prevent. The user is told they can pick
        // Cloud mode in Personalization if they want it sent.
        case .guardrailViolation:
            return .onDeviceRefused("On-device model declined this text")
        case .refusal:
            return .onDeviceRefused("On-device model declined this request")

        @unknown default:
            return .badResponse("On-device model failed")
        }
    }
}
#endif
