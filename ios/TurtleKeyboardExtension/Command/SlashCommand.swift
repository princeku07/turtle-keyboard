import Foundation

/// Every slash command the keyboard exposes. Mirrors Android's
/// `command/SlashCommand`. Local commands route to the integration
/// registry; remote commands route through `CommandRouter` to the AI
/// backend.
enum SlashCommand: String, CaseIterable {
    case cap     = "cap"
    case edit    = "edit"
    case style   = "style"
    case sticker = "sticker"
    case gif     = "gif"
    case fix    = "fix"
    case proofread = "proofread"
    case tone   = "tone"
    case reply  = "reply"
    case tl     = "tl"
    case search = "search"
    case ask    = "ask"
    case org    = "org"
    case split  = "split"
    case splits = "splits"
    case notion = "notion"
    case note   = "note"
    case slack  = "slack"
    case msg    = "msg"
    case poll   = "poll"
    case wyr    = "wyr"
    case web    = "web"
    case github = "github"
    case history = "history"

    var emoji: String {
        switch self {
        case .cap:           return "🎨"
        case .edit:          return "🖌️"
        case .style:         return "✨"
        case .sticker:       return "🏷️"
        case .gif:           return "🎞️"
        case .fix:           return "✏️"
        case .proofread:     return "📝"
        case .tone:          return "🎭"
        case .reply:         return "💬"
        case .tl:            return "🌐"
        case .search:        return "🔍"
        case .ask:           return "❓"
        case .org:           return "📐"
        case .split:         return "💸"
        case .splits:        return "📜"
        case .notion, .note: return "📓"
        case .slack, .msg:   return "💬"
        case .poll:          return "📊"
        case .wyr:           return "🤔"
        case .web:           return "🌐"
        case .github:        return "🐙"
        case .history:       return "🖼️"
        }
    }

    var needsPrompt: Bool {
        switch self {
        case .cap, .edit, .style, .sticker, .gif, .tone, .tl, .search, .ask, .org, .split,
             .notion, .note, .slack, .msg, .poll, .web, .github:    return true
        case .fix, .reply, .splits, .wyr, .history, .proofread: return false
        }
    }

    var buttonTitle: String {
        switch self {
        case .cap:               return "Generate"
        case .edit, .style:      return "Apply"
        case .sticker:           return "Make"
        case .gif:               return "Animate"
        case .search:            return "Search"
        case .split:             return "Split"
        case .splits:            return "Open"
        case .notion, .note:     return "Create"
        case .slack, .msg:       return "Post"
        case .poll:              return "Create"
        case .wyr:               return "Play"
        case .web:               return "Open"
        case .github:            return "Fetch"
        case .history:           return "Open"
        default:                 return "Send"
        }
    }

    /// Local commands run inside the keyboard with no AI round-trip.
    var isLocal: Bool {
        switch self {
        case .split, .splits, .notion, .note, .slack, .msg,
             .poll, .wyr, .web, .github, .history: return true
        default: return false
        }
    }

    /// Commands that operate on a user-supplied source image. Both `/edit`
    /// and `/style` take a reference image (staged via the photo picker)
    /// and a free-form prompt — only the prompt's intent differs.
    var needsReferenceImage: Bool {
        switch self {
        case .edit, .style, .sticker, .gif: return true
        default:                            return false
        }
    }

    /// Text shown inside the generating-wave overlay while the AI
    /// request is in flight. Mirrors Android's per-command loading
    /// messages in `BuiltinAiCommands`.
    var loadingMessage: String {
        switch self {
        case .cap:           return "Generating image…"
        case .edit:          return "Editing image…"
        case .style:         return "Restyling image…"
        case .sticker:       return "Making sticker…"
        case .gif:           return "Animating…"
        case .fix:           return "Fixing text…"
        case .proofread:     return "Proofreading…"
        case .tone:          return "Adjusting tone…"
        case .reply:         return "Drafting reply…"
        case .tl:            return "Translating…"
        case .search:        return "Searching…"
        case .ask:           return "Thinking…"
        case .org:           return "Organizing…"
        default:             return "Working…"
        }
    }

    /// Banner shown after a successful execution. Local commands surface
    /// their own progress banners; this is just for AI commands.
    var completionBanner: String {
        switch self {
        case .cap:           return "🎨 Image ready — long-press field to paste"
        case .edit:          return "🖌️ Edit ready — long-press field to paste"
        case .style:         return "✨ Restyle ready — long-press field to paste"
        case .sticker:       return "🏷️ Sticker ready — long-press field to paste"
        case .gif:           return "🎞️ GIF ready — long-press field to paste"
        case .fix:           return "✏️ Grammar fixed"
        case .proofread:     return "📝 Proofread — text cleaned up"
        case .tone:          return "🎭 Tone applied"
        case .reply:         return "💬 Reply inserted"
        case .tl:            return "🌐 Translated"
        case .search:        return "🔍 Inserted"
        case .ask:           return "❓ Answer inserted"
        case .org:           return "📐 Layout ready — long-press field to paste"
        case .split:         return "💸 Split ready"
        case .splits:        return "📜 Splits opened"
        case .notion, .note: return "📓 Notion"
        case .slack, .msg:   return "💬 Slack"
        case .poll:          return "📊 Poll"
        case .wyr:           return "🤔 Game ready"
        case .web:           return "🌐 Web"
        case .github:        return "🐙 Commit inserted"
        case .history:       return "🖼️ History"
        }
    }
}
