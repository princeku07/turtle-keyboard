import Foundation
#if os(iOS)
import UIKit

/// Owns the full `/poll` flow:
///   1. Load the shared system prompt from `commands/prompts/poll.txt`.
///   2. Ask the keyboard's LLM to shape the user's terse prompt into
///      `{question, options}` JSON.
///   3. POST to `turtle-worker` `/poll` via `PollClient`.
///   4. Commit the returned shareable App Link URL into the host field.
///
/// Mirrors Android's `PollIntegration`. No round trip through `CommandRouter`;
/// owns its own prompt and dispatch.
final class PollIntegration: KeyboardIntegration {

    let id = "poll"

    /// URL route key — matches `https://www.turtlekeyboard.com/poll/<id>`.
    static let routeKey = "poll"

    private static let busyBannerMs = 30_000
    private static let failBannerMs = 2_500
    private static let emptyBannerMs = 2_200

    func commands() -> [CommandSpec] {
        [
            CommandSpec(
                name: "poll", label: "Poll", emoji: "📊", needsPrompt: true,
                handler: { prompt, ctx in Self.handle(prompt: prompt, ctx: ctx) }
            ),
        ]
    }

    static func handle(prompt: String, ctx: IntegrationContext) {
        let trimmed = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            ctx.showBanner("What's the poll? e.g. /poll best dinner spot",
                           autoHideMs: emptyBannerMs)
            return
        }
        guard let systemPrompt = PromptLoader.load(id: "poll"), !systemPrompt.isEmpty else {
            // Build-time copy of commands/prompts/poll.txt didn't happen.
            // Clean rebuild after wiring the Run Script's inputPaths/outputPaths fixes it.
            ctx.showBanner("Poll prompt missing — clean rebuild needed",
                           autoHideMs: failBannerMs)
            return
        }

        ctx.showBanner("Creating poll…", autoHideMs: busyBannerMs)

        let llmPrompt = systemPrompt + "\n\nUser message:\n" + trimmed
        ctx.llm.complete(
            prompt: llmPrompt,
            onText: { text in
                handleModelText(rawJson: text, ctx: ctx)
            },
            onError: { reason in
                DispatchQueue.main.async {
                    ctx.showBanner("Poll failed: \(reason)", autoHideMs: failBannerMs)
                }
            }
        )
    }

    /// Parses the model's JSON output and POSTs to the Worker.
    private static func handleModelText(rawJson: String, ctx: IntegrationContext) {
        let stripped = stripCodeFences(rawJson)
        guard let data = stripped.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            DispatchQueue.main.async {
                ctx.showBanner("Couldn't shape that into a poll — try a clearer prompt",
                               autoHideMs: failBannerMs)
            }
            return
        }
        let question = (obj["question"] as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let optsArr = (obj["options"] as? [Any]) ?? []
        let options: [String] = optsArr.compactMap { raw in
            let s = (raw as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return s.isEmpty ? nil : s
        }
        guard !question.isEmpty, options.count >= 2 else {
            DispatchQueue.main.async {
                ctx.showBanner("Couldn't shape that into a poll — try a clearer prompt",
                               autoHideMs: failBannerMs)
            }
            return
        }

        Task {
            do {
                let result = try await PollClient.createPoll(question: question, options: options)
                await MainActor.run { ctx.commitText(result.url) }
            } catch {
                let msg = error.localizedDescription
                await MainActor.run {
                    ctx.showBanner("Poll create failed: \(msg)", autoHideMs: failBannerMs)
                }
            }
        }
    }

    /// Even when told not to, models occasionally wrap JSON in ```json … ```.
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
