import Foundation

/// Static key-row data for the keyboard. Mirrors Android's `Keycodes` —
/// no logic here, just the layout tables the keyboard renders. Two device
/// shapes (iPhone = 4 rows, iPad = 5 rows) × three modes (qwerty, symbols,
/// shifted symbols).
///
/// Special key glyphs:
///   ⇧ shift (long-press / double-tap → caps lock)
///   ⌫ backspace
///   🌐 globe (next-input)
///   ?123 / ABC / =\< mode switches
///   ↵ return
///   space — literal label, rendered as the spacebar
enum KeyRows {

    enum Mode { case qwerty, symbols, symbolsShift }

    // iPhone — 4 rows fit in ~334pt total.
    // Bottom row drops Apple's globe (next-keyboard) key in favour of a
    // direct `/` for triggering slash commands — that's the primary action
    // of this keyboard, so it should be one tap away from qwerty without
    // hopping into symbols mode. Spacebar slides left into the gap the
    // globe used to occupy.
    // Bottom row keeps the dedicated `/` (the slash-command trigger) AND the
    // system globe (🌐). The globe is REQUIRED — it's the only in-keyboard way
    // for the user to switch back to another keyboard, and Apple's review
    // guidelines mandate a reachable next-keyboard control. Dropping it (as an
    // earlier layout did) strands iPhone users on Turtle and risks rejection.
    static let iphoneQwerty: [[String]] = [
        ["q","w","e","r","t","y","u","i","o","p"],
        ["a","s","d","f","g","h","j","k","l"],
        ["⇧","z","x","c","v","b","n","m","⌫"],
        ["?123","🌐","space","/","↵"]
    ]
    static let iphoneSymbols: [[String]] = [
        ["1","2","3","4","5","6","7","8","9","0"],
        ["-","/",":",";","(",")","$","&","@","\""],
        ["=\\<",".",",","?","!","'","⌫"],
        ["ABC","🌐","space","/","↵"]
    ]
    static let iphoneSymbolsShift: [[String]] = [
        ["[","]","{","}","#","%","^","*","+","="],
        ["_","\\","|","~","<",">","€","£","¥","•"],
        ["?123",".",",","?","!","'","⌫"],
        ["ABC","🌐","space","/","↵"]
    ]

    // iPad — 5 rows: number row + 3 letter rows + modifier row
    static let ipadQwerty: [[String]] = [
        ["1","2","3","4","5","6","7","8","9","0","⌫"],
        ["q","w","e","r","t","y","u","i","o","p"],
        ["a","s","d","f","g","h","j","k","l","↵"],
        ["⇧","z","x","c","v","b","n","m",",",".","⇧"],
        ["🌐","?123","space","?123","↵"]
    ]
    static let ipadSymbols: [[String]] = [
        ["1","2","3","4","5","6","7","8","9","0","⌫"],
        ["@","#","$","_","&","-","+","(",")","/"],
        ["=","*","\"","'",":",";","!","?","€","↵"],
        ["=\\<","~","`","|","%","^","[","]","{","}","⇧"],
        ["🌐","ABC","space","ABC","↵"]
    ]
    static let ipadSymbolsShift: [[String]] = [
        ["1","2","3","4","5","6","7","8","9","0","⌫"],
        ["~","`","|","•","√","π","÷","×","§","∆"],
        ["%","^","€","£","¥","=","\\","{","}","↵"],
        ["?123","_","—","[","]","<",">","!",".",",","⇧"],
        ["🌐","ABC","space","ABC","↵"]
    ]

    static func rows(for mode: Mode, isPad: Bool) -> [[String]] {
        if isPad {
            switch mode {
            case .qwerty:       return ipadQwerty
            case .symbols:      return ipadSymbols
            case .symbolsShift: return ipadSymbolsShift
            }
        } else {
            switch mode {
            case .qwerty:       return iphoneQwerty
            case .symbols:      return iphoneSymbols
            case .symbolsShift: return iphoneSymbolsShift
            }
        }
    }

    /// Glyphs that get the special-key visual treatment (different colour,
    /// no caps transformation).
    static let specialKeys: Set<String> = [
        "🌐","⇧","⌫","?123","ABC","=\\<","↵","space"
    ]

    static func isSpecial(_ key: String) -> Bool {
        specialKeys.contains(key)
    }

    /// What to render on the keycap, accounting for shift state. Letters
    /// uppercase when shifted; everything else stays as-is.
    static func displayTitle(for key: String, mode: Mode, shifted: Bool) -> String {
        if mode == .qwerty, key.count == 1, key.first?.isLetter == true {
            return shifted ? key.uppercased() : key
        }
        return key
    }
}
