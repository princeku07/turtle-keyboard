import Foundation

/// Every slash command the keyboard exposes. Mirrors Android's
/// `command/SlashCommand`. Local commands route to the integration
/// registry; remote commands route through `CommandRouter` to the AI
/// backend.
enum SlashCommand: String {
    case cap    = "cap"
    case fix    = "fix"
    case tone   = "tone"
    case reply  = "reply"
    case tl     = "tl"
    case ask    = "ask"
    case org    = "org"
    case split  = "split"
    case splits = "splits"
    case notion = "notion"
    case note   = "note"
    case slack  = "slack"
    case msg    = "msg"

    var emoji: String {
        switch self {
        case .cap:           return "🎨"
        case .fix:           return "✏️"
        case .tone:          return "🎭"
        case .reply:         return "💬"
        case .tl:            return "🌐"
        case .ask:           return "❓"
        case .org:           return "📐"
        case .split:         return "💸"
        case .splits:        return "📜"
        case .notion, .note: return "📓"
        case .slack, .msg:   return "💬"
        }
    }

    var needsPrompt: Bool {
        switch self {
        case .cap, .tone, .tl, .ask, .org, .split,
             .notion, .note, .slack, .msg:        return true
        case .fix, .reply, .splits:               return false
        }
    }

    var buttonTitle: String {
        switch self {
        case .cap:               return "Generate"
        case .split:             return "Split"
        case .splits:            return "Open"
        case .notion, .note:     return "Create"
        case .slack, .msg:       return "Post"
        default:                 return "Send"
        }
    }

    /// Local commands run inside the keyboard with no AI round-trip.
    var isLocal: Bool {
        switch self {
        case .split, .splits, .notion, .note, .slack, .msg: return true
        default: return false
        }
    }

    /// Banner shown after a successful execution. Local commands surface
    /// their own progress banners; this is just for AI commands.
    var completionBanner: String {
        switch self {
        case .cap:           return "🎨 Image ready — long-press field to paste"
        case .fix:           return "✏️ Grammar fixed"
        case .tone:          return "🎭 Tone applied"
        case .reply:         return "💬 Reply inserted"
        case .tl:            return "🌐 Translated"
        case .ask:           return "❓ Answer inserted"
        case .org:           return "📐 Layout ready — long-press field to paste"
        case .split:         return "💸 Split ready"
        case .splits:        return "📜 Splits opened"
        case .notion, .note: return "📓 Notion"
        case .slack, .msg:   return "💬 Slack"
        }
    }
}
