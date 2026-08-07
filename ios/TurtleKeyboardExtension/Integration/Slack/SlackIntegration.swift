import Foundation
#if os(iOS)
import UIKit

/// Commands-only Slack integration. Contributes `/slack` and `/msg` —
/// posts the user's message to their default channel (or a channel they
/// override with a leading `#name`). Mirrors Android's `SlackIntegration`.
///
/// Behavior is fire-and-forget:
///   1. User taps Send on `/slack <message>`.
///   2. Banner flashes "Sending to #channel…".
///   3. Background: `chat.postMessage`.
///   4. Banner shows the result. On success the permalink is copied to the
///      clipboard so the user can paste it anywhere.
final class SlackIntegration: KeyboardIntegration {

    let id = "slack"

    func commands() -> [CommandSpec] {
        [
            CommandSpec(
                name: "slack", label: "Slack", emoji: "💬", needsPrompt: true,
                handler: { prompt, ctx in Self.handle(prompt: prompt, ctx: ctx) }
            ),
            CommandSpec(
                name: "msg", label: "Slack message", emoji: "💬", needsPrompt: true,
                handler: { prompt, ctx in Self.handle(prompt: prompt, ctx: ctx) }
            ),
        ]
    }

    static func handle(prompt: String, ctx: IntegrationContext) {
        let store = ctx.store
        let token = store.string(forKey: SlackKeys.accessToken, fallback: "")
        guard !token.isEmpty else {
            ctx.showBanner("Connect Slack in the Turtle app", autoHideMs: 1800)
            ctx.openScreen("slack-connect")
            return
        }

        let resolved = resolveChannel(prompt: prompt, store: store)
        guard !resolved.channelId.isEmpty else {
            ctx.showBanner("Pick a Slack channel in the Turtle app", autoHideMs: 1800)
            ctx.openScreen("slack-connect")
            return
        }
        let body = resolved.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else {
            ctx.showBanner("Type a message after /slack", autoHideMs: 1500)
            return
        }

        ctx.showBusy("💬 Sending to #\(resolved.channelName)…")

        Task {
            do {
                let result = try await SlackClient.postMessage(
                    accessToken: token,
                    channelId: resolved.channelId,
                    text: body)
                await MainActor.run {
                    if let link = result.permalink, !link.isEmpty {
                        UIPasteboard.general.string = link
                        ctx.showBanner("💬 Posted to #\(resolved.channelName) — link copied",
                                       autoHideMs: 2000)
                    } else {
                        ctx.showBanner("💬 Posted to #\(resolved.channelName)",
                                       autoHideMs: 1500)
                    }
                }
            } catch {
                await MainActor.run {
                    ctx.showBanner("⚠️ Slack: \(error.localizedDescription)", autoHideMs: 2500)
                }
            }
        }
    }

    // MARK: - Channel resolution

    private struct Resolved {
        let channelId: String
        let channelName: String
        let text: String
    }

    /// Pull a leading `#name` or `<#CID|name>` token off `prompt`. If the
    /// name matches a known channel (cached at connect time), route there.
    /// Otherwise use the stored default.
    private static func resolveChannel(prompt: String, store: SplitStore) -> Resolved {
        let defaultId = store.string(forKey: SlackKeys.defaultChannel, fallback: "")
        let defaultName = store.string(forKey: SlackKeys.defaultChannelName, fallback: "")
        let trimmed = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return Resolved(channelId: defaultId, channelName: defaultName, text: "")
        }
        // <#CID|name> form (used when Slack inserts a channel mention)
        if trimmed.hasPrefix("<#"), let close = trimmed.firstIndex(of: ">") {
            let inside = String(trimmed[trimmed.index(trimmed.startIndex, offsetBy: 2)..<close])
            let cid: String
            let name: String
            if let pipe = inside.firstIndex(of: "|") {
                cid = String(inside[inside.startIndex..<pipe])
                name = String(inside[inside.index(after: pipe)...])
            } else {
                cid = inside
                name = inside
            }
            let body = String(trimmed[trimmed.index(after: close)...])
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return Resolved(channelId: cid, channelName: name, text: body)
        }
        // #channel-name form — only honored when the channel id is cached.
        if trimmed.hasPrefix("#") {
            let afterHash = trimmed.dropFirst()
            let firstSpace = afterHash.firstIndex(where: { $0.isWhitespace })
            let nameSlice = firstSpace.map { afterHash[afterHash.startIndex..<$0] } ?? afterHash
            let name = String(nameSlice).lowercased()
            let body = firstSpace.map {
                String(afterHash[afterHash.index(after: $0)...])
                    .trimmingCharacters(in: .whitespacesAndNewlines)
            } ?? ""
            let id = store.string(forKey: SlackKeys.channelMapPrefix + name, fallback: "")
            if !id.isEmpty {
                return Resolved(channelId: id, channelName: name, text: body)
            }
            // Unknown channel — fall through to default with prefix kept.
        }
        return Resolved(channelId: defaultId, channelName: defaultName, text: trimmed)
    }
}
#endif
