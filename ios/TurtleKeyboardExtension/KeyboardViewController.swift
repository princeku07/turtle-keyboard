import UIKit

class KeyboardViewController: UIInputViewController {

    // MARK: - Keyboard mode / shift state

    private enum KeyboardMode { case qwerty, symbols, symbolsShift }
    private var mode: KeyboardMode = .qwerty

    private var isCapsLock    = false
    private var isShiftedOnce = false
    private var lastShiftTap: TimeInterval = 0
    private var lastSpaceTap: TimeInterval = 0
    private let doubleTapInterval: TimeInterval = 0.3

    private let haptic = UIImpactFeedbackGenerator(style: .light)

    // MARK: - Slash command state

    private enum SlashCommand: String {
        case cap = "cap", fix = "fix", tone = "tone", reply = "reply", tl = "tl"
        var emoji: String {
            switch self { case .cap: "🎨"; case .fix: "✏️"; case .tone: "🎭"; case .reply: "💬"; case .tl: "🌐" }
        }
        var needsPrompt: Bool { self == .cap || self == .tone || self == .tl }
        var buttonTitle: String { self == .cap ? "Generate" : "Send" }
    }

    private var activeCommand: SlashCommand?
    private var commandPromptText = ""
    private var isGenerating      = false

    // MARK: - Layout
    //
    // keyboardContainer height = commandBarH + rowsH, always fixed.
    // preferredContentSize is set once in viewDidLoad and NEVER changed.
    // commandBar and bannerContainer live at the top of keyboardContainer (y=0).
    // Key rows always start at y = commandBarH + rowGap.
    // Show/hide the command bar via isHidden only — no size changes.
    // This guarantees the iOS system shortcut bar never overlaps our UI.

    private let rowH:        CGFloat = 54
    private let rowGap:      CGFloat = 12
    private let keyGap:      CGFloat = 8
    private let commandBarH: CGFloat = 52
    private let bottomPad:   CGFloat = 4

    // Height of the four key rows (no command bar)
    private var rowsH: CGFloat {
        let rows = currentRows()
        return CGFloat(rows.count) * rowH
            + CGFloat(rows.count + 1) * rowGap
            + bottomPad
    }

    // Total keyboard height — always commandBarH + rowsH
    private var totalH: CGFloat { commandBarH + rowsH }

    private var kbWidth: CGFloat { UIScreen.main.bounds.width }

    // MARK: - UI references

    private var commandBar:        UIView!
    private var cmdPill:           UILabel!
    private var cmdPromptLabel:    UILabel!
    private var cmdSendButton:     UIButton!
    private var cmdCancelButton:   UIButton!
    private var cmdSpinner:        UIActivityIndicatorView!
    private var bannerContainer:   UIView!
    private var bannerLabel:       UILabel!
    private var keyboardContainer: UIView!
    private var heightConstraint:  NSLayoutConstraint!
    private var hideBannerTimer:   Timer?

    // MARK: - Palette

    private let bgColor    = UIColor(red: 0.106, green: 0.369, blue: 0.125, alpha: 1.0)
    private let keyNormal  = UIColor(red: 0.220, green: 0.510, blue: 0.235, alpha: 1.0)
    private let keySpecial = UIColor(red: 0.145, green: 0.420, blue: 0.160, alpha: 1.0)
    private let keyShiftOn = UIColor(red: 0.290, green: 0.580, blue: 0.305, alpha: 1.0)
    private let barBg      = UIColor(red: 0.045, green: 0.180, blue: 0.060, alpha: 1.0)

    // MARK: - Key rows

    private let qwertyRows: [[String]] = [
        ["q","w","e","r","t","y","u","i","o","p"],
        ["a","s","d","f","g","h","j","k","l"],
        ["⇧","z","x","c","v","b","n","m","⌫"],
        ["🌐","?123",",","space",".","↵"]
    ]
    private let symbolRows: [[String]] = [
        ["1","2","3","4","5","6","7","8","9","0"],
        ["@","#","$","_","&","-","+","(",")","/"],
        ["=\\<","*","\"","'",":",";","!","?","⌫"],
        ["🌐","ABC",",","space",".","↵"]
    ]
    private let symbolShiftRows: [[String]] = [
        ["~","`","|","•","√","π","÷","×","§","∆"],
        ["%","^","€","£","¥","=","{","}","\\"],
        ["?123","_","—","[","]","<",">","!","⌫"],
        ["🌐","ABC",",","space",".","↵"]
    ]

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        haptic.prepare()
        view.backgroundColor = .clear
        setupContainers()
        buildKeyboard()
        // Set once — never changed again anywhere in this file.
        preferredContentSize = CGSize(width: 0, height: totalH)
    }

    // MARK: - Container setup

    private func setupContainers() {
        // ── Keyboard container — fixed height, always pinned to bottom ──────
        keyboardContainer = UIView()
        keyboardContainer.backgroundColor = bgColor
        keyboardContainer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(keyboardContainer)

        heightConstraint = keyboardContainer.heightAnchor.constraint(equalToConstant: totalH)

        NSLayoutConstraint.activate([
            keyboardContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            keyboardContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            keyboardContainer.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            heightConstraint,
        ])

        // ── Command bar — top slice of keyboardContainer ─────────────────────
        commandBar = UIView()
        commandBar.backgroundColor = barBg
        commandBar.isHidden        = true
        commandBar.translatesAutoresizingMaskIntoConstraints = false
        keyboardContainer.addSubview(commandBar)

        // ✕ cancel
        cmdCancelButton = UIButton(type: .system)
        cmdCancelButton.setImage(
            UIImage(systemName: "xmark",
                    withConfiguration: UIImage.SymbolConfiguration(pointSize: 12, weight: .medium)),
            for: .normal)
        cmdCancelButton.tintColor = UIColor.white.withAlphaComponent(0.6)
        cmdCancelButton.addTarget(self, action: #selector(cancelCommand), for: .touchUpInside)
        cmdCancelButton.translatesAutoresizingMaskIntoConstraints = false
        commandBar.addSubview(cmdCancelButton)

        // Command pill  "🎨 /cap"
        cmdPill = UILabel()
        cmdPill.font               = .monospacedSystemFont(ofSize: 12, weight: .bold)
        cmdPill.textColor          = .white
        cmdPill.backgroundColor    = UIColor.white.withAlphaComponent(0.15)
        cmdPill.layer.cornerRadius = 5
        cmdPill.clipsToBounds      = true
        cmdPill.textAlignment      = .center
        cmdPill.setContentHuggingPriority(.required, for: .horizontal)
        cmdPill.setContentCompressionResistancePriority(.required, for: .horizontal)
        cmdPill.translatesAutoresizingMaskIntoConstraints = false
        commandBar.addSubview(cmdPill)

        // Prompt preview
        cmdPromptLabel = UILabel()
        cmdPromptLabel.font      = .systemFont(ofSize: 15)
        cmdPromptLabel.textColor = UIColor.white.withAlphaComponent(0.45)
        cmdPromptLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        cmdPromptLabel.translatesAutoresizingMaskIntoConstraints = false
        commandBar.addSubview(cmdPromptLabel)

        // Spinner
        cmdSpinner = UIActivityIndicatorView(style: .medium)
        cmdSpinner.color            = .white
        cmdSpinner.hidesWhenStopped = true
        cmdSpinner.translatesAutoresizingMaskIntoConstraints = false
        commandBar.addSubview(cmdSpinner)

        // Send / Generate button
        cmdSendButton = UIButton(type: .system)
        cmdSendButton.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        cmdSendButton.setTitleColor(.white, for: .normal)
        cmdSendButton.backgroundColor    = UIColor.white.withAlphaComponent(0.18)
        cmdSendButton.layer.cornerRadius = 8
        cmdSendButton.contentEdgeInsets  = UIEdgeInsets(top: 7, left: 14, bottom: 7, right: 14)
        cmdSendButton.setContentHuggingPriority(.required, for: .horizontal)
        cmdSendButton.addTarget(self, action: #selector(sendCommand), for: .touchUpInside)
        cmdSendButton.translatesAutoresizingMaskIntoConstraints = false
        commandBar.addSubview(cmdSendButton)

        // ── Banner — overlays the command bar slot ────────────────────────────
        bannerContainer = UIView()
        bannerContainer.backgroundColor = UIColor(red: 0.051, green: 0.247, blue: 0.071, alpha: 1.0)
        bannerContainer.isHidden        = true
        bannerContainer.translatesAutoresizingMaskIntoConstraints = false
        keyboardContainer.addSubview(bannerContainer)

        bannerLabel = UILabel()
        bannerLabel.textColor     = .white
        bannerLabel.font          = .boldSystemFont(ofSize: 13)
        bannerLabel.textAlignment = .center
        bannerLabel.translatesAutoresizingMaskIntoConstraints = false
        bannerContainer.addSubview(bannerLabel)

        NSLayoutConstraint.activate([
            // Command bar fills the top commandBarH of keyboardContainer
            commandBar.topAnchor.constraint(equalTo: keyboardContainer.topAnchor),
            commandBar.leadingAnchor.constraint(equalTo: keyboardContainer.leadingAnchor),
            commandBar.trailingAnchor.constraint(equalTo: keyboardContainer.trailingAnchor),
            commandBar.heightAnchor.constraint(equalToConstant: commandBarH),

            // Banner occupies the same top slot
            bannerContainer.topAnchor.constraint(equalTo: keyboardContainer.topAnchor),
            bannerContainer.leadingAnchor.constraint(equalTo: keyboardContainer.leadingAnchor),
            bannerContainer.trailingAnchor.constraint(equalTo: keyboardContainer.trailingAnchor),
            bannerContainer.heightAnchor.constraint(equalToConstant: commandBarH),
            bannerLabel.leadingAnchor.constraint(equalTo: bannerContainer.leadingAnchor),
            bannerLabel.trailingAnchor.constraint(equalTo: bannerContainer.trailingAnchor),
            bannerLabel.topAnchor.constraint(equalTo: bannerContainer.topAnchor),
            bannerLabel.bottomAnchor.constraint(equalTo: bannerContainer.bottomAnchor),

            // Command bar internals
            cmdCancelButton.leadingAnchor.constraint(equalTo: commandBar.leadingAnchor, constant: 10),
            cmdCancelButton.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdCancelButton.widthAnchor.constraint(equalToConstant: 28),

            cmdPill.leadingAnchor.constraint(equalTo: cmdCancelButton.trailingAnchor, constant: 6),
            cmdPill.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdPill.heightAnchor.constraint(equalToConstant: 26),

            cmdPromptLabel.leadingAnchor.constraint(equalTo: cmdPill.trailingAnchor, constant: 10),
            cmdPromptLabel.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdPromptLabel.trailingAnchor.constraint(equalTo: cmdSendButton.leadingAnchor, constant: -10),

            cmdSpinner.centerXAnchor.constraint(equalTo: cmdSendButton.centerXAnchor),
            cmdSpinner.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),

            cmdSendButton.trailingAnchor.constraint(equalTo: commandBar.trailingAnchor, constant: -12),
            cmdSendButton.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
        ])
    }

    // MARK: - Build keyboard

    private func buildKeyboard() {
        // Remove previous key rows (not commandBar / bannerContainer)
        keyboardContainer.subviews
            .filter { $0 !== commandBar && $0 !== bannerContainer }
            .forEach { $0.removeFromSuperview() }

        let rows = currentRows()
        for (i, keys) in rows.enumerated() {
            // Rows always start below the command bar slot
            let y = commandBarH + rowGap + CGFloat(i) * (rowH + rowGap)
            keyboardContainer.addSubview(buildRow(keys: keys, rowIndex: i, totalRows: rows.count, y: y))
        }
    }

    private func buildRow(keys: [String], rowIndex: Int, totalRows: Int, y: CGFloat) -> UIView {
        let w         = kbWidth
        let container = UIView(frame: CGRect(x: 0, y: y, width: w, height: rowH))
        let isBottom   = rowIndex == totalRows - 1
        let isModifier = rowIndex == totalRows - 2
        let isMiddle   = keys.count == 9 && !isBottom && !isModifier

        var widths: [CGFloat]; var xOffset: CGFloat

        if isBottom {
            let props: [CGFloat] = [8, 12, 7, 42, 7, 24]
            let avail = w - keyGap * CGFloat(keys.count + 1)
            widths = props.map { avail * $0 / 100 }; xOffset = keyGap
        } else if isModifier {
            let sideW   = w * 0.135
            let avail   = w - 2 * sideW - keyGap * CGFloat(keys.count + 1)
            let letterW = avail / CGFloat(keys.count - 2)
            widths = keys.indices.map { i in i == 0 || i == keys.count - 1 ? sideW : letterW }
            xOffset = keyGap
        } else if isMiddle {
            let indent = w * 0.055
            let avail  = w - 2 * indent - keyGap * CGFloat(keys.count - 1)
            widths = Array(repeating: avail / CGFloat(keys.count), count: keys.count)
            xOffset = indent
        } else {
            let avail = w - keyGap * CGFloat(keys.count + 1)
            widths = Array(repeating: avail / CGFloat(keys.count), count: keys.count)
            xOffset = keyGap
        }

        for (i, key) in keys.enumerated() {
            let btn = makeKey(label: key)
            btn.frame = CGRect(x: xOffset, y: 0, width: i < widths.count ? widths[i] : 44, height: rowH)
            container.addSubview(btn)
            xOffset += (i < widths.count ? widths[i] : 44) + keyGap
        }
        return container
    }

    private func makeKey(label: String) -> UIButton {
        let btn           = UIButton(type: .custom)
        let isShiftActive = label == "⇧" && (isCapsLock || isShiftedOnce)
        let c16  = UIImage.SymbolConfiguration(pointSize: 16, weight: .medium)
        let c15  = UIImage.SymbolConfiguration(pointSize: 15, weight: .medium)
        let c15r = UIImage.SymbolConfiguration(pointSize: 15, weight: .regular)

        switch label {
        case "🌐":
            btn.setImage(UIImage(systemName: "globe", withConfiguration: c15r), for: .normal)
            btn.tintColor = .white; btn.backgroundColor = keySpecial
        case "↵":
            btn.setImage(UIImage(systemName: "return", withConfiguration: c16), for: .normal)
            btn.tintColor = .white; btn.backgroundColor = keySpecial
        case "⇧" where isShiftActive:
            btn.setImage(UIImage(systemName: isCapsLock ? "capslock.fill" : "shift.fill",
                                 withConfiguration: c15), for: .normal)
            btn.tintColor = .white; btn.backgroundColor = keyShiftOn
        case "⇧":
            btn.setImage(UIImage(systemName: "shift", withConfiguration: c15), for: .normal)
            btn.tintColor = .white; btn.backgroundColor = keySpecial
        case "⌫":
            btn.setImage(UIImage(systemName: "delete.backward", withConfiguration: c15r), for: .normal)
            btn.tintColor = .white; btn.backgroundColor = keySpecial
        case "space":
            btn.setTitle("space", for: .normal)
            btn.titleLabel?.font = .systemFont(ofSize: 16)
            btn.setTitleColor(UIColor.white.withAlphaComponent(0.7), for: .normal)
            btn.backgroundColor = keyNormal
        case _ where isSpecial(label):
            btn.setTitle(label, for: .normal)
            btn.titleLabel?.font = .systemFont(ofSize: 15, weight: .medium)
            btn.setTitleColor(.white, for: .normal)
            btn.backgroundColor = keySpecial
        default:
            btn.setTitle(displayTitle(for: label), for: .normal)
            btn.titleLabel?.font = .systemFont(ofSize: 22, weight: .light)
            btn.setTitleColor(.white, for: .normal)
            btn.backgroundColor = keyNormal
        }

        btn.layer.cornerRadius  = 8; btn.layer.masksToBounds = false
        btn.layer.shadowColor   = UIColor.black.cgColor
        btn.layer.shadowOffset  = CGSize(width: 0, height: 1.5)
        btn.layer.shadowOpacity = 0.45; btn.layer.shadowRadius = 0
        btn.accessibilityLabel  = label
        btn.addTarget(self, action: #selector(keyTouchDown(_:)), for: .touchDown)
        btn.addTarget(self, action: #selector(keyTapped(_:)),    for: .touchUpInside)
        return btn
    }

    // MARK: - Key press handling

    @objc private func keyTouchDown(_ sender: UIButton) {
        haptic.impactOccurred()
        UIView.animate(withDuration: 0.05) { sender.transform = CGAffineTransform(scaleX: 0.93, y: 0.93) }
    }

    @objc private func keyTapped(_ sender: UIButton) {
        UIView.animate(withDuration: 0.08) { sender.transform = .identity }
        guard let key = sender.accessibilityLabel else { return }
        let proxy = textDocumentProxy

        switch key {
        case "🌐":  advanceToNextInputMode(); return
        case "⇧":   handleShift(); return
        case "↵":   proxy.insertText("\n"); hideCommandBar()
        case "⌫":   handleBackspace(); updateCommandDetection()
        case "space":
            proxy.insertText(" ")
            handleSpaceDoubleTap()
            updateCommandDetection()
        case "?123":
            mode = .symbols; isCapsLock = false; isShiftedOnce = false
            rebuildKeyboard(); return
        case "ABC":
            mode = .qwerty; isCapsLock = false; isShiftedOnce = false
            rebuildKeyboard(); return
        case "=\\<":
            mode = .symbolsShift; rebuildKeyboard(); return
        default:
            var text = key
            if mode == .qwerty, key.count == 1, key.first?.isLetter == true {
                text = (isCapsLock || isShiftedOnce) ? key.uppercased() : key
                if isShiftedOnce && !isCapsLock {
                    isShiftedOnce = false
                    proxy.insertText(text)
                    updateCommandDetection()
                    rebuildKeyboard(); return
                }
            }
            proxy.insertText(text)
            updateCommandDetection()
        }
    }

    private func handleBackspace() {
        let proxy = textDocumentProxy
        if let sel = proxy.selectedText, !sel.isEmpty { proxy.insertText("") }
        else { proxy.deleteBackward() }
    }

    private func handleShift() {
        guard mode == .qwerty else { return }
        let now = Date().timeIntervalSinceReferenceDate
        if now - lastShiftTap < doubleTapInterval {
            isCapsLock = !isCapsLock; isShiftedOnce = false; lastShiftTap = 0
        } else {
            isCapsLock = false; isShiftedOnce = !isShiftedOnce; lastShiftTap = now
        }
        rebuildKeyboard()
    }

    private func handleSpaceDoubleTap() {
        let now = Date().timeIntervalSinceReferenceDate
        if now - lastSpaceTap < doubleTapInterval {
            showBanner("🐢 Quick Panel — coming soon")
            lastSpaceTap = 0
        } else { lastSpaceTap = now }
    }

    // MARK: - Slash command detection

    private func updateCommandDetection() {
        guard let context = textDocumentProxy.documentContextBeforeInput,
              let slashIdx = context.lastIndex(of: "/") else { hideCommandBar(); return }

        let candidate = String(context[slashIdx...])
        guard !candidate.contains("\n") else { hideCommandBar(); return }

        let withoutSlash = String(candidate.dropFirst())
        let spaceIdx     = withoutSlash.firstIndex(of: " ")
        let cmdName      = spaceIdx.map { String(withoutSlash[..<$0]) } ?? withoutSlash
        let prompt       = spaceIdx.map { String(withoutSlash[withoutSlash.index(after: $0)...]) } ?? ""

        guard let cmd = SlashCommand(rawValue: cmdName.lowercased()) else { hideCommandBar(); return }
        guard spaceIdx != nil || !cmd.needsPrompt else { hideCommandBar(); return }

        commandPromptText = prompt
        showCommandBar(cmd)
    }

    // MARK: - Command bar  (isHidden only — height and preferredContentSize never change)

    private func showCommandBar(_ cmd: SlashCommand) {
        guard !isGenerating else { return }
        activeCommand = cmd

        cmdPill.text = "  \(cmd.emoji) /\(cmd.rawValue)  "
        cmdSendButton.setTitle(cmd.buttonTitle, for: .normal)

        if commandPromptText.isEmpty {
            cmdPromptLabel.text      = cmd.needsPrompt ? "type prompt above…" : "ready — tap \(cmd.buttonTitle)"
            cmdPromptLabel.textColor = UIColor.white.withAlphaComponent(0.40)
        } else {
            cmdPromptLabel.text      = commandPromptText
            cmdPromptLabel.textColor = UIColor.white.withAlphaComponent(0.90)
        }

        if commandBar.isHidden {
            commandBar.alpha  = 0
            commandBar.isHidden = false
            UIView.animate(withDuration: 0.18) { self.commandBar.alpha = 1 }
        }
    }

    private func hideCommandBar() {
        guard !commandBar.isHidden, !isGenerating else { return }
        activeCommand = nil
        UIView.animate(withDuration: 0.15, animations: {
            self.commandBar.alpha = 0
        }, completion: { _ in
            self.commandBar.isHidden = true
            self.commandBar.alpha    = 1
        })
    }

    @objc private func cancelCommand() {
        if let context = textDocumentProxy.documentContextBeforeInput,
           let slashIdx = context.lastIndex(of: "/") {
            for _ in String(context[slashIdx...]) { textDocumentProxy.deleteBackward() }
        }
        hideCommandBar()
    }

    @objc private func sendCommand() {
        guard let cmd = activeCommand, !isGenerating else { return }
        if cmd.needsPrompt && commandPromptText.trimmingCharacters(in: .whitespaces).isEmpty {
            shake(commandBar); showBanner("Type a prompt first ↑"); return
        }

        isGenerating = true
        cmdSendButton.isHidden = true
        cmdSpinner.startAnimating()

        if let context = textDocumentProxy.documentContextBeforeInput,
           let slashIdx = context.lastIndex(of: "/") {
            for _ in String(context[slashIdx...]) { textDocumentProxy.deleteBackward() }
        }
        executeCommand(cmd, prompt: commandPromptText)
    }

    // MARK: - Command execution (stub — replace body with POST /v1/command)

    private func executeCommand(_ cmd: SlashCommand, prompt: String) {
        DispatchQueue.main.asyncAfter(deadline: .now() + (cmd == .cap ? 1.8 : 1.1)) { [weak self] in
            guard let self else { return }
            self.isGenerating = false
            self.cmdSpinner.stopAnimating()
            self.cmdSendButton.isHidden = false
            self.hideCommandBar()

            switch cmd {
            case .cap:
                UIPasteboard.general.string = "[\(prompt)]"
                self.showBanner("🎨 Image ready — long-press field to paste")
            case .fix:
                self.textDocumentProxy.insertText("[fixed text]")
                self.showBanner("✏️ Grammar fixed")
            case .tone:
                self.textDocumentProxy.insertText("[rewritten: \(prompt)]")
                self.showBanner("🎭 Tone applied")
            case .reply:
                self.textDocumentProxy.insertText("[suggested reply]")
                self.showBanner("💬 Reply ready")
            case .tl:
                self.textDocumentProxy.insertText("[translated → \(prompt)]")
                self.showBanner("🌐 Translated")
            }
        }
    }

    // MARK: - Banner

    private func showBanner(_ text: String) {
        bannerLabel.text          = text
        bannerContainer.alpha     = 0
        bannerContainer.isHidden  = false
        UIView.animate(withDuration: 0.15) { self.bannerContainer.alpha = 1 }
        hideBannerTimer?.invalidate()
        hideBannerTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: false) { [weak self] _ in
            UIView.animate(withDuration: 0.2, animations: {
                self?.bannerContainer.alpha = 0
            }, completion: { _ in
                self?.bannerContainer.isHidden = true
                self?.bannerContainer.alpha    = 1
            })
        }
    }

    private func shake(_ v: UIView) {
        let a = CAKeyframeAnimation(keyPath: "transform.translation.x")
        a.values = [-6, 6, -4, 4, -2, 2, 0]; a.duration = 0.35
        v.layer.add(a, forKey: "shake")
    }

    // MARK: - Keyboard rebuild

    private func rebuildKeyboard() {
        buildKeyboard()
        // heightConstraint and preferredContentSize are never changed
    }

    // MARK: - Helpers

    private func currentRows() -> [[String]] {
        switch mode {
        case .qwerty: return qwertyRows; case .symbols: return symbolRows
        case .symbolsShift: return symbolShiftRows
        }
    }

    private func displayTitle(for key: String) -> String {
        if mode == .qwerty, key.count == 1, key.first?.isLetter == true {
            return (isCapsLock || isShiftedOnce) ? key.uppercased() : key
        }
        return key
    }

    private func isSpecial(_ key: String) -> Bool {
        ["🌐","⇧","⌫","?123","ABC","=\\<","↵","space"].contains(key)
    }
}
