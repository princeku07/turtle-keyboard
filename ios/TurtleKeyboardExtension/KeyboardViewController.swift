import UIKit
import PhotosUI

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

    /// PNG bytes of the image picked for the active `/edit` session, or
    /// `nil` when nothing is staged. Cleared after the command sends, when
    /// the command bar dismisses, or when the user picks again. Mirrors
    /// Android's `stagedImage` on `LmStudioAiClient`.
    private var stagedEditImage: Data?

    /// True while the PHPicker for `/edit` is on screen — prevents
    /// `showCommandBar(.edit)` from re-presenting on its second call
    /// (after the user picks and the picker dismisses).
    private var editPickerActive = false

    // While the user is composing a slash command, every keystroke is routed
    // into this buffer instead of `textDocumentProxy.insertText`. The host app
    // never sees `/ask …`; only the final result (or nothing, if cancelled)
    // reaches the text field. nil = normal typing.
    private var slashBuffer: String?

    // commandBar can show one of three things at a time
    private enum SuggestionMode { case none, slashCommand, replyResult, wordSuggestion, suggestedShortcuts }
    private var suggestionMode: SuggestionMode = .none
    private var pendingShortcuts: [SuggestedShortcut] = []

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
    // iPhone numbers tuned to match the iOS system keyboard (≈291pt total
    // portrait). Previously rowH=54, rowGap=12 made the keys look chunky
    // and the keyboard too tall.
    private var rowH:        CGFloat { isPad ? 42 : 44 }
    private var rowGap:      CGFloat { isPad ? 6  : 8 }
    private var commandBarH: CGFloat { isPad ? 40 : 44 }
    private var keyGap:      CGFloat { isPad ? 8  : 6 }
    private var bottomPad:   CGFloat { isPad ? 4  : 6 }

    // Command-bar internals shrink on iPhone — the prompt label needs to
    // claim what's left after cancel + pill + mic + send buttons, and
    // iPhone widths (375-393pt portrait) don't have headroom for the
    // generous iPad spacing.
    private var cmdSidePadding:   CGFloat { isPad ? 12 : 6 }
    private var cmdCancelW:       CGFloat { isPad ? 28 : 24 }
    private var cmdMicW:          CGFloat { isPad ? 32 : 28 }
    private var cmdMicH:          CGFloat { isPad ? 30 : 26 }
    private var cmdSendInsetH:    CGFloat { isPad ? 14 : 10 }
    private var cmdSendInsetV:    CGFloat { isPad ? 7  : 5 }

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
    private var cmdPresetStrip:      PresetChipStripView!
    /// 2 pt-wide blinking caret pinned to the right edge of the prompt
    /// label's rendered text. Visible whenever `cmdPromptLabel` is on
    /// screen so the user can tell which surface accepts their keystrokes.
    private var cmdCaret:            UIView!
    private var cmdCaretLeading:     NSLayoutConstraint!
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
        PollIntegration(),
        WyrIntegration(),
        WebIntegration(),
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
        resolveAndCacheTheme()
        setupContainers()
        buildKeyboard()
        // Set once — never changed again anywhere in this file.
        preferredContentSize = CGSize(width: 0, height: totalH)

        // Try to suppress the iPad system shortcut bar (undo / redo / clipboard).
        // This bar is owned by the host app's UITextField; a keyboard extension
        // cannot fully remove it, but emptying our own assistant item often
        // collapses it to zero on iPad.
        suppressSystemShortcutBar()

        // Re-apply when the user picks a new theme in the host app's
        // Personalization screen (writes into the shared App Group store,
        // then posts this notification on its way out).
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(themePreferenceDidChange),
            name: KeyboardThemeManager.preferenceDidChange,
            object: nil
        )
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        // When the user picked Auto, system Dark Mode flips should track.
        if previous?.userInterfaceStyle != traitCollection.userInterfaceStyle {
            applyTheme()
        }
    }

    @objc private func themePreferenceDidChange() {
        applyTheme()
    }

    private func resolveAndCacheTheme() {
        KeyboardPalette.current = KeyboardThemeManager.shared.resolve(
            store: personalizationStore,
            traitCollection: traitCollection
        )
    }

    /// Re-resolve the active theme, restamp container/banner colors that
    /// were set once at setup time, then rebuild the key grid so every
    /// button picks up the new palette via `KeyboardPalette.*`.
    private func applyTheme() {
        resolveAndCacheTheme()
        keyboardContainer?.backgroundColor = KeyboardPalette.bg
        commandBar?.backgroundColor       = KeyboardPalette.barBg
        bannerContainer?.backgroundColor  = KeyboardPalette.bannerBg
        // Integration panel host (Web etc.) — repaint if currently up.
        integrationPanelHost?.backgroundColor = KeyboardPalette.bg
        rebuildKeyboard()
    }

    private func suppressSystemShortcutBar() {
        inputAssistantItem.leadingBarButtonGroups  = []
        inputAssistantItem.trailingBarButtonGroups = []
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // Surface suggested-shortcut chips as soon as the keyboard mounts
        // on an empty field. Subsequent text changes refresh via textDidChange.
        updateWordSuggestions()
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        // If user cleared the field while the shortcuts strip was up, keep
        // it. If they typed past the empty state, word suggestions / hide
        // logic in updateWordSuggestions handle it.
        guard activeCommand == nil, !isGenerating else { return }
        updateWordSuggestions()
    }

    // MARK: - Image preview overlay
    //
    // After /org or /cap finishes, the resulting image is shown as a preview
    // covering the keyboard area. The user inspects it and either taps
    // "Copy to clipboard" (puts it on the pasteboard so they can long-press
    // and paste in the chat field) or "Close" to discard.

    private func showImagePreview(_ image: UIImage, command: String = "", prompt: String = "") {
        if previewOverlay == nil { buildPreviewOverlay() }
        pendingPreviewImage = image
        previewImageView?.image = image
        previewOverlay?.isHidden = false
        if let overlay = previewOverlay {
            keyboardContainer.bringSubviewToFront(overlay)
        }
        hideCommandBar()
        // Append to the persistent image history for the host-app
        // History screen. Skipped when caller didn't supply a command —
        // e.g. internal previews that aren't user-facing artifacts.
        if !command.isEmpty {
            ImageHistory.record(image: image, command: command, prompt: prompt)
        }
    }

    private func dismissPreview() {
        previewOverlay?.isHidden = true
        pendingPreviewImage = nil
    }

    @objc private func previewCloseTapped() {
        dismissPreview()
    }

    /// Variant buttons all funnel through here. `sender.tag` carries the
    /// `ImageVariants.Variant` raw index. Encodes the variant, drops it on
    /// the clipboard with the matching UTI, and surfaces a confirmation
    /// banner so the user knows what's been copied.
    @objc private func previewVariantTapped(_ sender: UIButton) {
        guard let img = pendingPreviewImage else { dismissPreview(); return }
        let variant: ImageVariants.Variant
        switch sender.tag {
        case 0: variant = .image
        case 1: variant = .sticker
        case 2: variant = .gif
        default: dismissPreview(); return
        }
        guard let result = ImageVariants.make(img, variant: variant) else {
            showBanner("⚠️ Couldn't encode \(variant.label.lowercased())")
            return
        }
        UIPasteboard.general.setData(result.data, forPasteboardType: result.uti)
        showBanner("📋 \(result.bannerNoun) copied — long-press field to paste")
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

        // Variant row — Image · Sticker · GIF · ✕. Mirrors Android's
        // ImagePreviewView 4-button layout. Variant buttons share a
        // single action via `sender.tag` so the encode/clipboard path
        // is centralized in `previewVariantTapped(_:)`.
        let brandGreen = UIColor(red: 0.106, green: 0.369, blue: 0.125, alpha: 1.0)
        func makeVariantButton(label: String, tag: Int) -> UIButton {
            let b = UIButton(type: .system)
            b.tag = tag
            b.setTitle(label, for: .normal)
            b.setTitleColor(brandGreen, for: .normal)
            b.titleLabel?.font = .boldSystemFont(ofSize: 13)
            b.backgroundColor = .white
            b.layer.cornerRadius = 8
            b.addTarget(self, action: #selector(previewVariantTapped(_:)), for: .touchUpInside)
            return b
        }
        let imageBtn   = makeVariantButton(label: "Image",   tag: 0)
        let stickerBtn = makeVariantButton(label: "Sticker", tag: 1)
        let gifBtn     = makeVariantButton(label: "GIF",     tag: 2)

        let closeBtn = UIButton(type: .system)
        closeBtn.setTitle("✕", for: .normal)
        closeBtn.setTitleColor(.white, for: .normal)
        closeBtn.titleLabel?.font = .systemFont(ofSize: 16, weight: .medium)
        closeBtn.backgroundColor = UIColor.white.withAlphaComponent(0.18)
        closeBtn.layer.cornerRadius = 8
        closeBtn.addTarget(self, action: #selector(previewCloseTapped), for: .touchUpInside)

        let buttonRow = UIStackView(arrangedSubviews: [imageBtn, stickerBtn, gifBtn, closeBtn])
        buttonRow.axis = .horizontal
        buttonRow.spacing = 6
        // Variant buttons share width; Close is narrower (matches Android's
        // 0.6 weight close-button + 1.0 weight variant buttons).
        imageBtn.translatesAutoresizingMaskIntoConstraints = false
        stickerBtn.translatesAutoresizingMaskIntoConstraints = false
        gifBtn.translatesAutoresizingMaskIntoConstraints = false
        closeBtn.translatesAutoresizingMaskIntoConstraints = false
        stickerBtn.widthAnchor.constraint(equalTo: imageBtn.widthAnchor).isActive = true
        gifBtn.widthAnchor.constraint(equalTo: imageBtn.widthAnchor).isActive = true
        closeBtn.widthAnchor.constraint(equalTo: imageBtn.widthAnchor, multiplier: 0.5).isActive = true
        buttonRow.translatesAutoresizingMaskIntoConstraints = false
        overlay.addSubview(buttonRow)

        NSLayoutConstraint.activate([
            title.topAnchor.constraint(equalTo: overlay.topAnchor, constant: 8),
            title.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),

            imageView.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 6),
            imageView.centerXAnchor.constraint(equalTo: overlay.centerXAnchor),
            imageView.bottomAnchor.constraint(equalTo: buttonRow.topAnchor, constant: -10),
            imageView.widthAnchor.constraint(equalTo: imageView.heightAnchor),
            imageView.leadingAnchor.constraint(greaterThanOrEqualTo: overlay.leadingAnchor, constant: 16),

            buttonRow.leadingAnchor.constraint(equalTo: overlay.leadingAnchor, constant: 12),
            buttonRow.trailingAnchor.constraint(equalTo: overlay.trailingAnchor, constant: -12),
            buttonRow.bottomAnchor.constraint(equalTo: overlay.bottomAnchor, constant: -10),
            buttonRow.heightAnchor.constraint(equalToConstant: 38),
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

        // Prompt preview — must stretch to fill whatever space the fixed
        // elements (pill + mic + send) leave behind, and shrink (with
        // truncation) before any of those clip when the row is narrow.
        cmdPromptLabel = UILabel()
        cmdPromptLabel.font      = .systemFont(ofSize: 15)
        cmdPromptLabel.textColor = UIColor.white.withAlphaComponent(0.45)
        cmdPromptLabel.lineBreakMode = .byTruncatingTail
        cmdPromptLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        cmdPromptLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        cmdPromptLabel.translatesAutoresizingMaskIntoConstraints = false
        commandBar.addSubview(cmdPromptLabel)

        // Preset chip strip — shares the prompt-label's slot. Visible only
        // while a needsPrompt command is active, the user hasn't typed
        // anything yet, AND the command has presets in PresetCatalog.
        cmdPresetStrip = PresetChipStripView()
        cmdPresetStrip.translatesAutoresizingMaskIntoConstraints = false
        cmdPresetStrip.isHidden = true
        commandBar.addSubview(cmdPresetStrip)

        // Blinking text caret — visual cue that the prompt label is the
        // current write surface. Positioned right after the rendered text
        // via a dynamic leading constraint that `updateCaretPosition()`
        // refreshes whenever the label text changes.
        cmdCaret = UIView()
        cmdCaret.translatesAutoresizingMaskIntoConstraints = false
        cmdCaret.backgroundColor = .white
        cmdCaret.layer.cornerRadius = 1
        cmdCaret.isHidden = true
        commandBar.addSubview(cmdCaret)

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
        cmdSendButton.contentEdgeInsets  = UIEdgeInsets(top: cmdSendInsetV, left: cmdSendInsetH, bottom: cmdSendInsetV, right: cmdSendInsetH)
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
        bannerContainer.backgroundColor = KeyboardPalette.bannerBg
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

            // Command bar internals — most distances narrow on iPhone via
            // cmdSidePadding / cmdCancelW / cmdMicW / cmdMicH so the
            // prompt label has room to breathe on a 393pt-wide row.
            cmdCancelButton.leadingAnchor.constraint(equalTo: commandBar.leadingAnchor, constant: cmdSidePadding),
            cmdCancelButton.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdCancelButton.widthAnchor.constraint(equalToConstant: cmdCancelW),

            cmdPill.leadingAnchor.constraint(equalTo: cmdCancelButton.trailingAnchor, constant: 4),
            cmdPill.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdPill.heightAnchor.constraint(equalToConstant: 26),

            cmdPromptLabel.leadingAnchor.constraint(equalTo: cmdPill.trailingAnchor, constant: 6),
            cmdPromptLabel.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdPromptLabel.trailingAnchor.constraint(equalTo: cmdMicButton.leadingAnchor, constant: -6),

            cmdPresetStrip.leadingAnchor.constraint(equalTo: cmdPill.trailingAnchor, constant: 6),
            cmdPresetStrip.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdPresetStrip.trailingAnchor.constraint(equalTo: cmdMicButton.leadingAnchor, constant: -6),
            cmdPresetStrip.heightAnchor.constraint(equalToConstant: 30),

            cmdCaret.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdCaret.widthAnchor.constraint(equalToConstant: 2),
            cmdCaret.heightAnchor.constraint(equalToConstant: 18),

            cmdMicButton.trailingAnchor.constraint(equalTo: cmdSendButton.leadingAnchor, constant: -4),
            cmdMicButton.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdMicButton.widthAnchor.constraint(equalToConstant: cmdMicW),
            cmdMicButton.heightAnchor.constraint(equalToConstant: cmdMicH),

            cmdSpinner.centerXAnchor.constraint(equalTo: cmdSendButton.centerXAnchor),
            cmdSpinner.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),

            cmdSendButton.trailingAnchor.constraint(equalTo: commandBar.trailingAnchor, constant: -cmdSidePadding),
            cmdSendButton.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),

            // Suggestions stack fills the space to the right of the cancel button
            cmdSuggestionsStack.leadingAnchor.constraint(equalTo: cmdCancelButton.trailingAnchor, constant: 4),
            cmdSuggestionsStack.trailingAnchor.constraint(equalTo: commandBar.trailingAnchor, constant: -cmdSidePadding),
            cmdSuggestionsStack.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdSuggestionsStack.heightAnchor.constraint(equalToConstant: 36),
        ])

        // Caret's leading constraint is dynamic — `updateCaretPosition()`
        // mutates `.constant` to follow the rendered text width.
        cmdCaretLeading = cmdCaret.leadingAnchor.constraint(
            equalTo: cmdPromptLabel.leadingAnchor, constant: 0)
        cmdCaretLeading.isActive = true
        startCaretBlink()
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

        let specialText = KeyboardPalette.keyTextSpecial
        let normalText  = KeyboardPalette.keyText

        switch label {
        case "🌐":
            btn.setImage(UIImage(systemName: "globe", withConfiguration: c15r), for: .normal)
            btn.tintColor = specialText; btn.backgroundColor = keySpecial
        case "↵":
            btn.setImage(UIImage(systemName: "return", withConfiguration: c16), for: .normal)
            btn.tintColor = specialText; btn.backgroundColor = keySpecial
        case "⇧" where isShiftActive:
            btn.setImage(UIImage(systemName: isCapsLock ? "capslock.fill" : "shift.fill",
                                 withConfiguration: c15), for: .normal)
            btn.tintColor = specialText; btn.backgroundColor = keyShiftOn
        case "⇧":
            btn.setImage(UIImage(systemName: "shift", withConfiguration: c15), for: .normal)
            btn.tintColor = specialText; btn.backgroundColor = keySpecial
        case "⌫":
            btn.setImage(UIImage(systemName: "delete.backward", withConfiguration: c15r), for: .normal)
            btn.tintColor = specialText; btn.backgroundColor = keySpecial
        case "space":
            btn.setTitle("space", for: .normal)
            btn.titleLabel?.font = .systemFont(ofSize: 16)
            btn.setTitleColor(normalText.withAlphaComponent(0.7), for: .normal)
            btn.backgroundColor = keyNormal
        case _ where isSpecial(label):
            btn.setTitle(label, for: .normal)
            btn.titleLabel?.font = .systemFont(ofSize: 15, weight: .medium)
            btn.setTitleColor(specialText, for: .normal)
            btn.backgroundColor = keySpecial
        default:
            btn.setTitle(displayTitle(for: label), for: .normal)
            btn.titleLabel?.font = .systemFont(ofSize: 22, weight: .light)
            btn.setTitleColor(normalText, for: .normal)
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

    /// Order the user sees in the panel grid. AI-routed commands come
    /// first in stable declaration order; the local integration commands
    /// follow, sourced from `integrationRegistry.allCommands` so the
    /// user's Personalization toggles are honoured — disabling Poll on
    /// the host strips it from the grid here.
    private func allCommandsForQuickPanel() -> [SlashCommand] {
        let aiCommands: [SlashCommand] = [.cap, .edit, .fix, .tone, .reply, .tl, .ask, .org]
        // Walk the live registry so commands added later (poll, wyr,
        // web, …) flow through automatically without touching this list.
        let localCommands: [SlashCommand] = integrationRegistry.allCommands.compactMap {
            SlashCommand(rawValue: $0.name.lowercased())
        }
        return aiCommands + localCommands
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
            // No word at cursor — empty / freshly cleared field. Offer the
            // suggested-shortcut chips if this field accepts them.
            if suggestionMode == .wordSuggestion { hideCommandBar() }
            updateSuggestedShortcuts()
            return
        }
        let currentWord = context.substring(with: range)

        // Skip while user is composing a slash command
        guard !currentWord.hasPrefix("/"), currentWord.count >= 2 else {
            if suggestionMode == .wordSuggestion { hideCommandBar() }
            // Single character or slash buffer — no UITextChecker payload,
            // but still no useful shortcuts to show either; hide if up.
            if suggestionMode == .suggestedShortcuts { hideCommandBar() }
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

    // MARK: - Suggested shortcuts (per-field templates)
    //
    // Port of android/.../SuggestedShortcut. iOS keys the catalog off field
    // traits (UITextInputTraits) rather than Android's `EditorInfo.packageName`
    // since extensions can't read the host bundle id.

    private func updateSuggestedShortcuts() {
        guard activeCommand == nil, !isGenerating, commandBar.isHidden || suggestionMode == .suggestedShortcuts else {
            return
        }
        // Only offer on a freshly empty field.
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        let after  = textDocumentProxy.documentContextAfterInput  ?? ""
        guard before.isEmpty, after.isEmpty else {
            if suggestionMode == .suggestedShortcuts { hideCommandBar() }
            return
        }

        guard let proxy = textDocumentProxy as? (UITextDocumentProxy & UITextInputTraits) else { return }
        let kind = FieldKind.from(InputContext(proxy: proxy))
        let shortcuts = Array(SuggestedShortcutCatalog.shortcuts(for: kind).prefix(3))
        guard !shortcuts.isEmpty else {
            if suggestionMode == .suggestedShortcuts { hideCommandBar() }
            return
        }
        showSuggestedShortcuts(shortcuts)
    }

    private func showSuggestedShortcuts(_ shortcuts: [SuggestedShortcut]) {
        suggestionMode   = .suggestedShortcuts
        pendingShortcuts = shortcuts

        for (i, btn) in cmdSuggestionBtns.enumerated() {
            if i < shortcuts.count {
                let s = shortcuts[i]
                btn.setTitle("\(s.emoji) \(s.label)", for: .normal)
                btn.isHidden = false
            } else {
                btn.setTitle(nil, for: .normal)
                btn.isHidden = true
            }
        }
        [cmdPill, cmdPromptLabel, cmdSendButton, cmdCancelButton].forEach { $0.isHidden = true }
        cmdSuggestionsStack.isHidden = false

        if commandBar.isHidden {
            commandBar.alpha = 0
            commandBar.isHidden = false
            UIView.animate(withDuration: 0.15) { self.commandBar.alpha = 1 }
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
        cmdPresetStrip?.isHidden = true
        cmdSuggestionsStack.isHidden = false
        updateCaret()

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

        // /edit needs a reference image before the user can describe an
        // edit — fire the system image picker on first entry. The picker
        // callback re-invokes `showCommandBar(.edit)`, at which point
        // `stagedEditImage` is non-nil and we fall through to render.
        if cmd == .edit, stagedEditImage == nil, !editPickerActive {
            editPickerActive = true
            presentEditImagePicker()
            return
        }

        // Coming from word-suggestion mode? Restore normal controls first.
        [cmdPill, cmdPromptLabel, cmdSendButton, cmdCancelButton].forEach { $0.isHidden = false }
        cmdSuggestionsStack.isHidden = true

        cmdPill.text = "  \(cmd.emoji) /\(cmd.rawValue)  "
        cmdSendButton.setTitle(cmd.buttonTitle, for: .normal)

        // Surface the preset chip strip only on a fresh, prompt-needing
        // command when the catalog has presets for it. As soon as the
        // user starts typing, the strip yields the slot back to the
        // prompt label so the typed text is visible.
        let presets = PresetCatalog.presets(for: cmd.rawValue)
        let showPresets = cmd.needsPrompt && commandPromptText.isEmpty && !presets.isEmpty
        if showPresets {
            cmdPresetStrip.setPresets(presets) { [weak self] value in
                self?.handlePresetTap(value)
            }
            cmdPresetStrip.isHidden = false
            cmdPromptLabel.isHidden = true
        } else {
            cmdPresetStrip.isHidden = true
            cmdPromptLabel.isHidden = false
            if commandPromptText.isEmpty {
                if cmd == .edit && stagedEditImage != nil {
                    cmdPromptLabel.text      = "📎 image ready · describe the edit…"
                } else {
                    cmdPromptLabel.text      = cmd.needsPrompt ? "type prompt above…" : "ready — tap \(cmd.buttonTitle)"
                }
                cmdPromptLabel.textColor = UIColor.white.withAlphaComponent(0.40)
            } else {
                cmdPromptLabel.text      = commandPromptText
                cmdPromptLabel.textColor = UIColor.white.withAlphaComponent(0.90)
            }
        }

        if commandBar.isHidden {
            commandBar.alpha  = 0
            commandBar.isHidden = false
            UIView.animate(withDuration: 0.18) { self.commandBar.alpha = 1 }
        }
        commandBar.layoutIfNeeded()
        updateCaret()
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
        commandBar.layoutIfNeeded()
        updateCaret()
    }

    private func hideCommandBar() {
        guard !commandBar.isHidden, !isGenerating else { return }
        activeCommand = nil
        pendingSuggestions = []
        suggestionMode = .none
        // Drop any staged /edit reference — the user backed out before
        // describing the edit. Re-entering /edit will re-launch the picker.
        stagedEditImage = nil
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
        // Consume the staged image for /edit (cleared after the request
        // builds so a failed call doesn't trap the bytes in memory).
        let referenceImage: Data? = (cmd == .edit) ? stagedEditImage : nil
        if cmd == .edit { stagedEditImage = nil }
        Task { @MainActor in
            do {
                let result = try await CommandRouter.shared.execute(
                    command: cmd.rawValue,
                    prompt: prompt,
                    context: context,
                    referenceImage: referenceImage
                )
                isGenerating = false
                cmdSpinner.stopAnimating()
                cmdSendButton.isHidden = false

                switch result {
                case .text(let text):
                    if cmd == .org {
                        hideCommandBar()
                        if let image = OrgImageRenderer.render(json: text) {
                            showImagePreview(image, command: cmd.rawValue, prompt: prompt)
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
                            showImagePreview(image, command: cmd.rawValue, prompt: prompt)
                        }
                    } catch {
                        showBanner("⚠️ Image download failed")
                    }

                case .imageData(let data):
                    hideCommandBar()
                    if let image = UIImage(data: data) {
                        showImagePreview(image, command: cmd.rawValue, prompt: prompt)
                    } else {
                        showBanner("⚠️ Image decode failed")
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

        if suggestionMode == .suggestedShortcuts {
            guard i < pendingShortcuts.count else { return }
            let shortcut = pendingShortcuts[i]
            pendingShortcuts = []
            suggestionMode = .none
            hideCommandBar()
            textDocumentProxy.insertText(shortcut.template)
            // Template may include text or a "/cmd " seed — re-evaluate so
            // word suggestions / shortcut strip update for the new state.
            updateWordSuggestions()
            return
        }

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
        cmdPresetStrip?.isHidden = true
        updateCaret()
    }

    // MARK: - Prompt caret
    //
    // The cmdPromptLabel is a UILabel (not a UITextField), so iOS doesn't
    // give us a system caret for free. We draw our own — a 2 pt-wide
    // white pill animated 1.0 → 0.0 → 1.0 forever — pinned to the end of
    // the rendered text via a leading constraint whose `.constant` we
    // recompute on every text change.

    private func startCaretBlink() {
        cmdCaret.alpha = 1
        UIView.animateKeyframes(
            withDuration: 1.0,
            delay: 0,
            options: [.repeat, .allowUserInteraction, .calculationModeLinear],
            animations: { [weak self] in
                UIView.addKeyframe(withRelativeStartTime: 0.0, relativeDuration: 0.5) {
                    self?.cmdCaret.alpha = 1
                }
                UIView.addKeyframe(withRelativeStartTime: 0.5, relativeDuration: 0.5) {
                    self?.cmdCaret.alpha = 0
                }
            },
            completion: nil
        )
    }

    /// Re-evaluate caret visibility + position. Called whenever the label
    /// text changes or the command-bar mode flips.
    private func updateCaret() {
        guard let label = cmdPromptLabel else { return }
        // Only show the caret when the prompt label is the visible
        // surface — preset strip / word suggestions / hidden bar all
        // suppress it.
        let visible = !label.isHidden && !commandBar.isHidden
        cmdCaret.isHidden = !visible
        guard visible else { return }
        updateCaretPosition()
    }

    private func updateCaretPosition() {
        guard let label = cmdPromptLabel,
              let text = label.text, !text.isEmpty,
              let font = label.font else {
            cmdCaretLeading.constant = 0
            return
        }
        let size = (text as NSString).size(withAttributes: [.font: font])
        // Cap inside the label's frame width so the caret can't drift
        // past the mic button when the prompt overflows.
        let maxWidth = max(0, label.bounds.width - 2)
        cmdCaretLeading.constant = min(size.width, maxWidth) + 1
    }

    /// Tap callback from `cmdPresetStrip`. Treats the chip value as the
    /// user's prompt for the active command and fires immediately —
    /// mirrors Android's `PresetChipStripView.onTap`.
    private func handlePresetTap(_ value: String) {
        guard let cmd = activeCommand else { return }
        commandPromptText = value
        cmdPresetStrip.isHidden = true
        cmdPromptLabel.isHidden = false
        cmdPromptLabel.text = value
        cmdPromptLabel.textColor = UIColor.white.withAlphaComponent(0.90)
        commandBar.layoutIfNeeded()
        updateCaret()
        executeCommand(cmd, prompt: value)
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
        // Prefer Gemini when a key is wired in .env; otherwise fall back to
        // the LAN LM Studio endpoint so devs without a key still get a model.
        if !Secrets.geminiApiKey.isEmpty {
            self.llm = GeminiLlmService(apiKey: Secrets.geminiApiKey)
        } else {
            self.llm = LMStudioLlmService()
        }
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

    func openExternalURL(_ url: URL) {
        DispatchQueue.main.async {
            self.owner?.extensionContext?.open(url, completionHandler: nil)
        }
    }
}

/// LlmService backed by Google Gemini's generateContent endpoint. Used when
/// `Secrets.geminiApiKey` is set via the `.env` loader. Posts the prompt as
/// a single user message; returns the text part of the first candidate.
private final class GeminiLlmService: LlmService {
    private let apiKey: String
    private let model: String

    init(apiKey: String, model: String = "gemini-2.5-flash-lite") {
        self.apiKey = apiKey
        self.model = model
    }

    func complete(
        prompt: String,
        onText: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        let urlStr = "https://generativelanguage.googleapis.com/v1beta/models/\(model):generateContent?key=\(apiKey)"
        guard let url = URL(string: urlStr) else {
            onError("bad Gemini URL"); return
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 30
        let body: [String: Any] = [
            "contents": [["parts": [["text": prompt]]]],
            "generationConfig": ["maxOutputTokens": 1024, "temperature": 0.7],
        ]
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        URLSession.shared.dataTask(with: req) { data, resp, err in
            if let err = err { onError(err.localizedDescription); return }
            let status = (resp as? HTTPURLResponse)?.statusCode ?? -1
            guard let data = data else { onError("empty response"); return }
            if !(200..<300).contains(status) {
                let body = String(data: data, encoding: .utf8)?.prefix(200) ?? ""
                onError("gemini \(status): \(body)"); return
            }
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let candidates = json["candidates"] as? [[String: Any]],
                  let content = candidates.first?["content"] as? [String: Any],
                  let parts = content["parts"] as? [[String: Any]],
                  let text = parts.first?["text"] as? String else {
                let snippet = String(data: data, encoding: .utf8)?.prefix(200) ?? ""
                onError("unexpected gemini shape: \(snippet)"); return
            }
            onText(text.trimmingCharacters(in: .whitespacesAndNewlines))
        }.resume()
    }
}

/// Minimal LlmService backed by the same LM Studio endpoint the rest of
/// the keyboard's text commands hit. Posts a single user message; returns
/// the assistant's content stripped of any `<think>…</think>` block that
/// reasoning models prepend.
private final class LMStudioLlmService: LlmService {
    private let endpoint = URL(string: "http://192.168.0.106:1234/api/v1/chat")!
    /// Must match the model loaded in LM Studio. Keep in sync with
    /// `LMStudioProvider`'s default.
    private let model = "google/gemma-4-e4b"

    func complete(
        prompt: String,
        onText: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.timeoutInterval = 30
        // Custom (non-OpenAI) chat API: { model, system_prompt, input }.
        // Notion's bridge sends a single combined prompt — pass it as
        // `input` and leave `system_prompt` empty so the model treats
        // the whole thing as the user message.
        let body: [String: Any] = [
            "model": model,
            "system_prompt": "",
            "input": prompt,
        ]
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        URLSession.shared.dataTask(with: req) { data, resp, err in
            if let err = err { onError(err.localizedDescription); return }
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else { onError("non-JSON LLM response"); return }
            let content = Self.extractContent(from: json)
            guard let raw = content else {
                let snippet = String(data: data, encoding: .utf8)?.prefix(200) ?? ""
                onError("unexpected LLM shape: \(snippet)"); return
            }
            // Strip reasoning model think blocks the same way LMStudioProvider does.
            let cleaned = raw
                .replacingOccurrences(of: #"(?is)<think>.*?</think>"#,
                                       with: "", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            onText(cleaned)
        }.resume()
    }

    /// Same defensive parser as `LMStudioProvider.extractContent`.
    /// Handles `output: [{type, content}]` (new), single-string forms,
    /// and OpenAI's nested shape.
    private static func extractContent(from json: [String: Any]) -> String? {
        if let outputArr = json["output"] as? [[String: Any]] {
            if let msg = outputArr.first(where: { ($0["type"] as? String) == "message" }),
               let content = msg["content"] as? String, !content.isEmpty {
                return content
            }
            let merged = outputArr.compactMap { $0["content"] as? String }
                                   .joined(separator: "\n")
            if !merged.isEmpty { return merged }
        }
        for key in ["output", "response", "text", "content", "answer", "result"] {
            if let s = json[key] as? String, !s.isEmpty { return s }
        }
        if let choices = json["choices"] as? [[String: Any]],
           let message = choices.first?["message"] as? [String: Any],
           let content = message["content"] as? String {
            return content
        }
        return nil
    }
}

// MARK: - PHPickerViewControllerDelegate (/edit image picker)
//
// `/edit` needs a reference image from the user's Photos library.
// `PHPickerViewController` is the only library-access path that works
// from a keyboard extension — it's an out-of-process picker, no Photos
// permission needed (the system returns just what the user selects),
// and it presents like a normal modal from `self.present(_:animated:)`.

@available(iOS 14.0, *)
extension KeyboardViewController: PHPickerViewControllerDelegate {

    func presentEditImagePicker() {
        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.filter = .images
        config.selectionLimit = 1
        config.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self
        picker.modalPresentationStyle = .fullScreen
        present(picker, animated: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true) { [weak self] in
            guard let self = self else { return }
            self.editPickerActive = false
            guard let provider = results.first?.itemProvider,
                  provider.canLoadObject(ofClass: UIImage.self) else {
                // User cancelled or picked something unreadable — keep the
                // command bar open at /edit with no staged image; user can
                // tap the bar's `×` to back out or try again.
                self.showCommandBar(.edit)
                return
            }
            provider.loadObject(ofClass: UIImage.self) { object, _ in
                let image = object as? UIImage
                DispatchQueue.main.async {
                    if let img = image, let png = ImageDownsizer.downsizedPNG(img) {
                        self.stagedEditImage = png
                    }
                    self.showCommandBar(.edit)
                }
            }
        }
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
