import Foundation
#if os(iOS)
import UIKit

/// Commands-only Notion integration. Contributes `/notion <prompt>` and
/// `/note <prompt>` (alias) — both create a Notion page under the user's
/// chosen parent. Mirrors Android's `NotionIntegration`.
///
/// Behavior is fire-and-forget:
///   1. User taps Send on `/notion <prompt>`.
///   2. Banner flashes "Creating Notion page…".
///   3. Background: LLM structures the prompt → Notion API creates the page.
///   4. Banner flashes the result (success copies the page URL to clipboard).
///
/// If the user hasn't completed OAuth + parent picking, the handler shows
/// a banner pointing them to the host app's "Connect Notion" screen.
final class NotionIntegration: KeyboardIntegration {

    let id = "notion"

    func commands() -> [CommandSpec] {
        [
            CommandSpec(
                name: "notion", label: "Notion page", emoji: "📓", needsPrompt: true,
                handler: { prompt, ctx in Self.handle(prompt: prompt, ctx: ctx) }
            ),
            CommandSpec(
                name: "note", label: "Notion page", emoji: "📓", needsPrompt: true,
                handler: { prompt, ctx in Self.handle(prompt: prompt, ctx: ctx) }
            ),
        ]
    }

    static func handle(prompt: String, ctx: IntegrationContext) {
        let store = ctx.store
        let token = store.string(forKey: NotionKeys.accessToken, fallback: "")
        guard !token.isEmpty else {
            ctx.showBanner("Connect Notion in the Turtle app", autoHideMs: 1800)
            ctx.openScreen("notion-connect")
            return
        }
        let parent = store.string(forKey: NotionKeys.defaultParent, fallback: "")
        guard !parent.isEmpty else {
            ctx.showBanner("Pick a Notion parent page in the Turtle app", autoHideMs: 1800)
            ctx.openScreen("notion-connect")
            return
        }
        let trimmed = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            ctx.showBanner("Type something after /notion", autoHideMs: 1500)
            return
        }

        ctx.showBusy("📓 Creating Notion page…")
        let llm = ctx.llm

        NotionLlmBridge.structure(userPrompt: trimmed, llm: llm) { result in
            switch result {
            case .failure(let error):
                ctx.showBanner("⚠️ Notion: \(error.localizedDescription)", autoHideMs: 2500)
            case .success(let parsed):
                Task {
                    do {
                        let (pageId, pageURL) = try await NotionClient.createPage(
                            accessToken: token,
                            parentPageId: parent,
                            title: parsed.title,
                            blocks: parsed.blocks
                        )
                        let url = pageURL ?? canonicalURL(pageId: pageId)
                        await MainActor.run {
                            UIPasteboard.general.string = url
                            ctx.showBanner("📓 Page created — link copied", autoHideMs: 2000)
                        }
                    } catch {
                        await MainActor.run {
                            ctx.showBanner("⚠️ Notion: \(error.localizedDescription)", autoHideMs: 2500)
                        }
                    }
                }
            }
        }
    }

    private static func canonicalURL(pageId: String) -> String {
        // Notion's web URL strips dashes from the id.
        let stripped = pageId.replacingOccurrences(of: "-", with: "")
        return "https://www.notion.so/\(stripped)"
    }
}
#endif
