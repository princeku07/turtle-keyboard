import Foundation
#if os(iOS)
import UIKit

// MARK: - SuggestedShortcut
//
// Port of android/.../integration/SuggestedShortcut. Surfaces a curated
// palette of pre-filled templates above the keys when entering an empty
// field. Tap → either insert the template directly, or seed the command
// bar with it for the user to refine.

struct SuggestedShortcut {
    let name: String          // unique id, e.g. "followup"
    let label: String         // chip display label, e.g. "Follow-up"
    let emoji: String         // single-char badge, e.g. "📧"
    let template: String      // text inserted on tap (verbatim when needsPrompt = false)
    let needsPrompt: Bool     // true → seed the command bar; false → insert directly
}

// MARK: - FieldKind
//
// iOS keyboard extensions cannot read the host app's bundle id (the way
// Android keys the catalog off `EditorInfo.packageName`). We derive the
// closest signal we *can* see — field traits via `UITextDocumentProxy`
// + `UITextInputTraits` — and bucket fields into a coarse kind enum.

enum FieldKind {
    case sensitive    // password, OTP, CVV — never show shortcuts here
    case email
    case url
    case search
    case numeric
    case general

    static func from(_ input: InputContext) -> FieldKind {
        if input.looksSensitive { return .sensitive }

        switch input.textContentType {
        case .some(.emailAddress): return .email
        case .some(.URL):          return .url
        default: break
        }

        switch input.keyboardType {
        case .emailAddress:
            return .email
        case .URL:
            return .url
        case .webSearch:
            return .search
        case .numberPad, .decimalPad, .numbersAndPunctuation,
             .phonePad, .asciiCapableNumberPad:
            return .numeric
        default:
            return .general
        }
    }
}
#endif
