import Foundation

// MARK: - PromptLoader
//
// Loads system prompts from `commands/prompts/<id>.txt` — the cross-platform
// registry shared with Android. A Run Script build phase on the keyboard
// extension target copies the .txt files into the appex bundle, so we read
// them via Bundle.main at runtime.
//
// If a prompt file is missing for any reason (build phase not wired, file
// removed, etc.), callers fall back to the inline string in CommandRouter.

enum PromptLoader {
    static func load(id: String) -> String? {
        guard let url = Bundle.main.url(forResource: id, withExtension: "txt"),
              let raw = try? String(contentsOf: url, encoding: .utf8) else {
            return nil
        }
        return raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
