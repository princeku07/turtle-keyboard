import Foundation

// MARK: - Requests

struct CommandRequest: Encodable {
    let command: String
    let prompt: String
    let context: String   // text before the slash (used by /fix, /tone, /reply, /tl)
    let locale: String
}

struct AnonymousAuthRequest: Encodable {
    let deviceId: String
}

// MARK: - Responses

struct AuthResponse: Decodable {
    let token: String
}

struct CommandResponse: Decodable {
    let type: OutputType
    let payload: String        // text to insert, image URL, or JSON-encoded [String] for suggestions
    let generationId: String

    enum OutputType: String, Decodable {
        case text
        case image
        case suggestions
    }
}

// MARK: - Errors

enum APIError: LocalizedError {
    case rateLimit
    case unauthorized
    case noFullAccess
    case server(Int)
    case network(URLError)
    case unknown(Error)

    var errorDescription: String? {
        switch self {
        case .rateLimit:     return "Daily limit reached — upgrade to Pro"
        case .unauthorized:  return "Session expired — reinstall to reset"
        case .noFullAccess:  return "Enable Full Access in Settings → Keyboard"
        case .server(let c): return "Server error (\(c)) — try again"
        case .unknown(let e): return e.localizedDescription
        case .network(let e):
            switch e.code {
            case .notConnectedToInternet:   return "No internet connection"
            case .timedOut:                 return "Request timed out — try again"
            case .cannotFindHost,
                 .cannotConnectToHost:      return "Backend unreachable — check Gemini API key"
            default:                        return "Network error: \(e.localizedDescription)"
            }
        }
    }
}
