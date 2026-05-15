import Foundation
#if os(iOS)
import UIKit

// MARK: - WebIntegration
//
// Mirrors android/web/WebIntegration. Ships `/web <url-or-query>` — the
// command bar collects the prompt, this handler mounts a full-panel
// WKWebView above the keys via `IntegrationContext.showPanel(...)`.
//
// No persistence, no AI, no chip — the simplest module shape.

final class WebIntegration: KeyboardIntegration {

    let id = "web"

    private static let emptyBannerMs = 1_500

    func commands() -> [CommandSpec] {
        [
            CommandSpec(
                name: "web", label: "Web", emoji: "🌐", needsPrompt: true,
                handler: { prompt, ctx in Self.handle(prompt: prompt, ctx: ctx) }
            ),
        ]
    }

    static func handle(prompt: String, ctx: IntegrationContext) {
        let trimmed = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            ctx.showBanner("Try /web wikipedia.org", autoHideMs: emptyBannerMs)
            return
        }
        guard let url = resolveURL(trimmed) else {
            ctx.showBanner("Couldn't parse that URL", autoHideMs: emptyBannerMs)
            return
        }

        let panel = WebPanelView()
        panel.setHandlers(
            onClose: { [weak ctx] in ctx?.hidePanel() },
            onOpenExternal: { [weak ctx] externalURL in
                ctx?.openExternalURL(externalURL)
            }
        )
        ctx.showPanel(panel)
        panel.load(url: url)
    }

    /// URL-ish (contains a dot, no spaces) → load directly; otherwise Google search.
    /// Matches android/web/WebIntegration.resolveUrl.
    private static func resolveURL(_ input: String) -> URL? {
        let looksLikeURL = input.contains(".") && !input.contains(" ")
        if looksLikeURL {
            let withScheme = (input.hasPrefix("http://") || input.hasPrefix("https://"))
                ? input
                : "https://\(input)"
            return URL(string: withScheme)
        }
        let encoded = input.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? input
        return URL(string: "https://www.google.com/search?q=\(encoded)")
    }

}
#endif
