import Foundation
#if os(iOS)
import UIKit

// MARK: - ThemePreference

/// What the user picked. Persisted to the App Group's UserDefaults so the
/// host app's Personalization screen (writer) and the keyboard extension
/// (reader) stay in sync.
enum ThemePreference: String, CaseIterable {
    case auto
    case turtle
    case light
    case dark

    var displayName: String {
        switch self {
        case .auto:   return "Auto (Match System)"
        case .turtle: return "Turtle"
        case .light:  return "Light"
        case .dark:   return "Dark"
        }
    }
}

// MARK: - KeyboardThemeManager
//
// Resolves the active KeyboardTheme from the user's preference + the
// system trait collection. Broadcasts changes via NotificationCenter so
// the keyboard view controller can rebuild without polling.

final class KeyboardThemeManager {

    static let shared = KeyboardThemeManager()

    /// Posted (no userInfo) whenever the persisted preference changes.
    /// The keyboard view controller observes this and reapplies the theme.
    static let preferenceDidChange = Notification.Name("turtle.keyboard.theme.changed")

    /// UserDefaults key (also exported via PersonalizationKeys).
    static let preferenceKey = "personalization.theme.preference"

    /// Last cached system style — used as a fallback when no view supplies
    /// a live trait collection. Refreshed on every `resolve(for:)` call.
    private var lastSystemStyle: UIUserInterfaceStyle = .light

    private init() {}

    // MARK: - Persistence

    func preference(store: SplitStore) -> ThemePreference {
        let raw = store.string(forKey: Self.preferenceKey, fallback: ThemePreference.turtle.rawValue)
        return ThemePreference(rawValue: raw) ?? .turtle
    }

    func setPreference(_ pref: ThemePreference, store: SplitStore) {
        store.setString(pref.rawValue, forKey: Self.preferenceKey)
        NotificationCenter.default.post(name: Self.preferenceDidChange, object: nil)
    }

    // MARK: - Resolution

    /// Resolve the active theme from the stored preference + a live trait
    /// collection (so `.auto` follows the system Dark Mode toggle).
    func resolve(store: SplitStore, traitCollection: UITraitCollection?) -> KeyboardTheme {
        if let style = traitCollection?.userInterfaceStyle, style != .unspecified {
            lastSystemStyle = style
        }
        switch preference(store: store) {
        case .auto:
            return lastSystemStyle == .dark ? .dark : .light
        case .turtle:
            return .turtle
        case .light:
            return .light
        case .dark:
            return .dark
        }
    }
}
#endif
