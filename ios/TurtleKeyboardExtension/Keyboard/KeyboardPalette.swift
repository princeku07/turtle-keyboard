import Foundation
#if os(iOS)
import UIKit

/// Brand colours used across the keyboard. Pulled out of
/// `KeyboardViewController` so the same palette can be referenced by
/// integration views (panel backgrounds, etc.) without copy-pasting
/// hex literals. Mirrors Android's eventual `theme/KeyboardTheme`.
enum KeyboardPalette {
    /// Dark turtle-green — keyboard background.
    static let bg        = UIColor(red: 0.106, green: 0.369, blue: 0.125, alpha: 1.0)
    /// Letter / digit keys.
    static let keyNormal = UIColor(red: 0.220, green: 0.510, blue: 0.235, alpha: 1.0)
    /// Modifier / function keys (shift, backspace, return, etc.).
    static let keySpecial = UIColor(red: 0.145, green: 0.420, blue: 0.160, alpha: 1.0)
    /// Shift key when latched on (caps lock).
    static let keyShiftOn = UIColor(red: 0.290, green: 0.580, blue: 0.305, alpha: 1.0)
    /// Command / banner bar background.
    static let barBg     = UIColor(red: 0.045, green: 0.180, blue: 0.060, alpha: 1.0)
}
#endif
