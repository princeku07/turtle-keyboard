import Foundation
#if os(iOS)
import UIKit

/// Owns the full `/wyr` (Would You Rather) flow:
///   1. Load `commands/prompts/wyr.txt`.
///   2. Ask the LLM to produce `{questions: [{a, b}]}` JSON.
///   3. POST to `turtle-worker` `/wyr` via `WyrClient`.
///   4. Commit the returned shareable App Link URL.
///
/// Mirrors Android's `WyrIntegration`. Empty user prompt is allowed — the
/// system prompt covers "any theme".
final class WyrIntegration: KeyboardIntegration {

    let id = "wyr"

    static let routeKey = "wyr"

    private static let failBannerMs = 2_500

    func commands() -> [CommandSpec] {
        [
            CommandSpec(
                name: "wyr", label: "Would you rather", emoji: "🤔", needsPrompt: false,
                handler: { prompt, ctx in Self.handle(prompt: prompt, ctx: ctx) }
            ),
        ]
    }

    static func handle(prompt: String, ctx: IntegrationContext) {
        let themeHint = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let userPrompt = themeHint.isEmpty ? "Generate a varied set." : themeHint

        guard let systemPrompt = PromptLoader.load(id: "wyr"), !systemPrompt.isEmpty else {
            ctx.showBanner("WYR prompt missing — clean rebuild needed",
                           autoHideMs: failBannerMs)
            return
        }

        ctx.showBusy("Creating game…")

        let llmPrompt = systemPrompt + "\n\nTheme:\n" + userPrompt
        ctx.llm.complete(
            prompt: llmPrompt,
            onText: { text in handleModelText(rawJson: text, ctx: ctx) },
            onError: { reason in
                DispatchQueue.main.async {
                    ctx.showBanner("Game failed: \(reason)", autoHideMs: failBannerMs)
                }
            }
        )
    }

    private static func handleModelText(rawJson: String, ctx: IntegrationContext) {
        let stripped = stripCodeFences(rawJson)
        guard let data = stripped.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let qsRaw = obj["questions"] as? [[String: Any]] else {
            DispatchQueue.main.async {
                ctx.showBanner("Couldn't shape that game — try a clearer prompt",
                               autoHideMs: failBannerMs)
            }
            return
        }
        let questions: [WyrClient.Question] = qsRaw.compactMap { q in
            let a = (q["a"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let b = (q["b"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !a.isEmpty, !b.isEmpty else { return nil }
            return WyrClient.Question(a: a, b: b)
        }
        guard questions.count >= 2 else {
            DispatchQueue.main.async {
                ctx.showBanner("Couldn't shape that game — try a clearer prompt",
                               autoHideMs: failBannerMs)
            }
            return
        }

        Task {
            do {
                let result = try await WyrClient.create(questions: questions)
                await MainActor.run { ctx.commitText(result.url) }
            } catch {
                await MainActor.run {
                    ctx.showBanner("Couldn’t create the game. Please try again.", autoHideMs: failBannerMs)
                }
            }
        }
    }

    private static func stripCodeFences(_ s: String) -> String {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("```") {
            if let firstNl = t.firstIndex(of: "\n") {
                t = String(t[t.index(after: firstNl)...])
            }
            if let closing = t.range(of: "```", options: .backwards) {
                t = String(t[..<closing.lowerBound])
            }
        }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
#endif
