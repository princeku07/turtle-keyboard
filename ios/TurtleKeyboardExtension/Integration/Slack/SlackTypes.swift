import Foundation

/// Namespaced storage keys used by the Slack module against the shared
/// SplitStore. Mirror Android's `SlackKeys`.
enum SlackKeys {
    static let accessToken         = "slack.access_token"
    static let teamName            = "slack.team_name"
    static let teamDomain          = "slack.team_domain"
    static let defaultChannel      = "slack.default_channel_id"
    static let defaultChannelName  = "slack.default_channel_name"
    static let enabled             = "slack.enabled"
    /// Per-channel-name → id map prefix. Lookup with
    /// `"slack.channel_map.\(name.lowercased())"` so `#general` resolves.
    static let channelMapPrefix    = "slack.channel_map."
}

/// One Slack channel as returned from `users.conversations`.
struct SlackChannel {
    let id: String
    let name: String
    let isPrivate: Bool
}
