import Foundation

/// Namespaced storage keys used by the Notion module against the shared
/// SplitStore. Mirror Android's `NotionKeys`.
enum NotionKeys {
    static let accessToken     = "notion.access_token"
    static let workspaceName   = "notion.workspace_name"
    static let defaultParent   = "notion.default_parent_id"
    static let defaultParentT  = "notion.default_parent_title"
    static let enabled         = "notion.enabled"
}

/// Top-level Notion page result from `POST /v1/search`. Used by the
/// connect screen to render the parent picker.
struct NotionPage {
    let id: String
    let title: String
}
