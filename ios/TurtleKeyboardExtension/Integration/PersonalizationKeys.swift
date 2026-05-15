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
    static let quickPanelEnabled  = "personalization.keyboard.quickpanel"
    static let voiceEnabled       = "personalization.keyboard.voice"

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
        default: return true
        }
        return store.int(forKey: key, fallback: 1) != 0
    }
}
