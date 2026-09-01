import SwiftUI

// MARK: - WidgetPalette
//
// Mirrors the brand values in `TurtleKeyboard/Generated/BrandTokens.swift`.
// Duplicated rather than shared because that file is UIKit-typed (UIColor)
// and this target is SwiftUI-only. Keep the two in sync by hand if the brand
// moves — the comment on each line names the token it came from.

enum WidgetPalette {
    static let background = Color(red: 0.9451, green: 0.9608, blue: 0.9098) // onboardBg
    static let ink        = Color(red: 0.0471, green: 0.0471, blue: 0.0471) // onboardTitle
    static let green      = Color(red: 0.0000, green: 0.5255, blue: 0.3451) // brandGreen
    static let greenDeep  = Color(red: 0.0549, green: 0.3608, blue: 0.1725) // brandGreenDeep
    static let subtle     = Color(red: 0.4196, green: 0.4196, blue: 0.4196) // onboardSubtitle
    static let tile       = Color(red: 0.8549, green: 0.9373, blue: 0.8000) // lowGreen12
    static let warn       = Color(red: 0.8510, green: 0.4500, blue: 0.1000) // setup nudge accent
}

// MARK: - Shared view helpers

extension View {
    /// iOS 17 requires widgets to declare their background through
    /// `containerBackground`, or the system renders them with a default
    /// padded chrome. iOS 15/16 have no such API, so fall back to a plain
    /// background there.
    @ViewBuilder
    func turtleContainerBackground(_ color: Color) -> some View {
        if #available(iOS 17.0, *) {
            self.containerBackground(color, for: .widget)
        } else {
            self.background(color)
        }
    }
}
