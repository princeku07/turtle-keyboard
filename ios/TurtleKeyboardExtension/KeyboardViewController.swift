import UIKit

class KeyboardViewController: UIInputViewController {

    // MARK: - State

    private enum KeyboardMode { case qwerty, symbols, symbolsShift }
    private var mode: KeyboardMode = .qwerty

    private var isCapsLock    = false
    private var isShiftedOnce = false
    private var lastShiftTap: TimeInterval = 0
    private var lastSpaceTap: TimeInterval = 0
    private let doubleTapInterval: TimeInterval = 0.3

    // MARK: - Layout constants

    private let rowH:    CGFloat = 45
    private let rowGap:  CGFloat = 8
    private let keyGap:  CGFloat = 6
    private let bannerH: CGFloat = 32

    private var keyboardH: CGFloat {
        CGFloat(currentRows().count) * rowH + CGFloat(currentRows().count + 1) * rowGap
    }

    // Width is always the screen width for a keyboard extension in portrait
    private var kbWidth: CGFloat { UIScreen.main.bounds.width }

    // MARK: - UI

    private var bannerContainer: UIView!
    private var bannerLabel:     UILabel!
    private var keyboardContainer: UIView!
    private var heightConstraint: NSLayoutConstraint!
    private var hideBannerTimer: Timer?

    // MARK: - Colours  (#1B5E20 bg, #0D3F12 banner — matches Android)

    private let bgColor     = UIColor(red: 0.106, green: 0.369, blue: 0.125, alpha: 1.0)
    private let bannerBg    = UIColor(red: 0.051, green: 0.247, blue: 0.071, alpha: 1.0)
    private let keyNormal   = UIColor.white.withAlphaComponent(0.18)
    private let keySpecial  = UIColor.white.withAlphaComponent(0.08)

    // MARK: - Key rows (mirrors Android XML layouts)

    private let qwertyRows: [[String]] = [
        ["q","w","e","r","t","y","u","i","o","p"],
        ["a","s","d","f","g","h","j","k","l"],
        ["⇧","z","x","c","v","b","n","m","⌫"],
        ["?123",",","/","space",".","↵"]
    ]
    private let symbolRows: [[String]] = [
        ["1","2","3","4","5","6","7","8","9","0"],
        ["@","#","$","_","&","-","+","(",")","/"],
        ["=\\<","*","\"","'",":",";","!","?","⌫"],
        ["ABC",",","/","space",".","↵"]
    ]
    private let symbolShiftRows: [[String]] = [
        ["~","`","|","•","√","π","÷","×","§","∆"],
        ["%","^","€","£","¥","=","{","}","\\"],
        ["?123","_","—","[","]","<",">","!","⌫"],
        ["ABC",",","/","space",".","↵"]
    ]

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = bgColor
        setupContainers()
        buildKeyboard()
        // Set size once — Auto Layout keeps the container at the bottom regardless
        // of whatever height the system assigns to the view.
        preferredContentSize = CGSize(width: 0, height: keyboardH)
    }

    // MARK: - Container setup (Auto Layout, pinned to bottom of view)

    private func setupContainers() {
        // Banner (hidden by default, sits just above the keyboard container)
        bannerContainer = UIView()
        bannerContainer.backgroundColor = bannerBg
        bannerContainer.isHidden = true
        bannerContainer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bannerContainer)

        bannerLabel = UILabel()
        bannerLabel.textColor     = .white
        bannerLabel.font          = .boldSystemFont(ofSize: 14)
        bannerLabel.textAlignment = .center
        bannerLabel.translatesAutoresizingMaskIntoConstraints = false
        bannerContainer.addSubview(bannerLabel)

        // Keyboard container — pinned to the BOTTOM of the view so keys are
        // always visible at the bottom even if the system gives us extra height.
        keyboardContainer = UIView()
        keyboardContainer.backgroundColor = .clear
        keyboardContainer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(keyboardContainer)

        heightConstraint = keyboardContainer.heightAnchor.constraint(equalToConstant: keyboardH)

        NSLayoutConstraint.activate([
            // Keyboard container
            keyboardContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            keyboardContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            keyboardContainer.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            heightConstraint,

            // Banner sits directly above keyboard container
            bannerContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bannerContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bannerContainer.bottomAnchor.constraint(equalTo: keyboardContainer.topAnchor),
            bannerContainer.heightAnchor.constraint(equalToConstant: bannerH),

            // Banner label fills banner container
            bannerLabel.leadingAnchor.constraint(equalTo: bannerContainer.leadingAnchor),
            bannerLabel.trailingAnchor.constraint(equalTo: bannerContainer.trailingAnchor),
            bannerLabel.topAnchor.constraint(equalTo: bannerContainer.topAnchor),
            bannerLabel.bottomAnchor.constraint(equalTo: bannerContainer.bottomAnchor),
        ])
    }

    // MARK: - Build keyboard (frame-based inside keyboardContainer)

    private func buildKeyboard() {
        keyboardContainer.subviews.forEach { $0.removeFromSuperview() }
        let w    = kbWidth
        let rows = currentRows()

        for (rowIdx, keys) in rows.enumerated() {
            let y    = rowGap + CGFloat(rowIdx) * (rowH + rowGap)
            let row  = buildRow(keys: keys, rowIndex: rowIdx,
                                totalRows: rows.count, width: w, y: y)
            keyboardContainer.addSubview(row)
        }
    }

    private func buildRow(keys: [String], rowIndex: Int,
                          totalRows: Int, width w: CGFloat, y: CGFloat) -> UIView {
        let container    = UIView(frame: CGRect(x: 0, y: y, width: w, height: rowH))
        let isBottom     = rowIndex == totalRows - 1
        let isModifier   = rowIndex == totalRows - 2
        let isMiddle     = keys.count == 9 && !isBottom && !isModifier

        var widths:  [CGFloat]
        var xOffset: CGFloat

        if isBottom {
            // Proportions match Android qwerty.xml: ?123=15 ,=10 /=10 space=30 .=10 ↵=25
            let props: [CGFloat] = [15, 10, 10, 30, 10, 25]
            let avail = w - keyGap * CGFloat(keys.count + 1)
            widths  = props.map { avail * $0 / 100 }
            xOffset = keyGap
        } else if isModifier {
            // ⇧/=\\< = 15%  letters = 10% each  ⌫ = 15%
            let avail = w - keyGap * CGFloat(keys.count + 1)
            widths  = keys.enumerated().map { (i, _) -> CGFloat in
                avail * ((i == 0 || i == keys.count - 1) ? 0.15 : 0.10)
            }
            xOffset = keyGap
        } else if isMiddle {
            // 9-key row: symmetric ~5.5% indent, gap only between keys
            let indent = w * 0.055
            let avail  = w - 2 * indent - keyGap * CGFloat(keys.count - 1)
            widths  = Array(repeating: avail / CGFloat(keys.count), count: keys.count)
            xOffset = indent
        } else {
            // Top row (10 keys): equal width, gap on every side
            let avail = w - keyGap * CGFloat(keys.count + 1)
            widths  = Array(repeating: avail / CGFloat(keys.count), count: keys.count)
            xOffset = keyGap
        }

        for (i, key) in keys.enumerated() {
            let kw  = i < widths.count ? widths[i] : 44
            let btn = makeKey(label: key)
            btn.frame = CGRect(x: xOffset, y: 0, width: kw, height: rowH)
            container.addSubview(btn)
            xOffset += kw + keyGap
        }
        return container
    }

    private func makeKey(label: String) -> UIButton {
        let btn = UIButton(type: .system)
        btn.setTitle(displayTitle(for: label), for: .normal)
        btn.titleLabel?.font    = isSpecial(label)
            ? .systemFont(ofSize: 14, weight: .medium)
            : .systemFont(ofSize: 17)
        btn.setTitleColor(.white, for: .normal)
        btn.backgroundColor     = isSpecial(label) ? keySpecial : keyNormal
        btn.layer.cornerRadius  = 5
        btn.layer.shadowColor   = UIColor.black.cgColor
        btn.layer.shadowOffset  = CGSize(width: 0, height: 1)
        btn.layer.shadowOpacity = 0.35
        btn.layer.shadowRadius  = 1
        btn.accessibilityLabel  = label
        btn.addTarget(self, action: #selector(keyTapped(_:)), for: .touchUpInside)
        return btn
    }

    // MARK: - Key press handling

    @objc private func keyTapped(_ sender: UIButton) {
        guard let key = sender.accessibilityLabel else { return }
        let proxy = textDocumentProxy

        switch key {
        case "⌫":   handleBackspace()
        case "⇧":   handleShift(); return
        case "↵":   proxy.insertText("\n")
        case "space":
            proxy.insertText(" ")
            handleSpaceDoubleTap()
        case "?123":
            mode = .symbols; isCapsLock = false; isShiftedOnce = false
            rebuildKeyboard(); return
        case "ABC":
            mode = .qwerty;  isCapsLock = false; isShiftedOnce = false
            rebuildKeyboard(); return
        case "=\\<":
            mode = .symbolsShift
            rebuildKeyboard(); return
        default:
            var text = key
            if mode == .qwerty, key.count == 1, key.first?.isLetter == true {
                text = (isCapsLock || isShiftedOnce) ? key.uppercased() : key
                if isShiftedOnce && !isCapsLock {
                    isShiftedOnce = false
                    proxy.insertText(text)
                    rebuildKeyboard(); return
                }
            }
            proxy.insertText(text)
        }
    }

    private func handleBackspace() {
        let proxy = textDocumentProxy
        if let sel = proxy.selectedText, !sel.isEmpty {
            proxy.insertText("")
        } else {
            proxy.deleteBackward()
        }
    }

    private func handleShift() {
        guard mode == .qwerty else { return }
        let now = Date().timeIntervalSinceReferenceDate
        if now - lastShiftTap < doubleTapInterval {
            isCapsLock    = !isCapsLock
            isShiftedOnce = false
            lastShiftTap  = 0
        } else {
            isCapsLock    = false
            isShiftedOnce = !isShiftedOnce
            lastShiftTap  = now
        }
        rebuildKeyboard()
    }

    private func handleSpaceDoubleTap() {
        let now = Date().timeIntervalSinceReferenceDate
        if now - lastSpaceTap < doubleTapInterval {
            showBanner("🐢 Double-tap detected")
            lastSpaceTap = 0
        } else {
            lastSpaceTap = now
        }
    }

    // MARK: - Banner

    private func showBanner(_ text: String) {
        bannerLabel.text        = text
        bannerContainer.isHidden = false
        hideBannerTimer?.invalidate()
        hideBannerTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: false) { [weak self] _ in
            self?.bannerContainer.isHidden = true
        }
    }

    // MARK: - Helpers

    private func rebuildKeyboard() {
        buildKeyboard()
        // Update the height constraint if the row count changed (e.g. mode switch)
        heightConstraint.constant = keyboardH
        preferredContentSize = CGSize(width: 0, height: keyboardH)
    }

    private func currentRows() -> [[String]] {
        switch mode {
        case .qwerty:       return qwertyRows
        case .symbols:      return symbolRows
        case .symbolsShift: return symbolShiftRows
        }
    }

    private func displayTitle(for key: String) -> String {
        if key == "⇧" { return isCapsLock ? "⇪" : "⇧" }
        if mode == .qwerty, key.count == 1, key.first?.isLetter == true {
            return (isCapsLock || isShiftedOnce) ? key.uppercased() : key
        }
        return key
    }

    private func isSpecial(_ key: String) -> Bool {
        ["⇧","⌫","?123","ABC","=\\<","↵","space"].contains(key)
    }
}
