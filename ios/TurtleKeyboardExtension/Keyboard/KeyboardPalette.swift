import Foundation
#if os(iOS)
import UIKit

/// Color accessor used across the keyboard. Every read forwards to the
/// currently-active `KeyboardTheme` so theme switching only requires
/// updating `current` and triggering a rebuild — the call sites never
/// change.
///
/// `current` is owned by `KeyboardViewController`, which resolves it via
/// `KeyboardThemeManager` and pokes a rebuild whenever the user's
/// preference or the system Dark Mode setting flips.
enum KeyboardPalette {

    /// The theme every read below resolves against. Defaults to the brand
    /// `turtle` palette so views queried before the keyboard mounts still
    /// look right.
    static var current: KeyboardTheme = .turtle

    static var bg:        UIColor { current.bg }
    static var keyNormal: UIColor { current.keyNormal }
    static var keySpecial: UIColor { current.keySpecial }
    static var keyShiftOn: UIColor { current.keyShiftOn }
    static var barBg:     UIColor { current.barBg }
    static var bannerBg:  UIColor { current.bannerBg }
    static var keyText:   UIColor { current.keyText }
    static var keyTextSpecial: UIColor { current.keyTextSpecial }
    static var keyTextShiftOn: UIColor { current.keyTextShiftOn }
    static var accent:    UIColor { current.accent }
    static var chipBg:    UIColor { current.chipBg }
    static var chipText:  UIColor { current.chipText }
    static var barText:   UIColor { current.barText }
}

// MARK: - KeyboardHaptics
//
// Centralized haptic feedback for the keyboard, following APPLE_UI.md §7.
// The rules this enforces:
//   • Reserve haptics for meaningful state changes — never per keystroke
//     (Apple's own keyboard ships key-press haptics OFF by default).
//   • Always `.prepare()` immediately before firing so the Taptic Engine
//     is warm and latency stays under ~100 ms.
//   • Never pair two haptics within 150 ms — each call site fires exactly
//     one, and warning/error banners are the single sink for failures so
//     a `shake()` + banner pair doesn't double-buzz.
//   • The generators silently no-op when the user has System Haptics off
//     (Settings → Sounds & Haptics), so no capability check is needed.
//
// The generators are retained statics: re-using them keeps the engine
// primed across calls on the hot keyboard path instead of paying an
// allocation per feedback event.
enum KeyboardHaptics {

    private static let impactLight  = UIImpactFeedbackGenerator(style: .light)
    private static let selection    = UISelectionFeedbackGenerator()
    private static let notification = UINotificationFeedbackGenerator()

    /// Continuous-selection feel — a latched state toggle (caps lock) or a
    /// pick from a list (Quick Panel tile, slash / preset chip). Maps to
    /// `UISelectionFeedbackGenerator` per §7.1.
    static func selectionChanged() {
        selection.prepare()
        selection.selectionChanged()
    }

    /// Light physical tap — a deliberate gesture surfacing a panel
    /// (double-tap-space → Quick Panel) or confirming a copy-to-clipboard.
    static func lightImpact() {
        impactLight.prepare()
        impactLight.impactOccurred()
    }

    /// An async task finished successfully (image / text generation done).
    static func success() {
        notification.prepare()
        notification.notificationOccurred(.success)
    }

    /// A validation issue or "not allowed yet" state — pairs with the
    /// command-bar shake + the ⚠️ banner. `.warning` (not `.error`)
    /// because these are recoverable prompts, not hard failures.
    static func warning() {
        notification.prepare()
        notification.notificationOccurred(.warning)
    }
}
#endif
