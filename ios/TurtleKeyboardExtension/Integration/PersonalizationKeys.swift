import Foundation

/// Personalization toggle keys, shared between the host app's
/// `PersonalizationViewController` (writer) and the keyboard extension's
/// integration registry (reader). Default for every key is "on" so a fresh
/// install gets every integration without the user having to opt in.
enum PersonalizationKeys {
    static let splitEnabled       = "personalization.split.enabled"
    static let notionEnabled      = "personalization.notion.enabled"
    static let slackEnabled       = "personalization.slack.enabled"
    static let pollEnabled        = "personalization.poll.enabled"
    static let wyrEnabled         = "personalization.wyr.enabled"
    static let webEnabled         = "personalization.web.enabled"
    static let githubEnabled      = "personalization.github.enabled"
    static let quickPanelEnabled  = "personalization.keyboard.quickpanel"
    /// User's drag-to-reorder ordering for the Quick Panel grid, stored as
    /// a comma-separated list of `SlashCommand` raw values. Commands not
    /// listed (e.g. ones added in a later build) fall in after the saved
    /// ones in their default order.
    static let quickPanelOrder    = "personalization.keyboard.quickpanel.order"
    static let voiceEnabled       = "personalization.keyboard.voice"
    /// Theme preference: "auto" | "turtle" | "light" | "dark". Source of truth
    /// owned by `KeyboardThemeManager.preferenceKey`; mirrored here so the
    /// host app and the keyboard can read/write through the same name.
    static let themePreference    = "personalization.theme.preference"
    /// Where AI commands are allowed to run: "auto" | "onDeviceOnly" |
    /// "cloudOnly". Read by `CommandRouter` before it walks a command's tier
    /// plan — see `InferenceMode` in `AI/InferenceTier.swift`.
    static let inferenceMode      = "personalization.inference.mode"

    /// Whether the integration with `id` is currently enabled. Defaults
    /// to true when the key has never been written.
    static func isEnabled(_ id: String, store: SplitStore) -> Bool {
        let key: String
        switch id {
        case "split":  key = splitEnabled
        case "notion": key = notionEnabled
        case "slack":  key = slackEnabled
        case "poll":   key = pollEnabled
        case "wyr":    key = wyrEnabled
        case "web":    key = webEnabled
        case "github": key = githubEnabled
        default: return true
        }
        return store.int(forKey: key, fallback: 1) != 0
    }
}
