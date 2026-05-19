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
    // The keyboard surface (bg / barBg / bannerBg) is a SINGLE colour per
    // theme — the suggestion strip, banner, and inter-key gap all paint
    // the same tone so the entire keyboard region reads as one continuous
    // surface. Previously these diverged (e.g. dark had pure-black bg
    // but dark-gray barBg), which made the empty suggestion strip look
    // visibly darker than the key-gap area.

    static let turtle = KeyboardTheme(
        id: "turtle",
        displayName: "Turtle",
        bg:        UIColor(red: 0.078, green: 0.290, blue: 0.094, alpha: 1.0),
        barBg:     UIColor(red: 0.078, green: 0.290, blue: 0.094, alpha: 1.0),
        bannerBg:  UIColor(red: 0.078, green: 0.290, blue: 0.094, alpha: 1.0),
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
        // Backdrop is fully clear — host content shows through the
        // suggestion strip slot and the inter-key gaps. The blur
        // material on each key (`.systemMaterialLight` via
        // `blurMaterialForCurrentTheme`) gives the floating-glass
        // light keyboard look. Letter keys use a white tint at high
        // alpha so they pop on any host bg; special keys use a soft
        // dark tint so they read as "modifier" keys.
        bg:        .clear,
        barBg:     .clear,
        bannerBg:  .clear,
        keyNormal: UIColor(white: 1.0, alpha: 0.82),
        keySpecial: UIColor(white: 0.0, alpha: 0.10),
        keyShiftOn: .white,
        keyText:    UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1.0),
        keyTextSpecial: UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1.0),
        keyTextShiftOn: UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1.0),
        accent:    UIColor(red: 0.082, green: 0.502, blue: 0.239, alpha: 1.0),
        chipBg:    UIColor(white: 0.0, alpha: 0.08),
        chipText:  UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1.0),
        barText:   UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1.0)
    )

    /// Dark keyboard — backdrop matches iOS's SYSTEM keyboard color
    /// (`#1C1C1E`, the same value `UIColor.systemBackground` resolves
    /// to in dark mode). This is the tone iOS uses for the globe / mic
    /// strip at the bottom of the keyboard region — by matching it,
    /// our suggestion strip, key-gap area, and the system-rendered row
    /// at the bottom all read as one continuous surface. The host app's
    /// chat area sits on top in whatever colour the app picked
    /// (often pure black), but that boundary belongs to the host —
    /// we're matching the system chrome, not the host chrome.
    static let dark = KeyboardTheme(
        id: "dark",
        displayName: "Dark",
        // Native iOS dark keyboard values — researched from Apple's
        // floating-glass keyboard style. The keyboard CONTAINER is
        // fully clear (host content visible through the gaps and the
        // suggestion strip slot), and the blur lives ONLY on the keys
        // themselves via UIVisualEffectView in `applyTranslucentBacking`:
        //   letter key:  rgba(255,255,255, 0.18) — white tint over blur.
        //   special key: rgba(255,255,255, 0.08) — darker tint so the
        //                two-tone read (letter vs modifier) survives.
        //   text:        rgba(255,255,255, 0.92).
        bg:        .clear,
        barBg:     .clear,
        bannerBg:  .clear,
        keyNormal: UIColor(white: 1.0, alpha: 0.18),
        // Special keys (⇧, ⌫, ↵, ?123, etc.) sit a notch darker than
        // letter keys but were previously at 0.08 alpha, which made
        // them disappear into the blur on most host backgrounds.
        // 0.12 keeps the two-tone read (lighter letters / darker
        // specials) AND keeps the special keys clearly visible.
        keySpecial: UIColor(white: 1.0, alpha: 0.12),
        keyShiftOn: UIColor(white: 1.0, alpha: 0.95),
        keyText:    UIColor(white: 1.0, alpha: 0.92),
        keyTextSpecial: UIColor(white: 1.0, alpha: 0.92),
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
