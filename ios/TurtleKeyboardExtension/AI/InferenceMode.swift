import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

// MARK: - InferenceMode
//
// The user's answer to "where is my text allowed to go?". Written by the host
// app's Personalization screen, read by the keyboard's `CommandRouter` before
// it walks a command's tier plan.
//
// This file is compiled into BOTH targets — the host app needs it to render
// the picker, the extension needs it to route. Keep it free of any dependency
// the host app doesn't already have (`SplitStore` and `PersonalizationKeys`
// are both already shared); anything that touches `ProviderID` or the AI
// provider stack belongs in `InferenceTier.swift`, which is extension-only.

enum InferenceMode: String, CaseIterable {
    /// Cheapest capable tier first, escalating on failure. Default.
    case auto
    /// Never touch the network for inference. Commands with no local path
    /// fail with a clear message instead of silently going to the cloud.
    case onDeviceOnly
    /// Always use the cloud model, even where a local path exists.
    case cloudOnly

    static let storageKey = PersonalizationKeys.inferenceMode
    static let fallback: InferenceMode = .auto

    var title: String {
        switch self {
        case .auto:         return "Auto"
        case .onDeviceOnly: return "On-device"
        case .cloudOnly:    return "Cloud"
        }
    }

    var blurb: String {
        switch self {
        case .auto:
            return "Runs each command on the cheapest thing that can handle it — offline text tools first, then Apple's on-device model, then the cloud. Images and /search always need the cloud."
        case .onDeviceOnly:
            return "Nothing leaves your phone. Text commands use Apple's on-device model or Turtle's offline tools; image commands and /search will tell you they need the cloud."
        case .cloudOnly:
            return "Always use the cloud model, even for commands the phone could handle for free."
        }
    }

    static func current(store: SplitStore) -> InferenceMode {
        let raw = store.string(forKey: storageKey, fallback: fallback.rawValue)
        return InferenceMode(rawValue: raw) ?? fallback
    }

    static func set(_ mode: InferenceMode, store: SplitStore) {
        store.setString(mode.rawValue, forKey: storageKey)
    }
}

// MARK: - On-device model availability

/// Why the on-device tier can't run, phrased for the keyboard's banner.
enum OnDeviceAvailability: Equatable {
    case available
    case unavailable(String)

    var isAvailable: Bool { self == .available }

    var reason: String? {
        if case .unavailable(let why) = self { return why }
        return nil
    }
}

enum OnDeviceModel {

    /// Live availability of Apple's on-device foundation model. Cheap to call
    /// — it reads cached system state, it does not load a model — so callers
    /// query it per request rather than caching a stale answer. The user can
    /// enable Apple Intelligence, or finish the model download, while the
    /// keyboard is mounted.
    static var availability: OnDeviceAvailability {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            switch SystemLanguageModel.default.availability {
            case .available:
                return .available
            case .unavailable(let reason):
                switch reason {
                case .deviceNotEligible:
                    return .unavailable("This device doesn't support Apple Intelligence")
                case .appleIntelligenceNotEnabled:
                    return .unavailable("Turn on Apple Intelligence in Settings")
                case .modelNotReady:
                    return .unavailable("Apple Intelligence is still downloading")
                @unknown default:
                    return .unavailable("Apple Intelligence is unavailable")
                }
            @unknown default:
                return .unavailable("Apple Intelligence is unavailable")
            }
        }
        return .unavailable("On-device AI needs iOS 26 or later")
        #else
        // Built against an SDK with no FoundationModels — this binary can
        // never run the on-device tier.
        return .unavailable("This build has no on-device AI support")
        #endif
    }

    /// The on-device model's context window is a few thousand tokens and
    /// overrunning it throws `exceededContextWindowSize`. Field text is
    /// clamped to this many characters so a long thread degrades gracefully
    /// instead of erroring straight into a cloud escalation.
    static let maxContextCharacters = 4_000

    static func clampedContext(_ text: String) -> String {
        guard text.count > maxContextCharacters else { return text }
        // Keep the tail — the most recent text is what the user is acting on.
        return String(text.suffix(maxContextCharacters))
    }
}
