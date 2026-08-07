import Foundation

// MARK: - ExecutionTier
//
// Where a command's work actually happens. This is the "AI / non-AI"
// segregation the keyboard routes on: `CommandRouter` walks a command's tier
// plan cheapest-first and only escalates when the cheaper tier genuinely
// cannot serve the request.
//
//   .deterministic  No model at all — pure Swift + system text APIs
//                   (`LocalTextEngine`). Free, offline, sub-millisecond,
//                   works back to the iOS 15 deployment floor.
//   .onDevice       Apple's on-device foundation model (FoundationModels,
//                   iOS 26+). Free, offline, zero tokens, the text never
//                   leaves the phone. Hosted by a system daemon on the Neural
//                   Engine, so it doesn't count against the appex's own
//                   memory ceiling.
//   .cloud          Gemini over HTTPS. Spends tokens. The only tier that can
//                   generate images or answer from fresh web knowledge.
//
// The user-facing constraint on all of this is `InferenceMode`, which lives in
// `InferenceMode.swift` because the host app needs it too.

enum ExecutionTier: Int, CaseIterable, Comparable {
    case deterministic = 0
    case onDevice      = 1
    case cloud         = 2

    static func < (lhs: ExecutionTier, rhs: ExecutionTier) -> Bool {
        lhs.rawValue < rhs.rawValue
    }

    /// True when serving from this tier spends the user's cloud budget.
    var costsTokens: Bool { self == .cloud }

    /// Suffix appended to the completion banner so the user can always tell
    /// whether a result cost them anything. Cloud stays unmarked — it's the
    /// baseline the keyboard shipped with.
    var bannerSuffix: String {
        switch self {
        case .deterministic: return " · offline"
        case .onDevice:      return " · on-device"
        case .cloud:         return ""
        }
    }

    /// Short tier name for diagnostics. Not user-facing — the banner uses
    /// `bannerSuffix`.
    var debugName: String {
        switch self {
        case .deterministic: return "offline"
        case .onDevice:      return "on-device"
        case .cloud:         return "cloud"
        }
    }

    var providerID: ProviderID {
        switch self {
        case .deterministic: return .local
        case .onDevice:      return .apple
        case .cloud:         return .google
        }
    }

    init(provider: ProviderID) {
        switch provider {
        case .local:  self = .deterministic
        case .apple:  self = .onDevice
        case .google: self = .cloud
        }
    }

    /// Per-command tier preference, cheapest-first. A command whose plan omits
    /// a tier can never be served there.
    ///
    /// `/search` is cloud-only on purpose: it exists to answer from current web
    /// knowledge, which a frozen on-device model cannot do — serving it locally
    /// would return confident stale answers. Image commands are cloud-only
    /// because iOS exposes no headless on-device image generation (Image
    /// Playground is UI-only and can't run from an appex).
    static func plan(for command: String) -> [ExecutionTier] {
        switch command {
        // Spelling and mechanics is exactly what `/fix` is, and
        // `LocalTextEngine` does it in well under a millisecond. It reports
        // "no change" when the problem is grammatical rather than
        // orthographic, which escalates — so the fast path never costs
        // quality.
        case "fix":
            return [.deterministic, .onDevice, .cloud]

        // Pure text transforms. The on-device model handles these well and
        // they're the bulk of everyday keyboard use — this is where the token
        // saving actually comes from.
        case "proofread", "tone", "reply", "tl", "ask":
            return [.onDevice, .cloud]

        // `/org` must emit JSON that decodes into `OrgDocument`. The on-device
        // attempt is validated before it's returned (see
        // `AppleOnDeviceProvider.postProcess`), so a malformed layout escalates
        // to cloud instead of rendering garbage — and the discarded attempt
        // cost nothing.
        case "org":
            return [.onDevice, .cloud]

        // `/poll` only asks the model to shape a terse line into
        // `{question, options}` — no world knowledge, no long context. The
        // on-device attempt runs under guided generation
        // (`AppleOnDeviceProvider.OnDevicePollDraft`), so the schema is
        // enforced by constrained decoding rather than hoped for, and the
        // draft is validated before `PollClient` ever sees it.
        //
        // Note this tier plan covers the *inference* step only. Creating the
        // poll itself is a `PollClient` POST to the Worker — `/poll` is a
        // shareable artifact, so it reaches the network on every tier.
        case "poll":
            return [.onDevice, .cloud]

        case "search":
            return [.cloud]

        case "cap", "edit", "style", "sticker", "gif":
            return [.cloud]

        default:
            return [.cloud]
        }
    }
}

// MARK: - InferenceMode × ExecutionTier
//
// Lives here rather than beside `InferenceMode` because it needs
// `ExecutionTier`, which is extension-only (it references `ProviderID`).

extension InferenceMode {
    func allows(_ tier: ExecutionTier) -> Bool {
        switch self {
        case .auto:         return true
        case .onDeviceOnly: return !tier.costsTokens
        case .cloudOnly:    return tier == .cloud
        }
    }
}
