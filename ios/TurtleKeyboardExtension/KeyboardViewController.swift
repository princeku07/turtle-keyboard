import UIKit

class KeyboardViewController: UIInputViewController {

    // MARK: - Keyboard mode / shift state

    // KeyboardMode moved to Keyboard/KeyRows.swift as KeyRows.Mode.
    private var mode: KeyRows.Mode = .qwerty

    private var isCapsLock    = false
    private var isShiftedOnce = false
    private var lastShiftTap: TimeInterval = 0
    private var lastSpaceTap: TimeInterval = 0
    private let doubleTapInterval: TimeInterval = 0.3

    private let haptic = UIImpactFeedbackGenerator(style: .light)

    // MARK: - Slash command state

    // SlashCommand enum moved to Command/SlashCommand.swift

    private var activeCommand: SlashCommand?
    private var commandPromptText = ""
    private var isGenerating      = false
    private var pendingSuggestions: [String] = []

    // While the user is composing a slash command, every keystroke is routed
    // into this buffer instead of `textDocumentProxy.insertText`. The host app
    // never sees `/ask …`; only the final result (or nothing, if cancelled)
    // reaches the text field. nil = normal typing.
    private var slashBuffer: String?

    // commandBar can show one of three things at a time
    private enum SuggestionMode { case none, slashCommand, replyResult, wordSuggestion }
    private var suggestionMode: SuggestionMode = .none

    private let textChecker = UITextChecker()

    // MARK: - Layout
    //
    // keyboardContainer height = commandBarH + rowsH, always fixed.
    // preferredContentSize is set once in viewDidLoad and NEVER changed.
    // commandBar and bannerContainer live at the top of keyboardContainer (y=0).
    // Key rows always start at y = commandBarH + rowGap.
    // Show/hide the command bar via isHidden only — no size changes.
    // This guarantees the iOS system shortcut bar never overlaps our UI.

    // Layout dimensions vary by device:
    // iPhone — 4 rows fit in ~334pt total (matches iOS native).
    // iPad   — 5 rows (extra number row) must fit in iPad's input view height.
    //          iOS only gives custom keyboards ~290pt portrait on smaller iPads
    //          (mini), so we size for that worst case. Command bar shrinks too.
    //          5*42 + 6*6 + 4 = 250 (rows) + 40 (commandBar) = 290pt total.
    private var rowH:        CGFloat { isPad ? 42 : 54 }
    private var rowGap:      CGFloat { isPad ? 6  : 12 }
    private var commandBarH: CGFloat { isPad ? 40 : 52 }
    private let keyGap:      CGFloat = 8
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

    private var commandBar:          UIView!
    private var cmdPill:             UILabel!
    private var cmdPromptLabel:      UILabel!
    private var cmdSendButton:       UIButton!
    private var cmdCancelButton:     UIButton!
    private var cmdMicButton:        UIButton!
    private var cmdSpinner:          UIActivityIndicatorView!

    // Voice dictation for the prompt area. Lazily created on first mic tap so
    // we don't pull in SFSpeechRecognizer until the user actually wants it.
    private lazy var voiceController = VoiceInputController()
    /// Snapshot of `slashBuffer` at the moment dictation started. We rebuild
    /// it as `<prefix><partial>` on every recognizer tick so cancelling
    /// dictation cleanly reverts to the typed prompt.
    private var voicePromptPrefix: String?
    private var cmdSuggestionsStack: UIStackView!
    private var cmdSuggestionBtns:   [UIButton] = []
    private var bannerContainer:     UIView!
    private var bannerLabel:         UILabel!
    private var keyboardContainer:   UIView!
    private var heightConstraint:    NSLayoutConstraint!
    private var hideBannerTimer:     Timer?
    private var backspaceTimer:      Timer?
    private var previewOverlay:      UIView?
    private var previewImageView:    UIImageView?
    private var pendingPreviewImage: UIImage?

    // Integration panel mount — holds whatever UIView an integration asked
    // us to show via IntegrationContext.showPanel. Sits on top of the key
    // rows but below the command bar / preview overlay.
    private var integrationPanelHost: UIView?

    // Quick Panel — opened by double-tapping space (PRD §6.6). Lives in
    // the same overlay slot as integration panels (mutually exclusive).
    private var quickPanelView: QuickPanelView?
    private lazy var integrationContext: IntegrationContext = KeyboardIntegrationContext(owner: self)
    private lazy var personalizationStore: SplitStore =
        UserDefaultsSplitStore(suiteName: SplitContract.storageSuiteName)

    private lazy var integrationRegistry = IntegrationRegistry([
        SplitIntegration(),
        NotionIntegration(),
        SlackIntegration(),
    ], store: personalizationStore)

    // MARK: - Palette
    //
    // Hex literals live in Keyboard/KeyboardPalette.swift; these are
    // wrappers so the (now-many) call sites in this file don't need to
    // change. Behavior is identical.

    private var bgColor:    UIColor { KeyboardPalette.bg }
    private var keyNormal:  UIColor { KeyboardPalette.keyNormal }
    private var keySpecial: UIColor { KeyboardPalette.keySpecial }
    private var keyShiftOn: UIColor { KeyboardPalette.keyShiftOn }
    private var barBg:      UIColor { KeyboardPalette.barBg }

    // MARK: - Device

    private var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    // Key row data moved to Keyboard/KeyRows.swift

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        haptic.prepare()
        view.backgroundColor = .clear
        setupContainers()
        buildKeyboard()
        // Set once — never changed again anywhere in this file.
        preferredContentSize = CGSize(width: 0, height: totalH)

        // Try to suppress the iPad system shortcut bar (undo / redo / clipboard).
        // This bar is owned by the host app's UITextField; a keyboard extension
        // cannot fully remove it, but emptying our own assistant item often
        // collapses it to zero on iPad.
        suppressSystemShortcutBar()

    }

    private func suppressSystemShortcutBar() {
        inputAssistantItem.leadingBarButtonGroups  = []
        inputAssistantItem.trailingBarButtonGroups = []
    }

    // MARK: - Image preview overlay
    //
    // After /org or /cap finishes, the resulting image is shown as a preview
    // covering the keyboard area. The user inspects it and either taps
    // "Copy to clipboard" (puts it on the pasteboard so they can long-press
    // and paste in the chat field) or "Close" to discard.

    private func showImagePreview(_ image: UIImage) {
        if previewOverlay == nil { buildPreviewOverlay() }
        pendingPreviewImage = image
        previewImageView?.image = image
        previewOverlay?.isHidden = false
        if let overlay = previewOverlay {
            keyboardContainer.bringSubviewToFront(overlay)
        }
        hideCommandBar()
    }

    private func dismissPreview() {
        previewOverlay?.isHidden = true
        pendingPreviewImage = nil
    }

    @objc private func previewCopyTapped() {
        if let img = pendingPreviewImage {
            UIPasteboard.general.image = img
            showBanner("📋 Copied — long-press field to paste")
        }
        dismissPreview()
    }

    @objc private func previewCloseTapped() {
        dismissPreview()
    }

    private func buildPreviewOverlay() {
        let overlay = UIView()
        overlay.backgroundColor = bgColor
        overlay.translatesAutoresizingMaskIntoConstraints = false
        keyboardContainer.addSubview(overlay)
        NSLayoutConstraint.activate([
            overlay.topAnchor.constraint(equalTo: keyboardContainer.topAnchor),
            overlay.leadingAnchor.constraint(equalTo: keyboardContainer.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: keyboardContainer.trailingAnchor),
            overlay.bottomAnchor.constraint(equalTo: keyboardContainer.bottomAnchor),
        ])

        let title = UILabel()
        title.text = "Preview"
        title.font = .boldSystemFont(ofSize: 13)
        title.textColor = .white
        title.translatesAutoresizingMaskIntoConstraints = false
        overlay.addSubview(title)

        let imageView = UIImageView()
        imageView.contentMode = .scaleAspectFit
        imageView.layer.cornerRadius = 8
        imageView.clipsToBounds = true
        imageView.backgroundColor = .white
        imageView.translatesAutoresizingMaskIntoConstraints = false
        overlay.addSubview(imageView)

        let copyBtn = UIButton(type: .system)
        copyBtn.setTitle("📋  Copy", for: .normal)
        copyBtn.setTitleColor(UIColor(red: 0.106, green: 0.369, blue: 0.125, alpha: 1.0), for: .normal)
        copyBtn.titleLabel?.font = .boldSystemFont(ofSize: 14)
        copyBtn.backgroundColor = .white
        copyBtn.layer.cornerRadius = 8
        copyBtn.translatesAutoresizingMaskIntoConstraints = false
        copyBtn.addTarget(self, action: #selector(previewCopyTapped), for: .touchUpInside)
        overlay.addSubview(copyBtn)

        let closeBtn = UIButton(type: .system)
        closeBtn.setTitle("✕  Close", for: .normal)
        closeBtn.setTitleColor(.white, for: .normal)
        closeBtn.titleLabel?.font = .systemFont(ofSize: 14, weight: .medium)
        closeBtn.backgroundColor = UIColor.white.withAlphaComponent(0.18)
        closeBtn.layer.cornerRadius = 8
        closeBtn.translatesAutoresizingMaskIntoConstraints = false
        closeBtn.addTarget(self, action: #selector(previewCloseTapped), for: .touchUpInside)
        overlay.addSubview(closeBtn)

        NSLayoutConstraint.activate([
            title.topAnchor.constraint(equalTo: overlay.topAnchor, constant: 8),
            title.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),

            imageView.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 6),
            imageView.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),
            imageView.bottomAnchor.constraint(equalTo: copyBtn.topAnchor, constant: -10),
            imageView.widthAnchor.constraint(equalTo: imageView.heightAnchor),
            imageView.leadingAnchor.constraint(greaterThanOrEqualTo: overlay.leadingAnchor, constant: 16),

            copyBtn.leadingAnchor.constraint(equalTo: overlay.leadingAnchor, constant: 12),
            copyBtn.trailingAnchor.constraint(equalTo: overlay.centerXAnchor, constant: -6),
            copyBtn.bottomAnchor.constraint(equalTo: overlay.bottomAnchor, constant: -10),
            copyBtn.heightAnchor.constraint(equalToConstant: 42),

            closeBtn.leadingAnchor.constraint(equalTo: overlay.centerXAnchor, constant: 6),
            closeBtn.trailingAnchor.constraint(equalTo: overlay.trailingAnchor, constant: -12),
            closeBtn.bottomAnchor.constraint(equalTo: overlay.bottomAnchor, constant: -10),
            closeBtn.heightAnchor.constraint(equalToConstant: 42),
        ])

        overlay.isHidden = true
        previewOverlay = overlay
        previewImageView = imageView
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

        // 🎙 mic — toggles voice dictation into the prompt area. Hidden
        // entirely when the user has switched off "Voice mic key" on the
        // Personalization screen.
        let voiceEnabled = personalizationStore.int(
            forKey: PersonalizationKeys.voiceEnabled, fallback: 1) != 0
        cmdMicButton = UIButton(type: .system)
        cmdMicButton.isHidden = !voiceEnabled
        cmdMicButton.setImage(
            UIImage(systemName: "mic.fill",
                    withConfiguration: UIImage.SymbolConfiguration(pointSize: 15, weight: .medium)),
            for: .normal)
        cmdMicButton.tintColor = UIColor.white.withAlphaComponent(0.85)
        cmdMicButton.backgroundColor = UIColor.white.withAlphaComponent(0.12)
        cmdMicButton.layer.cornerRadius = 8
        cmdMicButton.setContentHuggingPriority(.required, for: .horizontal)
        cmdMicButton.addTarget(self, action: #selector(micTapped), for: .touchUpInside)
        cmdMicButton.translatesAutoresizingMaskIntoConstraints = false
        commandBar.addSubview(cmdMicButton)

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

        // Suggestion chips — for /reply results (3 tappable options)
        cmdSuggestionsStack = UIStackView()
        cmdSuggestionsStack.axis         = .horizontal
        cmdSuggestionsStack.distribution = .fillEqually
        cmdSuggestionsStack.spacing      = 6
        cmdSuggestionsStack.isHidden     = true
        cmdSuggestionsStack.translatesAutoresizingMaskIntoConstraints = false
        commandBar.addSubview(cmdSuggestionsStack)

        for i in 0..<3 {
            let btn = UIButton(type: .custom)
            btn.tag = i
            btn.titleLabel?.font          = .systemFont(ofSize: 12, weight: .medium)
            btn.titleLabel?.lineBreakMode = .byTruncatingTail
            btn.setTitleColor(.white, for: .normal)
            btn.backgroundColor           = UIColor.white.withAlphaComponent(0.18)
            btn.layer.cornerRadius        = 8
            btn.contentEdgeInsets         = UIEdgeInsets(top: 0, left: 8, bottom: 0, right: 8)
            btn.addTarget(self, action: #selector(suggestionTapped(_:)), for: .touchUpInside)
            cmdSuggestionsStack.addArrangedSubview(btn)
            cmdSuggestionBtns.append(btn)
        }

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
            cmdPromptLabel.trailingAnchor.constraint(equalTo: cmdMicButton.leadingAnchor, constant: -8),

            cmdMicButton.trailingAnchor.constraint(equalTo: cmdSendButton.leadingAnchor, constant: -6),
            cmdMicButton.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdMicButton.widthAnchor.constraint(equalToConstant: 32),
            cmdMicButton.heightAnchor.constraint(equalToConstant: 30),

            cmdSpinner.centerXAnchor.constraint(equalTo: cmdSendButton.centerXAnchor),
            cmdSpinner.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),

            cmdSendButton.trailingAnchor.constraint(equalTo: commandBar.trailingAnchor, constant: -12),
            cmdSendButton.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),

            // Suggestions stack fills the space to the right of the cancel button
            cmdSuggestionsStack.leadingAnchor.constraint(equalTo: cmdCancelButton.trailingAnchor, constant: 6),
            cmdSuggestionsStack.trailingAnchor.constraint(equalTo: commandBar.trailingAnchor, constant: -12),
            cmdSuggestionsStack.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdSuggestionsStack.heightAnchor.constraint(equalToConstant: 36),
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
            // iPad bottom row: 5 keys [🌐, ?123, space, ?123, ↵]
            // iPhone bottom row: 6 keys [🌐, ?123, ',', space, '.', ↵]
            let props: [CGFloat]
            switch keys.count {
            case 5:  props = [9, 13, 48, 13, 17]                // iPad
            default: props = [8, 12, 7, 42, 7, 24]              // iPhone
            }
            let avail = w - keyGap * CGFloat(keys.count + 1)
            widths = props.map { avail * $0 / 100 }
            xOffset = keyGap
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

        // Press-and-hold to repeatedly delete (matches native keyboard behaviour)
        if label == "⌫" {
            let lp = UILongPressGestureRecognizer(target: self,
                                                  action: #selector(backspaceLongPress(_:)))
            lp.minimumPressDuration = 0.4
            btn.addGestureRecognizer(lp)
        }
        return btn
    }

    // MARK: - Backspace repeat

    @objc private func backspaceLongPress(_ gesture: UILongPressGestureRecognizer) {
        switch gesture.state {
        case .began:
            startBackspaceRepeat()
        case .ended, .cancelled, .failed:
            stopBackspaceRepeat()
            // cancelsTouchesInView=true means touchUpInside won't fire — restore visual
            if let btn = gesture.view as? UIButton {
                UIView.animate(withDuration: 0.08) { btn.transform = .identity }
            }
        default: break
        }
    }

    private func startBackspaceRepeat() {
        backspaceTimer?.invalidate()
        // Initial delete on press-and-hold start
        performBackspaceTick()
        haptic.impactOccurred()
        // Repeat at ~12 deletes/sec until released
        backspaceTimer = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            // Stop if there's nothing left to delete (buffer or proxy)
            if self.slashBuffer != nil {
                if self.slashBuffer?.isEmpty ?? true { self.stopBackspaceRepeat(); return }
            } else {
                let before = self.textDocumentProxy.documentContextBeforeInput ?? ""
                guard !before.isEmpty else { self.stopBackspaceRepeat(); return }
            }
            self.performBackspaceTick()
        }
    }

    private func performBackspaceTick() {
        if slashBuffer != nil {
            handleSlashBufferKey("⌫")
        } else {
            handleBackspace()
            updateCommandDetection()
        }
    }

    private func stopBackspaceRepeat() {
        backspaceTimer?.invalidate()
        backspaceTimer = nil
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

        // Layout/mode keys always work, regardless of slash-buffer state.
        switch key {
        case "🌐":   advanceToNextInputMode(); return
        case "⇧":    handleShift(); return
        case "?123":
            mode = .symbols; isCapsLock = false; isShiftedOnce = false
            rebuildKeyboard(); return
        case "ABC":
            mode = .qwerty; isCapsLock = false; isShiftedOnce = false
            rebuildKeyboard(); return
        case "=\\<":
            mode = .symbolsShift; rebuildKeyboard(); return
        default: break
        }

        // While composing a slash command, intercept everything — nothing
        // reaches the host text field until send/cancel.
        if slashBuffer != nil {
            handleSlashBufferKey(key)
            return
        }

        // Tapping "/" enters slash-buffer mode — but only when the cursor is at
        // the start of the text field (empty or whitespace-only context). A
        // mid-sentence "/" types as a normal character.
        if key == "/" {
            let pre = (proxy.documentContextBeforeInput ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if pre.isEmpty {
                slashBuffer = "/"
                updateCommandDetection()
                return
            }
        }

        switch key {
        case "↵":   proxy.insertText("\n"); hideCommandBar()
        case "⌫":   handleBackspace(); updateCommandDetection()
        case "space":
            proxy.insertText(" ")
            handleSpaceDoubleTap()
            updateCommandDetection()
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

    private func handleSlashBufferKey(_ key: String) {
        switch key {
        case "↵":
            // Treat return as "send" if we have a valid command queued.
            if activeCommand != nil { sendCommand() }
            return
        case "⌫":
            guard var buf = slashBuffer else { return }
            if !buf.isEmpty { buf.removeLast() }
            if buf.isEmpty {
                slashBuffer = nil
                hideCommandBar()
            } else {
                slashBuffer = buf
            }
            updateCommandDetection()
            return
        case "space":
            slashBuffer? += " "
            updateCommandDetection()
            return
        default:
            var text = key
            if mode == .qwerty, key.count == 1, key.first?.isLetter == true {
                text = (isCapsLock || isShiftedOnce) ? key.uppercased() : key
                if isShiftedOnce && !isCapsLock {
                    isShiftedOnce = false
                    slashBuffer? += text
                    updateCommandDetection()
                    rebuildKeyboard()
                    return
                }
            }
            slashBuffer? += text
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
        // Respect the Personalization toggle: when off, double-space falls
        // through to whatever the host expects (just two spaces).
        guard personalizationStore.int(
            forKey: PersonalizationKeys.quickPanelEnabled, fallback: 1) != 0
        else { return }
        let now = Date().timeIntervalSinceReferenceDate
        if now - lastSpaceTap < doubleTapInterval {
            // Undo the second space — the user meant "open Quick Panel",
            // not "type two spaces". (Native iOS would have inserted a
            // period here; we hide that gesture behind the panel.)
            textDocumentProxy.deleteBackward()
            lastSpaceTap = 0
            showQuickPanel()
        } else { lastSpaceTap = now }
    }

    // MARK: - Quick Panel

    private func showQuickPanel() {
        // Tear down any active command bar / integration panel first —
        // Quick Panel takes the whole overlay slot.
        if !commandBar.isHidden { hideCommandBar() }
        unmountIntegrationPanel()

        let panel = QuickPanelView(columns: isPad ? 6 : 4)
        panel.onSelect = self
        panel.show(allCommandsForQuickPanel())
        quickPanelView = panel
        mountIntegrationPanel(panel)
    }

    private func dismissQuickPanel() {
        quickPanelView = nil
        unmountIntegrationPanel()
    }

    /// Order the user sees in the panel grid. Integrations' commands come
    /// first (most recently added flows surface earliest), then the
    /// AI-routed commands. Local-only `/splits` follows `/split`.
    private func allCommandsForQuickPanel() -> [SlashCommand] {
        // Show every case in declaration order — keeps the layout stable.
        [.cap, .fix, .tone, .reply, .tl, .ask, .org,
         .split, .splits, .notion, .note, .slack, .msg]
    }

    // MARK: - Slash command detection

    private func updateCommandDetection() {
        // The slash buffer is the only source of truth — we never put `/...`
        // into the host text field, so we don't read it back from the proxy.
        if let buf = slashBuffer, buf.hasPrefix("/") {
            let body     = String(buf.dropFirst())
            let spaceIdx = body.firstIndex(of: " ")
            let cmdName  = spaceIdx.map { String(body[..<$0]) } ?? body
            let prompt   = spaceIdx.map { String(body[body.index(after: $0)...]) } ?? ""

            if let cmd = SlashCommand(rawValue: cmdName.lowercased()),
               spaceIdx != nil || !cmd.needsPrompt {
                commandPromptText = prompt
                showCommandBar(cmd)
            } else {
                // Buffer doesn't match a known command yet — show a draft bar
                // so the user can see what they're typing.
                showDraftCommandBar(buffer: buf)
            }
            return
        }

        // No active slash command — fall back to word suggestions
        if activeCommand != nil { hideCommandBar() }
        updateWordSuggestions()
    }

    // MARK: - Word suggestions (in-keyboard autocomplete strip)

    private func updateWordSuggestions() {
        guard activeCommand == nil, !isGenerating else { return }

        let context = (textDocumentProxy.documentContextBeforeInput ?? "") as NSString
        let range = context.range(of: "\\S+$", options: .regularExpression)

        guard range.location != NSNotFound else {
            if suggestionMode == .wordSuggestion { hideCommandBar() }
            return
        }
        let currentWord = context.substring(with: range)

        // Skip while user is composing a slash command
        guard !currentWord.hasPrefix("/"), currentWord.count >= 2 else {
            if suggestionMode == .wordSuggestion { hideCommandBar() }
            return
        }

        let lang = "en"
        let wordRange = NSRange(location: 0, length: currentWord.utf16.count)
        let completions = textChecker.completions(forPartialWordRange: wordRange,
                                                  in: currentWord,
                                                  language: lang) ?? []
        var picks = Array(completions.prefix(3))

        // If typed token is a misspelling, also surface guesses
        if picks.count < 3 {
            let misspelled = textChecker.rangeOfMisspelledWord(in: currentWord,
                                                               range: wordRange,
                                                               startingAt: 0,
                                                               wrap: false,
                                                               language: lang)
            if misspelled.location != NSNotFound {
                let guesses = textChecker.guesses(forWordRange: misspelled,
                                                  in: currentWord,
                                                  language: lang) ?? []
                for g in guesses where !picks.contains(g) {
                    picks.append(g)
                    if picks.count == 3 { break }
                }
            }
        }

        if picks.isEmpty {
            if suggestionMode == .wordSuggestion { hideCommandBar() }
        } else {
            showWordSuggestions(picks)
        }
    }

    private func showWordSuggestions(_ items: [String]) {
        suggestionMode = .wordSuggestion
        pendingSuggestions = items

        for (i, btn) in cmdSuggestionBtns.enumerated() {
            btn.setTitle(i < items.count ? items[i] : nil, for: .normal)
            btn.isHidden = i >= items.count
        }
        // Hide normal command-bar controls; show only the chips
        [cmdPill, cmdPromptLabel, cmdSendButton, cmdCancelButton].forEach { $0.isHidden = true }
        cmdSuggestionsStack.isHidden = false

        if commandBar.isHidden {
            commandBar.alpha = 0
            commandBar.isHidden = false
            UIView.animate(withDuration: 0.15) { self.commandBar.alpha = 1 }
        }
    }

    private func replaceCurrentWord(with replacement: String) {
        let context = (textDocumentProxy.documentContextBeforeInput ?? "") as NSString
        let range = context.range(of: "\\S+$", options: .regularExpression)
        guard range.location != NSNotFound else { return }

        let currentWord = context.substring(with: range)
        for _ in 0..<currentWord.count { textDocumentProxy.deleteBackward() }
        textDocumentProxy.insertText(replacement + " ")
    }

    // MARK: - Command bar  (isHidden only — height and preferredContentSize never change)

    private func showCommandBar(_ cmd: SlashCommand) {
        guard !isGenerating else { return }
        activeCommand = cmd
        suggestionMode = .slashCommand

        // Coming from word-suggestion mode? Restore normal controls first.
        [cmdPill, cmdPromptLabel, cmdSendButton, cmdCancelButton].forEach { $0.isHidden = false }
        cmdSuggestionsStack.isHidden = true

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

    // Shown while the buffer is `/` or `/xy` — i.e. the user is mid-typing a
    // command name that doesn't yet match a known command. Gives them visible
    // feedback for every keystroke.
    private func showDraftCommandBar(buffer: String) {
        activeCommand  = nil
        suggestionMode = .slashCommand

        [cmdPill, cmdPromptLabel, cmdSendButton, cmdCancelButton].forEach { $0.isHidden = false }
        cmdSpinner.stopAnimating()
        cmdSuggestionsStack.isHidden = true

        cmdPill.text = "  /  "
        if buffer.count <= 1 {
            cmdPromptLabel.text      = "type a command…"
            cmdPromptLabel.textColor = UIColor.white.withAlphaComponent(0.40)
        } else {
            cmdPromptLabel.text      = buffer
            cmdPromptLabel.textColor = UIColor.white.withAlphaComponent(0.90)
        }
        cmdSendButton.setTitle("Send", for: .normal)

        if commandBar.isHidden {
            commandBar.alpha    = 0
            commandBar.isHidden = false
            UIView.animate(withDuration: 0.15) { self.commandBar.alpha = 1 }
        }
    }

    private func hideCommandBar() {
        guard !commandBar.isHidden, !isGenerating else { return }
        activeCommand = nil
        pendingSuggestions = []
        suggestionMode = .none
        UIView.animate(withDuration: 0.15, animations: {
            self.commandBar.alpha = 0
        }, completion: { _ in
            self.commandBar.isHidden = true
            self.commandBar.alpha    = 1
            self.resetCommandBarMode()
        })
    }

    @objc private func cancelCommand() {
        if voiceController.isListening { voiceController.cancel() }
        voicePromptPrefix = nil
        // Nothing was inserted into the host field, so nothing to delete —
        // just drop the buffer.
        slashBuffer = nil
        hideCommandBar()
    }

    // MARK: - Voice dictation

    @objc private func micTapped() {
        guard hasFullAccess else {
            shake(commandBar)
            showBanner("⚠️ Enable Full Access in Settings → Keyboard")
            return
        }
        if voiceController.isListening {
            voiceController.stop()
            return
        }
        if VoiceInputController.hasPermissions {
            beginDictation()
        } else {
            VoiceInputController.requestPermissions { [weak self] granted in
                guard let self = self else { return }
                if granted { self.beginDictation() }
                else { self.showBanner("⚠️ Mic & Speech permission needed") }
            }
        }
    }

    private func beginDictation() {
        // Anchor the dictation onto whatever the user has typed so far —
        // typed prompt + spoken prompt concatenate naturally.
        let buf = slashBuffer ?? "/"
        // If the buffer already contains the command/prompt separator, keep
        // it as-is; otherwise add a space so the first dictated word becomes
        // the prompt body rather than glomming onto the command name.
        voicePromptPrefix = buf.contains(" ") ? buf : buf + " "
        voiceController.start(sink: self)
    }

    private func setMicListeningUI(_ listening: Bool) {
        cmdMicButton.tintColor = listening
            ? UIColor(red: 1.0, green: 0.35, blue: 0.35, alpha: 1.0)
            : UIColor.white.withAlphaComponent(0.85)
        cmdMicButton.backgroundColor = listening
            ? UIColor.white.withAlphaComponent(0.22)
            : UIColor.white.withAlphaComponent(0.12)
        let symbol = listening ? "stop.fill" : "mic.fill"
        cmdMicButton.setImage(
            UIImage(systemName: symbol,
                    withConfiguration: UIImage.SymbolConfiguration(pointSize: 15, weight: .medium)),
            for: .normal)
    }

    @objc private func sendCommand() {
        guard hasFullAccess else {
            shake(commandBar)
            showBanner("⚠️ Enable Full Access in Settings → Keyboard")
            return
        }
        guard let cmd = activeCommand, !isGenerating else { return }
        if cmd.needsPrompt && commandPromptText.trimmingCharacters(in: .whitespaces).isEmpty {
            shake(commandBar); showBanner("Type a prompt first ↑"); return
        }

        isGenerating = true
        cmdSendButton.isHidden = true
        cmdSpinner.startAnimating()

        // Buffer holds the slash text; nothing was typed into the host field,
        // so there's nothing to delete from the proxy.
        slashBuffer = nil
        executeCommand(cmd, prompt: commandPromptText)
    }

    // MARK: - Command execution

    private func executeCommand(_ cmd: SlashCommand, prompt: String) {
        // Local commands handled by integrations — no AI hop, no spinner.
        if cmd.isLocal {
            isGenerating = false
            cmdSpinner.stopAnimating()
            cmdSendButton.isHidden = false
            hideCommandBar()
            if let spec = integrationRegistry.command(named: cmd.rawValue) {
                spec.handler(prompt, integrationContext)
            }
            return
        }

        let context = contextBeforeSlash()
        Task { @MainActor in
            do {
                let result = try await CommandRouter.shared.execute(
                    command: cmd.rawValue,
                    prompt: prompt,
                    context: context
                )
                isGenerating = false
                cmdSpinner.stopAnimating()
                cmdSendButton.isHidden = false

                switch result {
                case .text(let text):
                    if cmd == .org {
                        hideCommandBar()
                        if let image = OrgImageRenderer.render(json: text) {
                            showImagePreview(image)
                        } else {
                            showBanner("⚠️ Layout render failed")
                        }
                    } else {
                        textDocumentProxy.insertText(text)
                        hideCommandBar()
                        showBanner(completionBanner(for: cmd))
                    }

                case .image(let urlString):
                    hideCommandBar()
                    do {
                        let data = try await downloadImageData(from: urlString)
                        if let image = UIImage(data: data) {
                            showImagePreview(image)
                        }
                    } catch {
                        showBanner("⚠️ Image download failed")
                    }

                case .suggestions(let items):
                    showSuggestions(items)
                }

            } catch {
                isGenerating = false
                cmdSpinner.stopAnimating()
                cmdSendButton.isHidden = false
                showBanner("⚠️ " + (error.localizedDescription))
            }
        }
    }

    private func downloadImageData(from urlString: String) async throws -> Data {
        guard let url = URL(string: urlString) else {
            throw ProviderError.badResponse("Invalid image URL")
        }
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            return data
        } catch let e as URLError { throw ProviderError.network(e) }
        catch { throw ProviderError.unknown(error) }
    }

    // Text before the slash — this is what /fix, /tone, /reply, /tl act on.
    // The slash itself never reaches the host field, so the entire pre-input
    // context is fair game.
    private func contextBeforeSlash() -> String {
        return (textDocumentProxy.documentContextBeforeInput ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func stripCodeFences(_ s: String) -> String {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("```") {
            if let nl = t.firstIndex(of: "\n") {
                t = String(t[t.index(after: nl)...])
            }
            if t.hasSuffix("```") {
                t = String(t.dropLast(3))
            }
        }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func completionBanner(for cmd: SlashCommand) -> String {
        // Moved to SlashCommand.completionBanner; keeping this wrapper so
        // the existing call site doesn't change.
        cmd.completionBanner
    }

    // MARK: - Suggestions UI  (/reply returns 3 tappable options)

    private func showSuggestions(_ items: [String]) {
        guard !items.isEmpty else {
            showBanner("⚠️ No suggestions returned")
            hideCommandBar()
            return
        }
        pendingSuggestions = items

        for (i, btn) in cmdSuggestionBtns.enumerated() {
            btn.setTitle(i < items.count ? items[i] : nil, for: .normal)
            btn.isHidden = i >= items.count
        }
        // Switch commandBar to suggestions mode
        [cmdPill, cmdPromptLabel, cmdSendButton].forEach { $0.isHidden = true }
        cmdSuggestionsStack.isHidden = false
    }

    @objc private func suggestionTapped(_ sender: UIButton) {
        let i = sender.tag
        guard i < pendingSuggestions.count else { return }
        let pick = pendingSuggestions[i]

        switch suggestionMode {
        case .wordSuggestion:
            replaceCurrentWord(with: pick)
            pendingSuggestions = []
            suggestionMode = .none
            hideCommandBar()
            // Re-evaluate suggestions after the replacement (cursor advanced)
            updateWordSuggestions()
        default:
            // /reply suggestion → insert full reply
            textDocumentProxy.insertText(pick)
            pendingSuggestions = []
            suggestionMode = .none
            resetCommandBarMode()
            hideCommandBar()
        }
    }

    private func resetCommandBarMode() {
        [cmdPill, cmdPromptLabel, cmdSendButton, cmdCancelButton].forEach { $0.isHidden = false }
        cmdSuggestionsStack.isHidden = true
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
    //
    // Wrappers around Keyboard/KeyRows.swift so the call sites don't
    // change. Behaviour is identical.

    private func currentRows() -> [[String]] {
        KeyRows.rows(for: mode, isPad: isPad)
    }

    private func displayTitle(for key: String) -> String {
        KeyRows.displayTitle(for: key, mode: mode, shifted: isCapsLock || isShiftedOnce)
    }

    private func isSpecial(_ key: String) -> Bool {
        KeyRows.isSpecial(key)
    }
}

// MARK: - Integration panel mount
//
// Integrations call `IntegrationContext.showPanel(view)` to mount their UI
// above the keys. We wrap the integration's view in a host view that's
// pinned over the key rows (below the command bar) so the user keeps
// keyboard chrome but loses the keys while the panel is up.

extension KeyboardViewController {

    func mountIntegrationPanel(_ panel: UIView) {
        if integrationPanelHost == nil {
            let host = UIView()
            host.backgroundColor = bgColor
            host.translatesAutoresizingMaskIntoConstraints = false
            keyboardContainer.addSubview(host)
            NSLayoutConstraint.activate([
                host.topAnchor.constraint(equalTo: keyboardContainer.topAnchor, constant: commandBarH),
                host.leadingAnchor.constraint(equalTo: keyboardContainer.leadingAnchor),
                host.trailingAnchor.constraint(equalTo: keyboardContainer.trailingAnchor),
                host.bottomAnchor.constraint(equalTo: keyboardContainer.bottomAnchor),
            ])
            integrationPanelHost = host
        }
        guard let host = integrationPanelHost else { return }
        host.subviews.forEach { $0.removeFromSuperview() }
        panel.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(panel)
        NSLayoutConstraint.activate([
            panel.leadingAnchor.constraint(equalTo: host.leadingAnchor),
            panel.trailingAnchor.constraint(equalTo: host.trailingAnchor),
            panel.topAnchor.constraint(equalTo: host.topAnchor),
            panel.bottomAnchor.constraint(equalTo: host.bottomAnchor),
        ])
        host.isHidden = false
        keyboardContainer.bringSubviewToFront(host)
    }

    func unmountIntegrationPanel() {
        integrationPanelHost?.subviews.forEach { $0.removeFromSuperview() }
        integrationPanelHost?.isHidden = true
    }

    func emitBanner(_ text: String) { showBanner(text) }
}

// MARK: - IntegrationContext bridge
//
// Concrete `IntegrationContext` that adapts the protocol to the keyboard
// view controller's surfaces. Holds a weak reference so panel coordinators
// don't accidentally keep the IME alive after the input session ends.

private final class KeyboardIntegrationContext: IntegrationContext {

    weak var owner: KeyboardViewController?
    let store: SplitStore
    let llm: LlmService

    init(owner: KeyboardViewController) {
        self.owner = owner
        // App Group will be wired later — falls back to standard defaults
        // for now so saves persist across launches at minimum.
        self.store = UserDefaultsSplitStore(suiteName: SplitContract.storageSuiteName)
        self.llm = LMStudioLlmService()
    }

    func showPanel(_ view: UIView) {
        DispatchQueue.main.async { self.owner?.mountIntegrationPanel(view) }
    }

    func hidePanel() {
        DispatchQueue.main.async { self.owner?.unmountIntegrationPanel() }
    }

    // No chip surface on iOS for the MVP — the whole chip/activation story
    // depends on host-app detection, which iOS keyboards can't do.
    func showChip(_ spec: ChipSpec, onTap: @escaping () -> Void) {}
    func hideChip() {}

    func showBanner(_ text: String, autoHideMs: Int) {
        DispatchQueue.main.async { self.owner?.emitBanner(text) }
    }

    func commitText(_ text: String) {
        DispatchQueue.main.async { self.owner?.textDocumentProxy.insertText(text) }
    }

    func deleteBeforeCursor(_ n: Int) {
        DispatchQueue.main.async {
            for _ in 0..<n { self.owner?.textDocumentProxy.deleteBackward() }
        }
    }

    func openScreen(_ screenId: String) {
        let urlString = "turtlekeyboard://\(screenId)"
        guard let url = URL(string: urlString) else { return }
        // `extensionContext.open` is the only way a keyboard extension can
        // launch URLs. Returns false if the host app isn't registered for
        // the scheme — silently no-op in that case (mirrors Android's
        // "no-op when the host doesn't recognize the screen id").
        DispatchQueue.main.async {
            self.owner?.extensionContext?.open(url, completionHandler: nil)
        }
    }
}

/// Minimal LlmService backed by the same LM Studio endpoint the rest of
/// the keyboard's text commands hit. Posts a single user message; returns
/// the assistant's content stripped of any `<think>…</think>` block that
/// reasoning models prepend.
private final class LMStudioLlmService: LlmService {
    private let endpoint = URL(string: "http://192.168.1.5:1234/v1/chat/completions")!

    func complete(
        prompt: String,
        onText: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 30
        let body: [String: Any] = [
            "stream": false,
            "temperature": 0.4,
            "messages": [["role": "user", "content": prompt]],
        ]
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        URLSession.shared.dataTask(with: req) { data, resp, err in
            if let err = err { onError(err.localizedDescription); return }
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let choices = json["choices"] as? [[String: Any]],
                  let first = choices.first,
                  let message = first["message"] as? [String: Any],
                  let content = message["content"] as? String
            else { onError("non-JSON LLM response"); return }
            // Strip reasoning model think blocks the same way LMStudioProvider does.
            let cleaned = content
                .replacingOccurrences(of: #"(?is)<think>.*?</think>"#,
                                       with: "", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            onText(cleaned)
        }.resume()
    }
}

// MARK: - QuickPanelDelegate

extension KeyboardViewController: QuickPanelDelegate {

    func quickPanelDidSelect(_ command: SlashCommand) {
        dismissQuickPanel()
        if command.needsPrompt {
            // Open the command bar pre-loaded with the command and an
            // empty prompt; the user types (or dictates) the body and
            // taps Send. Reusing the slash buffer keeps the existing
            // detection / dispatch path intact.
            slashBuffer = "/\(command.rawValue) "
            updateCommandDetection()
        } else {
            // No prompt needed — fire immediately. activeCommand is set
            // synchronously by updateCommandDetection so sendCommand
            // picks it up.
            slashBuffer = "/\(command.rawValue)"
            updateCommandDetection()
            sendCommand()
        }
    }

    func quickPanelDidDismiss() {
        dismissQuickPanel()
    }
}

// MARK: - VoiceInputController.Sink

extension KeyboardViewController: VoiceInputController.Sink {

    func onListeningStarted() {
        setMicListeningUI(true)
    }

    func onListeningStopped() {
        setMicListeningUI(false)
    }

    func onPartial(_ text: String) {
        appendDictation(text)
    }

    func onFinal(_ text: String) {
        appendDictation(text)
        voicePromptPrefix = nil
    }

    func onError(_ userVisibleMessage: String) {
        voicePromptPrefix = nil
        showBanner("⚠️ \(userVisibleMessage)")
    }

    private func appendDictation(_ spoken: String) {
        guard let prefix = voicePromptPrefix else { return }
        let trimmed = spoken.trimmingCharacters(in: .whitespacesAndNewlines)
        slashBuffer = prefix + trimmed
        updateCommandDetection()
    }
}
