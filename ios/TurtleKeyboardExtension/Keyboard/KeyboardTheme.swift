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
    let keyTextShiftOn: UIColor   // glyph color when shift is latched — Apple flips this to a contrast tone

    // Brand
    let accent: UIColor           // return-key fill / preview-overlay highlight

    // Command-bar chrome (pill, send button, suggestion chips, slash strip).
    // Pulled out so the chips in the slash-autocomplete and word-suggestion
    // strips don't have to hardcode white-on-green.
    let chipBg: UIColor
    let chipText: UIColor
    /// Glyph + label colour for items rendered INSIDE the command bar
    /// (cancel ✕, send arrow, prompt label). Was hardcoded white before.
    let barText: UIColor

    // MARK: - Presets

    // Apple's iOS keyboard layers three shades within a single theme:
    //   1. backdrop  — darkest tone, sets the canvas
    //   2. special keys (shift / 123 / return / ⌫) — mid tone
    //   3. letter keys — lightest tone, pop forward
    // Each theme below applies that same hierarchy in its own palette so
    // the keys read as "raised" against the bar instead of disappearing
    // into it (the previous Turtle / Dark palettes had letter keys at
    // similar luminance to the background, which is why typing felt
    // visually cramped versus the native keyboard).

    // Palette values below are matched to Apple's iOS native keyboard,
    // sampled per-theme so each look gets the same three-tone layering:
    //   backdrop   — darkest in dark theme / lightest backdrop in light theme
    //   special    — mid tone, used for shift / 123 / ⌫ / return
    //   letterKey  — pops one shade off the backdrop in the OTHER direction
    // The previous Turtle/Dark values had letter keys at similar luminance
    // to the backdrop, which is why the keyboard felt flat / muted vs
    // Apple's native keyboard.

    /// Turtle brand look — green palette using Apple's layering ratio.
    /// Letter keys ride higher than the green backdrop; special keys
    /// sit between. Same green identity, now with native-keyboard depth.
    static let turtle = KeyboardTheme(
        id: "turtle",
        displayName: "Turtle",
        bg:        UIColor(red: 0.078, green: 0.290, blue: 0.094, alpha: 1.0),
        barBg:     UIColor(red: 0.045, green: 0.180, blue: 0.060, alpha: 1.0),
        bannerBg:  UIColor(red: 0.051, green: 0.247, blue: 0.071, alpha: 1.0),
        keyNormal: UIColor(red: 0.298, green: 0.580, blue: 0.310, alpha: 1.0),
        keySpecial: UIColor(red: 0.180, green: 0.435, blue: 0.196, alpha: 1.0),
        keyShiftOn: UIColor(red: 0.380, green: 0.690, blue: 0.396, alpha: 1.0),
        keyText:    .white,
        keyTextSpecial: .white,
        keyTextShiftOn: .white,
        accent:    UIColor(red: 0.082, green: 0.502, blue: 0.239, alpha: 1.0),
        chipBg:    UIColor(white: 1.0, alpha: 0.18),
        chipText:  .white,
        barText:   .white
    )

    /// Light keyboard — sampled from Apple's iOS light keyboard.
    /// Backdrop: cool gray #D1D4DB. Letter keys: pure white. Special
    /// keys: gray-blue #ABB0BD. Glyph: near-black.
    static let light = KeyboardTheme(
        id: "light",
        displayName: "Light",
        bg:        UIColor(red: 0.820, green: 0.835, blue: 0.859, alpha: 1.0),
        barBg:     UIColor(red: 0.890, green: 0.898, blue: 0.914, alpha: 1.0),
        bannerBg:  UIColor(red: 0.890, green: 0.898, blue: 0.914, alpha: 1.0),
        keyNormal: .white,
        keySpecial: UIColor(red: 0.671, green: 0.690, blue: 0.741, alpha: 1.0),
        keyShiftOn: .white,
        keyText:    UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1.0),
        keyTextSpecial: UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1.0),
        keyTextShiftOn: UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1.0),
        accent:    UIColor(red: 0.082, green: 0.502, blue: 0.239, alpha: 1.0),
        chipBg:    UIColor(white: 0.0, alpha: 0.08),
        chipText:  UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1.0),
        barText:   UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1.0)
    )

    /// Dark keyboard — sampled from Apple's iOS dark keyboard.
    /// Backdrop: pure black. Letter keys: dark mid-gray #3C3C3F (low
    /// contrast against backdrop, the way native keys read). Special
    /// keys: a notch darker #2A2A2F. Glyph: pure white.
    static let dark = KeyboardTheme(
        id: "dark",
        displayName: "Dark",
        bg:        UIColor(red: 0.000, green: 0.000, blue: 0.000, alpha: 1.0),
        barBg:     UIColor(red: 0.118, green: 0.118, blue: 0.133, alpha: 1.0),
        bannerBg:  UIColor(red: 0.118, green: 0.118, blue: 0.133, alpha: 1.0),
        keyNormal: UIColor(red: 0.235, green: 0.235, blue: 0.247, alpha: 1.0),
        keySpecial: UIColor(red: 0.165, green: 0.165, blue: 0.184, alpha: 1.0),
        keyShiftOn: UIColor(red: 0.847, green: 0.847, blue: 0.871, alpha: 1.0),
        keyText:    .white,
        keyTextSpecial: .white,
        keyTextShiftOn: UIColor(red: 0.000, green: 0.000, blue: 0.000, alpha: 1.0),
        accent:    UIColor(red: 0.310, green: 0.557, blue: 0.361, alpha: 1.0),
        chipBg:    UIColor(white: 1.0, alpha: 0.14),
        chipText:  UIColor(white: 0.96, alpha: 1.0),
        barText:   .white
    )

    static let allPresets: [KeyboardTheme] = [.turtle, .light, .dark]

    static func find(id: String) -> KeyboardTheme? {
        allPresets.first { $0.id == id }
    }
}
#endif
