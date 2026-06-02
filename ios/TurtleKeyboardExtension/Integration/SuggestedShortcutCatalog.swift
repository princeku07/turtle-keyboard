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

// MARK: - ContextualSuggester
//
// The "discovery" layer: surfaces a high-value slash command based on what the
// user has ALREADY typed in the field, turning dormant commands into a daily
// habit. Deliberately conservative — one or two chips, only on strong signals,
// never in sensitive fields. Reads field text only (no clipboard: that trips
// the iOS paste banner and is privacy-sensitive). The returned shortcuts carry
// the command name in `name`; tapping one opens that command's bar via the
// slash buffer (the keyboard routes `.contextualCommand` taps there), so
// `template` / `needsPrompt` are unused here.
enum ContextualSuggester {

    static func suggestions(fieldText: String, kind: FieldKind) -> [SuggestedShortcut] {
        guard kind != .sensitive else { return [] }
        let text = fieldText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !text.hasPrefix("/") else { return [] }

        let lower = text.lowercased()
        let words = text.split(whereSeparator: { $0 == " " || $0 == "\n" }).map(String.init)
        let wordCount = words.count
        // Punctuation-stripped, lowercased word set for keyword matching, so a
        // trailing comma / period doesn't hide "poll," or "translate.".
        let strip = CharacterSet.alphanumerics.inverted
        let wordSet = Set(words.map { $0.lowercased().trimmingCharacters(in: strip) })
        let firstWord = words.first?.lowercased().trimmingCharacters(in: strip) ?? ""
        let looksLikeLinkOrCode = lower.contains("http") || lower.contains("://") || lower.contains("www.")

        var out: [SuggestedShortcut] = []
        func add(_ name: String, _ label: String, _ emoji: String) {
            guard !out.contains(where: { $0.name == name }) else { return }
            out.append(chip(name, label, emoji))
        }

        // — Specific, high-confidence intents first (most relevant) —

        // GitHub repo reference → pull its info.
        if lower.contains("github.com/") { add("github", "Look up repo", "🐙") }

        // "would you rather …" → run a WYR round.
        if lower.contains("would you rather") { add("wyr", "Play WYR", "🤔") }

        // Translation intent.
        if wordSet.contains("translate") || wordSet.contains("translation") {
            add("tl", "Translate", "🌐")
        }

        // Note / todo capture → save to Notion.
        if lower.hasPrefix("note:") || lower.hasPrefix("todo") || lower.hasPrefix("to-do")
            || lower.hasPrefix("remember to") {
            add("note", "Save note", "📓")
        }

        // Poll / vote intent.
        if wordSet.contains("poll") || wordSet.contains("polls")
            || wordSet.contains("vote") || wordSet.contains("voting") {
            add("poll", "Make poll", "📊")
        }

        // A factual question the user composed → answer it inline. Gated on a
        // leading question word + "?" + length so casual "you good?" is skipped.
        let questionWords: Set<String> = ["what","whats","how","why","when","who",
                                          "which","where","is","are","can","could",
                                          "does","do","should","will"]
        if text.hasSuffix("?"), wordCount >= 4, questionWords.contains(firstWord) {
            add("ask", "Ask AI", "❓")
        }

        // A numeric field with a typed amount → split it.
        if kind == .numeric, text.rangeOfCharacter(from: .decimalDigits) != nil {
            add("split", "Split", "💸")
        }

        // — General polish for any substantial message (fills the rest) —
        if wordCount >= 7, !looksLikeLinkOrCode {
            add("proofread", "Proofread", "📝")
            add("tone", "Tone", "🎭")
        }

        // Three chip slots in the strip.
        return Array(out.prefix(3))
    }

    private static func chip(_ command: String, _ label: String, _ emoji: String) -> SuggestedShortcut {
        SuggestedShortcut(name: command, label: label, emoji: emoji, template: "", needsPrompt: false)
    }
}
#endif
