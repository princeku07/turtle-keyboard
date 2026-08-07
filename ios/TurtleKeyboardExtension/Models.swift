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
        case .server:        return "Turtle couldn’t complete that request. Please try again."
        case .unknown:       return "Something went wrong. Please try again."
        case .network(let e):
            switch e.code {
            case .notConnectedToInternet:   return "No internet connection"
            case .timedOut:                 return "Request timed out — try again"
            case .cannotFindHost,
                 .cannotConnectToHost:      return "Turtle is temporarily unavailable"
            default:                        return "Check your connection and try again"
            }
        }
    }
}

// MARK: - Privacy-safe product telemetry

/// A small, bounded App Group event queue. Its API is intentionally an
/// allowlist: callers cannot attach arbitrary strings, which prevents prompts,
/// typed text, clipboard data, URLs, or generated output entering analytics.
/// A future uploader may drain `pendingEvents()` to the chosen analytics
/// backend without changing collection behavior.
enum PrivacySafeTelemetry {
    enum CommandCategory: String, Codable {
        case writing, image, knowledge, productivity, social, utility

        static func classify(_ command: String) -> Self {
            switch command {
            case "fix", "proofread", "tone", "reply", "tl": return .writing
            case "cap", "edit", "style", "sticker", "gif", "org": return .image
            case "ask", "search", "web": return .knowledge
            case "notion", "note", "github": return .productivity
            case "slack", "msg", "poll", "wyr": return .social
            default: return .utility
            }
        }
    }

    enum FailureCategory: String, Codable {
        case offline, timeout, authentication, unavailable, invalidResponse, cancelled, unknown
    }

    enum Integration: String, Codable { case google, notion, slack, github }

    struct Event: Codable, Equatable {
        enum Name: String, Codable {
            case onboardingStarted, onboardingCompleted, settingsOpened
            case keyboardHeartbeatDetected, firstCommandSucceeded, commandCompleted
            case commandFailed, integrationConnected, dailyActive
        }
        let name: Name
        let timestamp: TimeInterval
        let category: String?
        let durationMs: Int?
    }

    private static let suite = "group.com.samarth.turtlekeyboard.split"
    private static let eventsKey = "telemetry.pending.v1"
    private static let firstSuccessKey = "telemetry.firstSuccess.v1"
    private static let activeDayKey = "telemetry.activeDay.v1"
    private static let lock = NSLock()
    private static var commandStarts: [String: TimeInterval] = [:]

    static func onboardingStarted() { append(.init(name: .onboardingStarted, timestamp: now, category: nil, durationMs: nil)) }
    static func onboardingCompleted() { append(.init(name: .onboardingCompleted, timestamp: now, category: nil, durationMs: nil)) }
    static func settingsOpened() { append(.init(name: .settingsOpened, timestamp: now, category: nil, durationMs: nil)) }
    static func keyboardHeartbeatDetected() { append(.init(name: .keyboardHeartbeatDetected, timestamp: now, category: nil, durationMs: nil)) }

    static func commandStarted(_ command: String) {
        lock.lock(); defer { lock.unlock() }
        commandStarts[command] = now
    }

    static func commandSucceeded(_ command: String) {
        let duration = takeDuration(for: command)
        append(.init(name: .commandCompleted, timestamp: now,
                     category: CommandCategory.classify(command).rawValue, durationMs: duration))
        let defaults = store
        if !defaults.bool(forKey: firstSuccessKey) {
            defaults.set(true, forKey: firstSuccessKey)
            append(.init(name: .firstCommandSucceeded, timestamp: now,
                         category: CommandCategory.classify(command).rawValue, durationMs: duration))
        }
        markDailyActive()
    }

    static func commandFailed(_ command: String, category: FailureCategory) {
        append(.init(name: .commandFailed, timestamp: now,
                     category: category.rawValue, durationMs: takeDuration(for: command)))
        markDailyActive()
    }

    static func integrationConnected(_ integration: Integration) {
        append(.init(name: .integrationConnected, timestamp: now,
                     category: integration.rawValue, durationMs: nil))
    }

    static func pendingEvents() -> [Event] {
        guard let data = store.data(forKey: eventsKey) else { return [] }
        return (try? JSONDecoder().decode([Event].self, from: data)) ?? []
    }

    static func removePendingEvents() { store.removeObject(forKey: eventsKey) }

    private static func markDailyActive() {
        let day = Int(now / 86_400)
        guard store.integer(forKey: activeDayKey) != day else { return }
        store.set(day, forKey: activeDayKey)
        append(.init(name: .dailyActive, timestamp: now, category: nil, durationMs: nil))
    }

    private static func takeDuration(for command: String) -> Int? {
        lock.lock(); defer { lock.unlock() }
        guard let started = commandStarts.removeValue(forKey: command) else { return nil }
        return max(0, Int((now - started) * 1_000))
    }

    private static func append(_ event: Event) {
        lock.lock(); defer { lock.unlock() }
        var events = pendingEventsUnlocked()
        events.append(event)
        events = Array(events.suffix(200))
        if let data = try? JSONEncoder().encode(events) { store.set(data, forKey: eventsKey) }
    }

    private static func pendingEventsUnlocked() -> [Event] {
        guard let data = store.data(forKey: eventsKey) else { return [] }
        return (try? JSONDecoder().decode([Event].self, from: data)) ?? []
    }

    private static var store: UserDefaults { UserDefaults(suiteName: suite) ?? .standard }
    private static var now: TimeInterval { Date().timeIntervalSince1970 }
}
