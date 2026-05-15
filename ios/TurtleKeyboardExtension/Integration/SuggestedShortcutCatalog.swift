import Foundation
#if os(iOS)

// MARK: - SuggestedShortcutCatalog
//
// Port of android/.../StaticSuggestedShortcutSource. iOS keys entries off
// `FieldKind` (derived from field traits) rather than Android's
// `EditorInfo.packageName`, since iOS keyboard extensions cannot see the
// host app id.
//
// Edits welcome — these are deliberately short, opinionated starters. The
// idea is "the user opens a fresh empty field and gets a useful first
// keystroke without typing"; entries that don't pay for their pixel slot
// should be cut, not piled onto.

enum SuggestedShortcutCatalog {

    static func shortcuts(for kind: FieldKind) -> [SuggestedShortcut] {
        switch kind {
        case .sensitive:
            return []

        case .email:
            return [
                .init(name: "followup", label: "Follow-up", emoji: "📧",
                      template: "Just following up on my previous email — ",
                      needsPrompt: false),
                .init(name: "thanks", label: "Thanks", emoji: "🙏",
                      template: "Thanks so much for ",
                      needsPrompt: false),
                .init(name: "intro", label: "Intro", emoji: "👋",
                      template: "Hi — quick intro: ",
                      needsPrompt: false),
            ]

        case .url, .search:
            return [
                .init(name: "site", label: "Site search", emoji: "🔎",
                      template: "site:", needsPrompt: false),
                .init(name: "filetype", label: "Filetype", emoji: "📄",
                      template: "filetype:pdf ", needsPrompt: false),
            ]

        case .numeric:
            // /split prompts for an amount + counterparties, so we just seed
            // the slash and let the existing command bar take over.
            return [
                .init(name: "split", label: "Split", emoji: "💸",
                      template: "/split ", needsPrompt: true),
            ]

        case .general:
            return [
                .init(name: "standup", label: "Standup", emoji: "🗓️",
                      template: "*Yesterday:* \n*Today:* \n*Blockers:* ",
                      needsPrompt: false),
                .init(name: "today", label: "Today", emoji: "📅",
                      template: "## Today\n- ",
                      needsPrompt: false),
                .init(name: "birthday", label: "Birthday", emoji: "🎂",
                      template: "Happy birthday! 🎉 Wishing you ",
                      needsPrompt: false),
                .init(name: "poll", label: "Poll", emoji: "📊",
                      template: "/poll ", needsPrompt: true),
            ]
        }
    }
}
#endif
