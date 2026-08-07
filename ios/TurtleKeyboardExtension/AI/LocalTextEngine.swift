import Foundation
import UIKit

// MARK: - LocalTextEngine
//
// The `.deterministic` tier: no model, no network, no tokens. Pure Swift
// plus `UITextChecker` (the same system dictionary the keyboard already uses
// for word suggestions). Runs in well under a millisecond and works back to
// iOS 15, so it's the first thing `/fix` tries.
//
// Scope is deliberately narrow. This engine handles *orthography and
// mechanics* — spelling, spacing, punctuation placement, sentence
// capitalization. It does not attempt grammar, tone, or rewriting, and it
// reports `.noChange` rather than guessing when it has nothing confident to
// contribute. That's the signal `CommandRouter` uses to escalate to a real
// model, which is why the fast path never costs quality.

enum LocalFixOutcome {
    /// At least one confident correction was made.
    case corrected(String)
    /// Nothing the engine is confident about — caller should escalate.
    case noChange
}

enum LocalTextEngine {

    /// Commands the deterministic tier can serve at all. Anything outside
    /// this set must not appear with `.deterministic` in its tier plan.
    static let supportedCommands: Set<String> = ["fix"]

    // MARK: - Entry point

    /// - Parameter requireSpellingChange: when true (the normal path) the
    ///   engine only claims the request if it actually corrected a
    ///   misspelling. Mechanics-only cleanups (a double space, a stray space
    ///   before a comma) are too weak to justify skipping the model, so they
    ///   return `.noChange` and let the router escalate. The router flips
    ///   this to false for one final pass when every other tier is
    ///   unavailable, so `/fix` always returns *something* rather than an
    ///   error.
    static func fix(_ input: String, requireSpellingChange: Bool = true) -> LocalFixOutcome {
        let source = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !source.isEmpty else { return .noChange }

        let (spellChecked, spellingFixes) = correctSpelling(in: source)
        let cleaned = tidyMechanics(spellChecked)

        if requireSpellingChange && spellingFixes == 0 {
            return .noChange
        }
        guard cleaned != source else { return .noChange }
        return .corrected(cleaned)
    }

    // MARK: - Spelling

    /// Walks the string with `UITextChecker` and replaces misspellings with
    /// the system's top suggestion — but only where the substitution is
    /// safe. Chat text is full of names, handles, slang, and acronyms that a
    /// dictionary will happily "correct" into nonsense, so every candidate
    /// has to clear `isCorrectable` and a distance check first.
    private static func correctSpelling(in text: String) -> (String, Int) {
        let checker = UITextChecker()
        let language = preferredLanguage()

        var working = text
        var cursor = 0
        var fixes = 0

        // Hard iteration cap — the cursor always advances, but a malformed
        // range from the checker shouldn't be able to hang the keyboard.
        var guard_ = 0
        while guard_ < 200 {
            guard_ += 1

            let ns = working as NSString
            guard cursor < ns.length else { break }

            let misspelled = checker.rangeOfMisspelledWord(
                in: working,
                range: NSRange(location: 0, length: ns.length),
                startingAt: cursor,
                wrap: false,
                language: language)

            guard misspelled.location != NSNotFound,
                  misspelled.length > 0,
                  NSMaxRange(misspelled) <= ns.length else { break }

            let word = ns.substring(with: misspelled)

            guard isCorrectable(word),
                  !isInsideIdentifier(range: misspelled, in: ns),
                  let replacement = bestGuess(for: word, range: misspelled,
                                              in: working, language: language)
            else {
                cursor = NSMaxRange(misspelled)
                continue
            }

            working = ns.replacingCharacters(in: misspelled, with: replacement)
            fixes += 1
            cursor = misspelled.location + (replacement as NSString).length
        }

        return (working, fixes)
    }

    /// Words we refuse to touch: too short to correct safely, acronyms,
    /// anything carrying a digit or symbol.
    private static func isCorrectable(_ word: String) -> Bool {
        guard word.count >= 4 else { return false }
        guard word.allSatisfy({ $0.isLetter }) else { return false }
        // ALL CAPS reads as an acronym (LGTM, ETA, API) — leave it alone.
        if word == word.uppercased() { return false }
        return true
    }

    /// True when the word sits inside a URL, email, @handle, #hashtag, or
    /// snake_case identifier. `UITextChecker` tokenizes on letters, so it
    /// hands back "github" out of "github.com" with no indication of the
    /// surrounding punctuation — we have to look at the neighbours ourselves.
    private static func isInsideIdentifier(range: NSRange, in ns: NSString) -> Bool {
        let markers: Set<Character> = ["@", "/", ".", "#", "_", ":", "-", "\\"]

        if range.location > 0 {
            let before = Character(ns.substring(with: NSRange(location: range.location - 1, length: 1)))
            if markers.contains(before) { return true }
        }
        let end = NSMaxRange(range)
        if end < ns.length {
            let after = Character(ns.substring(with: NSRange(location: end, length: 1)))
            if markers.contains(after) { return true }
        }
        return false
    }

    /// First system guess that's a small enough edit to be believable, with
    /// the original word's capitalization reapplied.
    private static func bestGuess(for word: String, range: NSRange,
                                  in text: String, language: String) -> String? {
        let guesses = UITextChecker().guesses(forWordRange: range, in: text, language: language) ?? []
        // Tight budget for short words, slightly looser for long ones. Keeps
        // "teh"→"the" and "recieve"→"receive" while rejecting the wholesale
        // substitutions the dictionary offers for unknown proper nouns.
        let budget = word.count <= 5 ? 1 : 2

        for guess in guesses.prefix(4) {
            guard !guess.isEmpty else { continue }
            guard editDistance(word.lowercased(), guess.lowercased()) <= budget else { continue }
            return matchCase(of: word, applyingTo: guess)
        }
        return nil
    }

    /// Reapply the source word's capitalization. `UITextChecker` returns
    /// dictionary-cased suggestions, so "Teh" would come back "the".
    private static func matchCase(of original: String, applyingTo guess: String) -> String {
        guard let first = original.first, first.isUppercase else { return guess }
        return guess.prefix(1).uppercased() + guess.dropFirst()
    }

    /// Levenshtein distance, two-row DP. Small strings only — this runs on
    /// single words.
    private static func editDistance(_ a: String, _ b: String) -> Int {
        let x = Array(a), y = Array(b)
        if x.isEmpty { return y.count }
        if y.isEmpty { return x.count }

        var previous = Array(0...y.count)
        var current = [Int](repeating: 0, count: y.count + 1)

        for i in 1...x.count {
            current[0] = i
            for j in 1...y.count {
                let substitution = previous[j - 1] + (x[i - 1] == y[j - 1] ? 0 : 1)
                current[j] = min(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            previous = current
        }
        return previous[y.count]
    }

    private static func preferredLanguage() -> String {
        let available = UITextChecker.availableLanguages
        // Exact locale first ("en_GB"), then any variant of the language
        // ("en_*"), then whatever the system has.
        if available.contains(Locale.current.identifier) { return Locale.current.identifier }
        let code = Locale.current.languageCode ?? "en"
        if let variant = available.first(where: { $0.hasPrefix(code) }) { return variant }
        return available.first ?? "en_US"
    }

    // MARK: - Mechanics

    /// Whitespace, punctuation spacing, and sentence capitalization. Every
    /// rule here is unambiguous — nothing that changes meaning or voice.
    private static func tidyMechanics(_ text: String) -> String {
        var out = text

        // Collapse runs of spaces/tabs without touching newlines.
        out = out.replacingOccurrences(of: "[ \t]{2,}", with: " ",
                                       options: .regularExpression)
        // No space before closing punctuation.
        out = out.replacingOccurrences(of: "[ \t]+([,.!?;:)])", with: "$1",
                                       options: .regularExpression)
        // Exactly one space after sentence/clause punctuation when a word
        // follows. Skips decimals (3.5) and ellipses because the lookahead
        // requires a letter.
        out = out.replacingOccurrences(of: "([,;:])(?=[A-Za-z])", with: "$1 ",
                                       options: .regularExpression)
        out = out.replacingOccurrences(of: "([.!?])(?=[A-Za-z])", with: "$1 ",
                                       options: .regularExpression)
        // Standalone "i" → "I".
        out = out.replacingOccurrences(of: "\\bi\\b", with: "I",
                                       options: .regularExpression)
        // Trim trailing whitespace on each line.
        out = out.replacingOccurrences(of: "[ \t]+$", with: "",
                                       options: [.regularExpression])

        return capitalizeSentences(out)
    }

    /// Uppercase the first letter of the string and of anything following a
    /// sentence terminator.
    private static func capitalizeSentences(_ text: String) -> String {
        var chars = Array(text)
        var expectingSentenceStart = true

        for i in chars.indices {
            let c = chars[i]
            if c.isLetter {
                if expectingSentenceStart {
                    chars[i] = Character(String(c).uppercased())
                    expectingSentenceStart = false
                }
            } else if c == "." || c == "!" || c == "?" || c == "\n" {
                expectingSentenceStart = true
            } else if c.isNumber {
                expectingSentenceStart = false
            }
            // Spaces and other punctuation leave the flag untouched so
            // `"hello. world"` still capitalizes `world`.
        }
        return String(chars)
    }
}

// MARK: - LocalProvider
//
// Adapts `LocalTextEngine` to the `AIProvider` protocol so the router can
// treat the deterministic tier exactly like a model backend.

final class LocalProvider: AIProvider {
    let id: ProviderID = .local

    func execute(_ payload: CommandPayload) async throws -> CommandResult {
        try await run(payload, requireSpellingChange: true)
    }

    /// Last-resort pass used by `CommandRouter` when every other tier is
    /// unavailable: accept mechanics-only cleanups so `/fix` returns text
    /// instead of an error on a device with no Apple Intelligence and no
    /// network.
    func executeLenient(_ payload: CommandPayload) async throws -> CommandResult {
        try await run(payload, requireSpellingChange: false)
    }

    private func run(_ payload: CommandPayload,
                     requireSpellingChange: Bool) async throws -> CommandResult {
        guard LocalTextEngine.supportedCommands.contains(payload.command) else {
            throw ProviderError.unsupportedCommand(payload.command)
        }

        let source = payload.context.isEmpty ? payload.prompt : payload.context

        // `UITextChecker` is UIKit. It's sub-millisecond on a chat-length
        // string, so hopping to the main actor is cheaper than reasoning
        // about its thread-safety.
        let outcome = await MainActor.run {
            LocalTextEngine.fix(source, requireSpellingChange: requireSpellingChange)
        }

        switch outcome {
        case .corrected(let text):
            return .text(text)
        case .noChange:
            throw ProviderError.noLocalImprovement
        }
    }
}
