import Foundation
#if os(iOS)
import UIKit

// MARK: - KeyboardTheme
//
// Port of android/.../theme/KeyboardTheme. Holds every color token the
// keyboard surfaces touch. New tokens added here automatically flow through
// `KeyboardPalette` (which delegates to the current theme), so call sites
// in `KeyboardViewController` don't change.
//
// To add a theme: append a static factory below and a `ThemePreference`
// case in KeyboardThemeManager.swift.

struct KeyboardTheme: Equatable {

    let id: String                // stable persistence key, e.g. "turtle"
    let displayName: String

    // Surfaces
    let bg: UIColor               // keyboard container, panel host
    let barBg: UIColor            // command bar background
    let bannerBg: UIColor         // transient banner above keys

    // Keys
    let keyNormal: UIColor        // letter / digit keys
    let keySpecial: UIColor       // modifier / function keys
    let keyShiftOn: UIColor       // shift latched (caps lock)
    let keyText: UIColor          // glyph color for letter / digit keys
    let keyTextSpecial: UIColor   // glyph color for special keys (shift, return, etc.)

    // Brand
    let accent: UIColor           // return-key fill / preview-overlay highlight

    // MARK: - Presets

    /// Current brand look — turtle green. Default for existing installs.
    static let turtle = KeyboardTheme(
        id: "turtle",
        displayName: "Turtle",
        bg:        UIColor(red: 0.106, green: 0.369, blue: 0.125, alpha: 1.0),
        barBg:     UIColor(red: 0.045, green: 0.180, blue: 0.060, alpha: 1.0),
        bannerBg:  UIColor(red: 0.051, green: 0.247, blue: 0.071, alpha: 1.0),
        keyNormal: UIColor(red: 0.220, green: 0.510, blue: 0.235, alpha: 1.0),
        keySpecial: UIColor(red: 0.145, green: 0.420, blue: 0.160, alpha: 1.0),
        keyShiftOn: UIColor(red: 0.290, green: 0.580, blue: 0.305, alpha: 1.0),
        keyText:    .white,
        keyTextSpecial: .white,
        accent:    UIColor(red: 0.082, green: 0.502, blue: 0.239, alpha: 1.0)
    )

    /// Light keyboard look — pale gray surface, white keys, dark glyphs.
    /// Command bar + banner keep the brand dark-green so command-bar
    /// text (white) reads consistently across themes.
    static let light = KeyboardTheme(
        id: "light",
        displayName: "Light",
        bg:        UIColor(red: 0.820, green: 0.827, blue: 0.851, alpha: 1.0),
        barBg:     UIColor(red: 0.045, green: 0.180, blue: 0.060, alpha: 1.0),
        bannerBg:  UIColor(red: 0.082, green: 0.502, blue: 0.239, alpha: 1.0),
        keyNormal: .white,
        keySpecial: UIColor(red: 0.675, green: 0.690, blue: 0.741, alpha: 1.0),
        keyShiftOn: UIColor(red: 0.082, green: 0.502, blue: 0.239, alpha: 1.0),
        keyText:    UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1.0),
        keyTextSpecial: UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1.0),
        accent:    UIColor(red: 0.082, green: 0.502, blue: 0.239, alpha: 1.0)
    )

    /// Dark keyboard look — near-black surface, dark gray keys, white glyphs.
    static let dark = KeyboardTheme(
        id: "dark",
        displayName: "Dark",
        bg:        UIColor(red: 0.110, green: 0.110, blue: 0.118, alpha: 1.0),
        barBg:     UIColor(red: 0.055, green: 0.055, blue: 0.063, alpha: 1.0),
        bannerBg:  UIColor(red: 0.082, green: 0.502, blue: 0.239, alpha: 1.0),
        keyNormal: UIColor(red: 0.173, green: 0.173, blue: 0.180, alpha: 1.0),
        keySpecial: UIColor(red: 0.110, green: 0.110, blue: 0.118, alpha: 1.0),
        keyShiftOn: UIColor(red: 0.310, green: 0.557, blue: 0.361, alpha: 1.0),
        keyText:    .white,
        keyTextSpecial: .white,
        accent:    UIColor(red: 0.310, green: 0.557, blue: 0.361, alpha: 1.0)
    )

    static let allPresets: [KeyboardTheme] = [.turtle, .light, .dark]

    static func find(id: String) -> KeyboardTheme? {
        allPresets.first { $0.id == id }
    }
}
#endif
