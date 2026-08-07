import Foundation

// MARK: - Provider ID

/// Backend identifier. One case per `ExecutionTier` — see `InferenceTier.swift`
/// for how the router picks between them.
enum ProviderID: String, Codable, CaseIterable {
    /// Gemini over HTTPS. Spends tokens; the only tier that can generate
    /// images or answer from current web knowledge.
    case google
    /// Apple's on-device foundation model (FoundationModels, iOS 26+).
    /// Free, offline, no tokens.
    case apple
    /// No model at all — `LocalTextEngine`'s deterministic text passes.
    case local

    /// Only cloud backends need a user-supplied key.
    var needsAPIKey: Bool { self == .google }
}

// MARK: - Model capability
// Used to validate that a model can handle a given command before routing.

enum ModelCapability: Hashable {
    case imageGeneration    // /cap
    case textEdit           // /fix, /tone
    case chat               // /reply
    case translation        // /tl
}

// MARK: - AI model metadata

struct AIModel: Identifiable, Equatable {
    let id: String                          // provider-native model ID
    let displayName: String
    let provider: ProviderID
    let capabilities: Set<ModelCapability>
    let isFree: Bool

    func supports(_ capability: ModelCapability) -> Bool {
        capabilities.contains(capability)
    }
}

// MARK: - Command payload  (router → provider)

struct CommandPayload {
    let command: String
    let model: AIModel
    let prompt: String      // text typed after command name  e.g. "formal" in /tone formal
    let context: String     // text before the slash  (the content to act on)
    let locale: String
    /// PNG bytes of a reference image used by image-edit commands (/edit).
    /// Nil for every other command. Mirrors Android's `InlineImage`.
    let referenceImage: Data?

    init(command: String, model: AIModel, prompt: String, context: String,
         locale: String, referenceImage: Data? = nil) {
        self.command = command
        self.model = model
        self.prompt = prompt
        self.context = context
        self.locale = locale
        self.referenceImage = referenceImage
    }
}

// MARK: - Command result  (provider → keyboard)

enum CommandResult {
    case text(String)               // insert directly into field
    case image(String)              // URL — keyboard downloads + puts to clipboard
    case imageData(Data)            // raw bytes (e.g. Gemini inline image response)
    case suggestions([String])      // /reply — 3 tappable chips
}

// MARK: - Provider protocol
// Implement this to add a new AI provider. Register it in CommandRouter.

protocol AIProvider {
    var id: ProviderID { get }
    func execute(_ payload: CommandPayload) async throws -> CommandResult

    /// Streaming variant for text commands. `onDelta` is called with each
    /// chunk of generated text as it arrives (off the main thread — the
    /// caller marshals to the UI). The returned `CommandResult` carries the
    /// full accumulated text for history/banner purposes.
    ///
    /// Providers that can't stream get the default below, which simply runs
    /// the one-shot `execute` and never fires `onDelta` — callers fall back
    /// to inserting the whole result at once.
    func executeStreaming(_ payload: CommandPayload,
                          onDelta: @escaping (String) -> Void) async throws -> CommandResult
}

extension AIProvider {
    func executeStreaming(_ payload: CommandPayload,
                          onDelta: @escaping (String) -> Void) async throws -> CommandResult {
        try await execute(payload)
    }
}

// MARK: - Provider errors

enum ProviderError: LocalizedError {
    case missingAPIKey(ProviderID)
    case unsupportedCommand(String)
    case badResponse(String)
    case http(Int)
    case network(URLError)
    case unknown(Error)

    // MARK: Tier-routing errors  (see InferenceTier.swift)

    /// The on-device model can't run here — no Apple Intelligence, model
    /// still downloading, or pre-iOS-26. Escalatable.
    case onDeviceUnavailable(String)
    /// The on-device model declined the content. Deliberately **not**
    /// escalatable — see `AppleOnDeviceProvider.map(_:)`.
    case onDeviceRefused(String)
    /// `LocalTextEngine` had nothing it was confident about. Escalatable.
    case noLocalImprovement
    /// The command has no non-cloud path but the user chose On-device mode.
    case requiresCloud(String)

    var errorDescription: String? {
        switch self {
        case .missingAPIKey:
            return "This AI service is temporarily unavailable"
        case .unsupportedCommand(let c):
            return "/\(c) is not supported by the selected model"
        case .badResponse:
            return "Turtle received an unexpected response. Please try again"
        case .onDeviceUnavailable(let why):
            return why
        case .onDeviceRefused(let why):
            return "\(why) — switch to Cloud in Personalization to send it"
        case .noLocalImprovement:
            return "Nothing to fix"
        case .requiresCloud(let c):
            return "/\(c) needs cloud AI — set Inference to Auto in Personalization"
        case .http:
            return "Turtle couldn’t complete that request. Please try again"
        case .network(let e):
            switch e.code {
            case .notConnectedToInternet: return "No internet connection"
            case .timedOut:               return "Request timed out — try again"
            case .cannotFindHost,
                 .cannotConnectToHost:    return "Cannot reach AI provider"
            default:                      return "Check your connection and try again"
            }
        case .unknown:
            return "Something went wrong. Please try again"
        }
    }
}

// MARK: - Shared response parsing helpers

func parseSuggestionsJSON(_ raw: String) -> [String] {
    let cleaned = raw
        .replacingOccurrences(of: "```json", with: "")
        .replacingOccurrences(of: "```", with: "")
        .trimmingCharacters(in: .whitespacesAndNewlines)
    if let data = cleaned.data(using: .utf8),
       let array = try? JSONDecoder().decode([String].self, from: data) {
        return Array(array.prefix(3))
    }
    return raw.isEmpty ? [] : [raw]
}
