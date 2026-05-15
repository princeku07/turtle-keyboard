import Foundation

// MARK: - Provider ID

enum ProviderID: String, Codable, CaseIterable {
    case fal
    case anthropic
    case google
    case openai
    case lmstudio
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
}

// MARK: - Command result  (provider → keyboard)

enum CommandResult {
    case text(String)               // insert directly into field
    case image(String)              // URL — keyboard downloads + puts to clipboard
    case suggestions([String])      // /reply — 3 tappable chips
}

// MARK: - Provider protocol
// Implement this to add a new AI provider. Register it in CommandRouter.

protocol AIProvider {
    var id: ProviderID { get }
    func execute(_ payload: CommandPayload) async throws -> CommandResult
}

// MARK: - Provider errors

enum ProviderError: LocalizedError {
    case missingAPIKey(ProviderID)
    case unsupportedCommand(String)
    case badResponse(String)
    case http(Int)
    case network(URLError)
    case unknown(Error)

    var errorDescription: String? {
        switch self {
        case .missingAPIKey(let p):
            return "No API key for \(p.rawValue) — add it in Settings"
        case .unsupportedCommand(let c):
            return "/\(c) is not supported by the selected model"
        case .badResponse(let msg):
            return "Unexpected response: \(msg)"
        case .http(let code):
            return "HTTP \(code) from AI provider — try again"
        case .network(let e):
            switch e.code {
            case .notConnectedToInternet: return "No internet connection"
            case .timedOut:               return "Request timed out — try again"
            case .cannotFindHost,
                 .cannotConnectToHost:    return "Cannot reach AI provider"
            default:                      return e.localizedDescription
            }
        case .unknown(let e):
            return e.localizedDescription
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
