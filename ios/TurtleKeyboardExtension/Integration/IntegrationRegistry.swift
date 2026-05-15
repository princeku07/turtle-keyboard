import Foundation
#if os(iOS)
import UIKit

/// Central directory for every `KeyboardIntegration` the keyboard ships.
/// On iOS we cannot detect the host app, so the activation/session story
/// from Android is intentionally trimmed — the registry is just a lookup
/// table for slash commands today. Re-add `activate(...)` plumbing here
/// when iOS adds a way to detect host context (e.g. via field traits).
final class IntegrationRegistry {

    private let integrations: [KeyboardIntegration]
    private let commandIndex: [String: CommandSpec]

    init(_ integrations: [KeyboardIntegration], store: SplitStore? = nil) {
        // Honour the user's per-integration toggles set on the
        // Personalization screen. When a `store` is passed, integrations
        // whose `enabled` flag is `0` are dropped from the registry —
        // their commands disappear from the slash vocabulary and from
        // the Quick Panel grid.
        let filtered: [KeyboardIntegration]
        if let store = store {
            filtered = integrations.filter { PersonalizationKeys.isEnabled($0.id, store: store) }
        } else {
            filtered = integrations
        }
        self.integrations = filtered
        var idx: [String: CommandSpec] = [:]
        for integration in filtered {
            for cmd in integration.commands() {
                idx[cmd.name.lowercased()] = cmd
            }
        }
        self.commandIndex = idx
    }

    /// Resolve a slash-command name (e.g. `"split"`) to an integration-owned
    /// command spec, or nil if no integration owns it.
    func command(named name: String) -> CommandSpec? {
        commandIndex[name.lowercased()]
    }

    /// Snapshot of every command across every integration. Useful for the
    /// (future) Quick Panel grid.
    var allCommands: [CommandSpec] {
        integrations.flatMap { $0.commands() }
    }
}
#endif
