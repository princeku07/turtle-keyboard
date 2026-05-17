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
    static var accent:    UIColor { current.accent }
    static var chipBg:    UIColor { current.chipBg }
    static var chipText:  UIColor { current.chipText }
    static var barText:   UIColor { current.barText }
}
#endif
