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

    /// True while the PHPicker for `/edit` or `/style` is on screen —
    /// prevents `showCommandBar` from re-presenting on its second call
    /// (after the user picks and the picker dismisses).
    private var editPickerActive = false

    /// The command that launched the photo picker (`/edit` or `/style`).
    /// Used by the picker callback to re-enter the right command bar with
    /// the staged image. Cleared after the callback re-routes.
    private var pickerSourceCommand: SlashCommand?

    /// Top slash-command match for the current draft buffer, or `nil`
    /// when nothing matches. Drives both the ghost completion in the
    /// prompt label and the Send button's tap-to-accept behaviour.
    private var slashAutocompleteTopMatch: String?

    /// Explicit caret-position override in points. When non-nil,
    /// `updateCaretPosition()` uses this instead of measuring the full
    /// label text — keeps the caret between the typed prefix and the
    /// ghost completion in draft mode.
    private var caretAnchorWidth: CGFloat?

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
    private var cmdPromptScrollView: UIScrollView!
    private var cmdPromptLabel:      UILabel!
    private var cmdSendButton:       UIButton!
    private var cmdMicButton:        UIButton!
    private var cmdSpinner:          UIActivityIndicatorView!

    /// Caret position within the **prompt portion** of `slashBuffer` (i.e.
    /// the substring after the first space). Nil while we're still in draft
    /// mode (no committed command yet); after committing, defaults to the
    /// end of the prompt so typing keeps appending as before. The user can
    /// tap inside the prompt scroll view to move the caret mid-text, after
    /// which typing inserts and backspace deletes at this index.
    private var promptCaretIndex:    Int?

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
    /// Strip of slash-command matches, mounted just below the command
    /// bar above the keys. Matches Android's `CommandSuggestionStripView`
    /// behaviour 1:1.
    private var slashStrip:          CommandSuggestionStripView!
    private var slashStripHeight:    NSLayoutConstraint!
    private let slashStripH: CGFloat = 34
    private var heightConstraint:    NSLayoutConstraint!
    private var hideBannerTimer:     Timer?
    private var backspaceTimer:      Timer?
    private var previewOverlay:      UIView?
    private var previewImageView:    UIImageView?
    private var pendingPreviewImage: UIImage?

    /// Raw bytes of the source the preview was built from (PNG for image
    /// commands, GIF89a for `/gif`). Kept verbatim so that tapping the
    /// **GIF** variant pill on a `/gif` result pastes the original
    /// animated bytes instead of a single-frame re-encode of the
    /// preview's first frame.
    private var pendingPreviewSourceData: Data?

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
        // Initial height includes the always-reserved command-bar slot at
        // the top so word-suggestion chips don't shove the keys when they
        // appear. `recomputeKeyboardHeight` grows / shrinks from here when
        // the slash strip toggles or the image-preview chrome takes over.
        let initialH = effectiveChromeH + rowsH
        heightConstraint.constant = initialH
        preferredContentSize = CGSize(width: 0, height: initialH)

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
        // Slash autocomplete strip + every styled element inside the
        // command bar (pill, prompt label, send, mic, suggestion chips).
        slashStrip?.applyTheme()
        restampCommandBarColors()
        rebuildKeyboard()
    }

    /// Restamp every hardcoded `UIColor.white` / `barText` site in the
    /// command bar so the bar follows the active theme.
    private func restampCommandBarColors() {
        let text = KeyboardPalette.barText
        let chipBg = KeyboardPalette.chipBg

        cmdPill?.backgroundColor = chipBg
        cmdPill?.textColor = text
        cmdPromptLabel?.textColor = text.withAlphaComponent(0.90)
        cmdMicButton?.tintColor = text.withAlphaComponent(0.85)
        cmdMicButton?.backgroundColor = chipBg.withAlphaComponent(0.7)
        cmdSendButton?.setTitleColor(text, for: .normal)
        cmdSendButton?.backgroundColor = chipBg
        cmdCaret?.backgroundColor = text

        for btn in cmdSuggestionBtns {
            btn.setTitleColor(text, for: .normal)
            btn.backgroundColor = chipBg
        }

        bannerLabel?.textColor = text
    }

    private func suppressSystemShortcutBar() {
        inputAssistantItem.leadingBarButtonGroups  = []
        inputAssistantItem.trailingBarButtonGroups = []
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // If the user just returned from the Turtle host app after dictating,
        // a transcript is sitting in the App Group waiting to be inserted.
        // The Darwin notification handles the live case; this catches the
        // suspended-appex case where we missed the notification.
        voiceController.consumePendingTranscript()
        // Re-hydrate any in-progress slash draft the user left behind when
        // they switched apps (e.g. `/ca`, `/cap a samurai cat`). Must run
        // before updateWordSuggestions — when the draft restores, the
        // command bar takes over the suggestion slot.
        restoreDraftStateIfFresh()
        // Surface suggested-shortcut chips as soon as the keyboard mounts
        // on an empty field. Subsequent text changes refresh via textDidChange.
        updateWordSuggestions()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Stash the in-progress slash draft so the next keyboard mount
        // (returning from another app) picks it up where the user left off.
        persistDraftState()
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

    private func showImagePreview(_ image: UIImage,
                                  sourceData: Data? = nil,
                                  command: String = "",
                                  prompt: String = "") {
        if previewOverlay == nil { buildPreviewOverlay() }
        pendingPreviewImage = image
        pendingPreviewSourceData = sourceData
        previewImageView?.image = image
        // UIImageView auto-animates an `image` built via
        // `UIImage.animatedImage(with:duration:)` once it's in a window —
        // but calling startAnimating explicitly here is cheap and makes
        // the behaviour deterministic across iOS versions / view-state
        // edge cases (e.g. re-entering the preview after dismiss).
        if image.images != nil { previewImageView?.startAnimating() }
        previewOverlay?.isHidden = false
        if let overlay = previewOverlay {
            keyboardContainer.bringSubviewToFront(overlay)
        }
        hideCommandBar()
        // hideCommandBar's animation completion will recompute the height
        // ~0.15s later; force a recompute now so the preview has the full
        // keyboard canvas immediately and isPreviewVisible is honored even
        // if the command bar was already hidden (guard would skip it).
        recomputeKeyboardHeight()
        // Append to the persistent image history for the host-app
        // History screen. Skipped when caller didn't supply a command —
        // e.g. internal previews that aren't user-facing artifacts.
        if !command.isEmpty {
            ImageHistory.record(image: image, command: command, prompt: prompt)
        }
    }

    private func dismissPreview() {
        previewOverlay?.isHidden = true
        previewImageView?.stopAnimating()
        pendingPreviewImage = nil
        pendingPreviewSourceData = nil
        recomputeKeyboardHeight()
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

        // /gif fast-path: when the source is already an animated GIF89a
        // (produced by `GoogleProvider.runGifPipeline`) and the user tapped
        // the GIF variant pill, paste the original bytes verbatim. The
        // `ImageVariants.encodeGIF` re-encode path would only see the
        // first frame (UIImage doesn't carry multi-frame GIF data without
        // `animatedImage(with:duration:)`) and would silently produce a
        // single-frame still — which is what WhatsApp / iMessage were
        // showing in the user's chats.
        if variant == .gif, let raw = pendingPreviewSourceData, Self.isAnimatedGIF(raw) {
            UIPasteboard.general.setData(raw, forPasteboardType: UTType.gif.identifier)
            showBanner("📋 GIF copied — long-press field to paste")
            dismissPreview()
            return
        }

        guard let result = ImageVariants.make(img, variant: variant) else {
            showBanner("⚠️ Couldn't encode \(variant.label.lowercased())")
            return
        }
        UIPasteboard.general.setData(result.data, forPasteboardType: result.uti)
        showBanner("📋 \(result.bannerNoun) copied — long-press field to paste")
        dismissPreview()
    }

    /// True when `data` begins with the GIF magic (`"GIF87a"` or `"GIF89a"`).
    /// The 4-byte prefix `"GIF8"` is enough to distinguish from PNG/JPEG.
    private static func isAnimatedGIF(_ data: Data) -> Bool {
        guard data.count >= 4 else { return false }
        return data[0] == 0x47 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x38
    }

    /// Build an animated `UIImage` from raw GIF data so the preview's
    /// `UIImageView` plays the animation. Falls back to nil when the
    /// data isn't actually a multi-frame GIF — caller should then use
    /// `UIImage(data:)` to get a still.
    private static func animatedImage(fromGIFData data: Data) -> UIImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else {
            return nil
        }
        let count = CGImageSourceGetCount(src)
        guard count > 1 else { return nil }
        var frames: [UIImage] = []
        var totalDuration: TimeInterval = 0
        frames.reserveCapacity(count)
        for i in 0..<count {
            guard let cg = CGImageSourceCreateImageAtIndex(src, i, nil) else { continue }
            frames.append(UIImage(cgImage: cg))
            // Prefer the unclamped delay; viewers like Twitter ignore the
            // clamped one when both are present. Default to 100 ms if the
            // encoder didn't write a delay.
            var delay: TimeInterval = 0.1
            if let props = CGImageSourceCopyPropertiesAtIndex(src, i, nil) as? [String: Any],
               let gif = props[kCGImagePropertyGIFDictionary as String] as? [String: Any] {
                if let d = gif[kCGImagePropertyGIFUnclampedDelayTime as String] as? Double, d > 0 {
                    delay = d
                } else if let d = gif[kCGImagePropertyGIFDelayTime as String] as? Double, d > 0 {
                    delay = d
                }
            }
            totalDuration += delay
        }
        guard !frames.isEmpty else { return nil }
        return UIImage.animatedImage(with: frames, duration: totalDuration)
    }

    private func buildPreviewOverlay() {
        let overlay = UIView()
        overlay.backgroundColor = bgColor
        overlay.translatesAutoresizingMaskIntoConstraints = false
        keyboardContainer.addSubview(overlay)
        // Preview is a fixed-height chrome slot at the TOP of the keyboard
        // — the keys stay mounted underneath at their normal y-origin
        // (effectiveChromeH bumps to previewH while preview is up). This
        // mirrors the Android layout: image + variant pills above, full
        // QWERTY below.
        NSLayoutConstraint.activate([
            overlay.topAnchor.constraint(equalTo: keyboardContainer.topAnchor),
            overlay.leadingAnchor.constraint(equalTo: keyboardContainer.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: keyboardContainer.trailingAnchor),
            overlay.heightAnchor.constraint(equalToConstant: previewH),
        ])

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
            imageView.topAnchor.constraint(equalTo: overlay.topAnchor, constant: 12),
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
        // ── Keyboard container — fills the input view exactly so iOS
        //    actually resizes the host's keyboard region when we change
        //    `heightConstraint`. Anchoring just to `view.bottomAnchor`
        //    with a height constraint on the container itself caused the
        //    container to overflow upward into the host app whenever we
        //    grew it past `rowsH` (slash strip / banner). Putting the
        //    constraint on `view.heightAnchor` instead makes iOS adjust
        //    the actual keyboard frame.
        keyboardContainer = UIView()
        keyboardContainer.backgroundColor = bgColor
        keyboardContainer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(keyboardContainer)

        // Container is glued to all four sides of view; view drives the
        // size via its own height constraint.
        heightConstraint = view.heightAnchor.constraint(equalToConstant: totalH)
        heightConstraint.priority = .required - 1

        NSLayoutConstraint.activate([
            keyboardContainer.topAnchor.constraint(equalTo: view.topAnchor),
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

        // Command pill  "🎨 /cap"
        cmdPill = UILabel()
        cmdPill.font               = .monospacedSystemFont(ofSize: 12, weight: .bold)
        cmdPill.textColor          = .white
        cmdPill.backgroundColor    = KeyboardPalette.chipBg
        cmdPill.textColor          = KeyboardPalette.barText
        cmdPill.layer.cornerRadius = 5
        cmdPill.clipsToBounds      = true
        cmdPill.textAlignment      = .center
        cmdPill.setContentHuggingPriority(.required, for: .horizontal)
        cmdPill.setContentCompressionResistancePriority(.required, for: .horizontal)
        cmdPill.translatesAutoresizingMaskIntoConstraints = false
        commandBar.addSubview(cmdPill)

        // Prompt area — a horizontal scroll view wrapping the prompt label
        // so long prompts don't truncate. The user can swipe the prompt
        // text and tap-to-position the caret (see `handlePromptTap`).
        cmdPromptScrollView = UIScrollView()
        cmdPromptScrollView.showsHorizontalScrollIndicator = false
        cmdPromptScrollView.showsVerticalScrollIndicator = false
        cmdPromptScrollView.alwaysBounceVertical = false
        cmdPromptScrollView.translatesAutoresizingMaskIntoConstraints = false
        commandBar.addSubview(cmdPromptScrollView)

        cmdPromptLabel = UILabel()
        cmdPromptLabel.font      = .systemFont(ofSize: 15)
        cmdPromptLabel.textColor = KeyboardPalette.barText.withAlphaComponent(0.45)
        // The scroll view owns overflow now — let the label render full text
        // (no `.byTruncatingTail`) so its intrinsic content width drives the
        // scroll view's content size.
        cmdPromptLabel.lineBreakMode = .byClipping
        cmdPromptLabel.translatesAutoresizingMaskIntoConstraints = false
        cmdPromptScrollView.addSubview(cmdPromptLabel)

        // Tap to position the caret inside the prompt. Lives on the scroll
        // view rather than the label so taps in trailing whitespace still
        // hit (the label only covers rendered text width).
        let promptTap = UITapGestureRecognizer(
            target: self, action: #selector(handlePromptTap(_:)))
        promptTap.cancelsTouchesInView = false
        cmdPromptScrollView.addGestureRecognizer(promptTap)

        // Preset chip strip — shares the prompt-label's slot. Visible only
        // while a needsPrompt command is active, the user hasn't typed
        // anything yet, AND the command has presets in PresetCatalog.
        cmdPresetStrip = PresetChipStripView()
        cmdPresetStrip.translatesAutoresizingMaskIntoConstraints = false
        cmdPresetStrip.isHidden = true
        commandBar.addSubview(cmdPresetStrip)

        // Blinking text caret — visual cue that the prompt label is the
        // current write surface. Positioned via a dynamic leading constraint
        // that `updateCaretPosition()` refreshes from `promptCaretIndex` (in
        // prompt mode) or `caretAnchorWidth` (in draft mode). Lives inside
        // the scroll view so it scrolls with the text, and never absorbs
        // taps — `handlePromptTap` owns those.
        cmdCaret = UIView()
        cmdCaret.translatesAutoresizingMaskIntoConstraints = false
        cmdCaret.backgroundColor = .white
        cmdCaret.layer.cornerRadius = 1
        cmdCaret.isHidden = true
        cmdCaret.isUserInteractionEnabled = false
        cmdPromptScrollView.addSubview(cmdCaret)

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
        cmdMicButton.tintColor = KeyboardPalette.barText.withAlphaComponent(0.85)
        cmdMicButton.backgroundColor = KeyboardPalette.chipBg.withAlphaComponent(0.7)
        cmdMicButton.layer.cornerRadius = 8
        cmdMicButton.setContentHuggingPriority(.required, for: .horizontal)
        cmdMicButton.addTarget(self, action: #selector(micTapped), for: .touchUpInside)
        cmdMicButton.translatesAutoresizingMaskIntoConstraints = false
        commandBar.addSubview(cmdMicButton)

        // Send / Generate button
        cmdSendButton = UIButton(type: .system)
        cmdSendButton.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        cmdSendButton.setTitleColor(KeyboardPalette.barText, for: .normal)
        cmdSendButton.backgroundColor    = KeyboardPalette.chipBg
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
            btn.setTitleColor(KeyboardPalette.barText, for: .normal)
            btn.backgroundColor           = KeyboardPalette.chipBg
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

        // ── Slash autocomplete strip — sits between the command bar and
        // the keys, mirrors Android's `CommandSuggestionStripView`. Its
        // height collapses to 0 when no matches are showing so the keys
        // get the full row back.
        slashStrip = CommandSuggestionStripView()
        slashStrip.onPick = { [weak self] cmd in
            self?.handleSlashSuggestionTap(cmd.rawValue)
        }
        keyboardContainer.addSubview(slashStrip)
        slashStripHeight = slashStrip.heightAnchor.constraint(equalToConstant: 0)

        NSLayoutConstraint.activate([
            // Slash strip sits at the very top of the keyboardContainer,
            // above the prompt area. Height toggles between 0 (hidden)
            // and `slashStripH` (visible) — when visible the keyboard
            // grows by that amount so nothing else has to move.
            slashStrip.topAnchor.constraint(equalTo: keyboardContainer.topAnchor),
            slashStrip.leadingAnchor.constraint(equalTo: keyboardContainer.leadingAnchor),
            slashStrip.trailingAnchor.constraint(equalTo: keyboardContainer.trailingAnchor),
            slashStripHeight,

            // Command bar now hangs off the strip's bottom so it shifts
            // down by `slashStripH` when the strip is visible.
            commandBar.topAnchor.constraint(equalTo: slashStrip.bottomAnchor),
            commandBar.leadingAnchor.constraint(equalTo: keyboardContainer.leadingAnchor),
            commandBar.trailingAnchor.constraint(equalTo: keyboardContainer.trailingAnchor),
            commandBar.heightAnchor.constraint(equalToConstant: commandBarH),

            // Banner shares the command bar's slot (only one visible at
            // a time) so it follows the same anchor.
            bannerContainer.topAnchor.constraint(equalTo: slashStrip.bottomAnchor),
            bannerContainer.leadingAnchor.constraint(equalTo: keyboardContainer.leadingAnchor),
            bannerContainer.trailingAnchor.constraint(equalTo: keyboardContainer.trailingAnchor),
            bannerContainer.heightAnchor.constraint(equalToConstant: commandBarH),
            bannerLabel.leadingAnchor.constraint(equalTo: bannerContainer.leadingAnchor),
            bannerLabel.trailingAnchor.constraint(equalTo: bannerContainer.trailingAnchor),
            bannerLabel.topAnchor.constraint(equalTo: bannerContainer.topAnchor),
            bannerLabel.bottomAnchor.constraint(equalTo: bannerContainer.bottomAnchor),

            // Command bar internals — most distances narrow on iPhone via
            // cmdSidePadding / cmdMicW / cmdMicH so the prompt label has
            // room to breathe on a 393pt-wide row. The leading-side ✕
            // cancel button was removed; backspace through the slash
            // buffer is the cancel affordance now.
            cmdPill.leadingAnchor.constraint(equalTo: commandBar.leadingAnchor, constant: cmdSidePadding),
            cmdPill.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdPill.heightAnchor.constraint(equalToConstant: 26),

            // Prompt scroll view fills the slot between pill and mic. The
            // label inside it can be wider than the frame — that's what
            // makes the prompt scroll horizontally.
            cmdPromptScrollView.leadingAnchor.constraint(equalTo: cmdPill.trailingAnchor, constant: 6),
            cmdPromptScrollView.trailingAnchor.constraint(equalTo: cmdMicButton.leadingAnchor, constant: -6),
            cmdPromptScrollView.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdPromptScrollView.heightAnchor.constraint(equalToConstant: 28),

            // Label fills the scroll view's CONTENT layout guide so its
            // intrinsic width drives content size. Vertically it matches
            // the FRAME layout guide so the text stays centered as the
            // user scrolls horizontally.
            cmdPromptLabel.topAnchor.constraint(equalTo: cmdPromptScrollView.contentLayoutGuide.topAnchor),
            cmdPromptLabel.bottomAnchor.constraint(equalTo: cmdPromptScrollView.contentLayoutGuide.bottomAnchor),
            cmdPromptLabel.leadingAnchor.constraint(equalTo: cmdPromptScrollView.contentLayoutGuide.leadingAnchor),
            cmdPromptLabel.trailingAnchor.constraint(equalTo: cmdPromptScrollView.contentLayoutGuide.trailingAnchor),
            cmdPromptLabel.heightAnchor.constraint(equalTo: cmdPromptScrollView.frameLayoutGuide.heightAnchor),

            cmdPresetStrip.leadingAnchor.constraint(equalTo: cmdPill.trailingAnchor, constant: 6),
            cmdPresetStrip.centerYAnchor.constraint(equalTo: commandBar.centerYAnchor),
            cmdPresetStrip.trailingAnchor.constraint(equalTo: cmdMicButton.leadingAnchor, constant: -6),
            cmdPresetStrip.heightAnchor.constraint(equalToConstant: 30),

            // Caret rides in the scroll view's content area (leading from
            // the label so it scrolls with the text) and centers vertically
            // on the frame guide so it stays put as content shifts.
            cmdCaret.centerYAnchor.constraint(equalTo: cmdPromptScrollView.frameLayoutGuide.centerYAnchor),
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
            cmdSuggestionsStack.leadingAnchor.constraint(equalTo: commandBar.leadingAnchor, constant: cmdSidePadding),
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
        // Remove previous key rows. Persistent overlays that are mounted as
        // siblings of the rows (command bar, banner, slash strip, preview,
        // integration / quick / listening panels) must survive a rebuild —
        // recomputeKeyboardHeight() calls into here after toggling chrome,
        // and we'd otherwise yank a visible preview out from under the user.
        keyboardContainer.subviews
            .filter { $0 !== commandBar
                   && $0 !== bannerContainer
                   && $0 !== slashStrip
                   && $0 !== previewOverlay
                   && $0 !== integrationPanelHost
                   && $0 !== quickPanelView
                   && $0 !== listeningOverlay }
            .forEach { $0.removeFromSuperview() }

        let rows = currentRows()
        // y-origin tracks the dynamic top chrome: strip + prompt bar if
        // either is currently showing, zero otherwise (collapsed mode).
        let topOffset = effectiveChromeH
        for (i, keys) in rows.enumerated() {
            let y = topOffset + rowGap + CGFloat(i) * (rowH + rowGap)
            keyboardContainer.addSubview(buildRow(keys: keys, rowIndex: i, totalRows: rows.count, y: y))
        }
        if let strip = slashStrip {
            keyboardContainer.bringSubviewToFront(strip)
        }
        // Newly-added key rows land on top of the stacking order; lift any
        // preserved overlay back above them so the user keeps seeing it.
        if let overlay = previewOverlay, overlay.isHidden == false {
            keyboardContainer.bringSubviewToFront(overlay)
        }
        if let panel = integrationPanelHost, panel.isHidden == false {
            keyboardContainer.bringSubviewToFront(panel)
        }
        if let quick = quickPanelView {
            keyboardContainer.bringSubviewToFront(quick)
        }
        if let listening = listeningOverlay {
            keyboardContainer.bringSubviewToFront(listening)
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
            // iPhone bottom row: 4 keys [?123, space, /, ↵] — the globe
            // is dropped in favour of a dedicated `/` (the slash-command
            // trigger); space slides left into the freed slot. Widths
            // still sum to 100, with space dominant and `/` sized like a
            // compact special key.
            let props: [CGFloat]
            switch keys.count {
            case 5:  props = [9, 13, 48, 13, 17]                // iPad
            case 4:  props = [15, 53, 12, 20]                   // iPhone [?123, space, /, ↵]
            default: props = [8, 12, 7, 42, 7, 24]              // legacy 6-key fallback
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
        // Prompt mode = there's a space in the buffer (e.g. "/cap a samurai
        // cat" → command "cap", prompt "a samurai cat"). Mutations there
        // honour `promptCaretIndex` so the user can edit mid-prompt after
        // tapping. Draft mode (no space yet) keeps the simpler append /
        // pop-from-end behaviour — caret has no meaning while the user is
        // still typing the command name.
        let inPrompt = splitSlashBuffer() != nil

        switch key {
        case "↵":
            // Treat return as "send" if we have a valid command queued.
            if activeCommand != nil { sendCommand() }
            return

        case "⌫":
            if inPrompt {
                handlePromptBackspace()
            } else {
                guard var buf = slashBuffer else { return }
                if !buf.isEmpty { buf.removeLast() }
                if buf.isEmpty {
                    slashBuffer = nil
                    hideCommandBar()
                } else {
                    slashBuffer = buf
                }
            }
            updateCommandDetection()
            return

        case "space":
            if inPrompt {
                insertIntoPrompt(" ")
            } else {
                // Transition into prompt mode — append the space and reset
                // the caret to the start of the (still-empty) prompt.
                slashBuffer? += " "
                promptCaretIndex = 0
            }
            updateCommandDetection()
            return

        default:
            var text = key
            let isQwertyLetter = (mode == .qwerty
                                  && key.count == 1
                                  && key.first?.isLetter == true)
            if isQwertyLetter {
                text = (isCapsLock || isShiftedOnce) ? key.uppercased() : key
            }
            if inPrompt {
                insertIntoPrompt(text)
            } else {
                slashBuffer? += text
            }
            if isQwertyLetter, isShiftedOnce, !isCapsLock {
                isShiftedOnce = false
                updateCommandDetection()
                rebuildKeyboard()
                return
            }
            updateCommandDetection()
        }
    }

    /// `slashBuffer` split into "/cap " (head, with trailing space) plus
    /// the prompt portion after it. Returns nil while we're still in draft
    /// mode — i.e. nothing past the slash, or no space typed yet.
    private func splitSlashBuffer() -> (head: String, prompt: String)? {
        guard let buf = slashBuffer, let spaceIdx = buf.firstIndex(of: " ") else {
            return nil
        }
        let afterSpace = buf.index(after: spaceIdx)
        return (head: String(buf[..<afterSpace]), prompt: String(buf[afterSpace...]))
    }

    /// Current prompt portion of `slashBuffer`, or nil in draft mode.
    private func currentPromptText() -> String? { splitSlashBuffer()?.prompt }

    /// Insert `text` into the prompt at `promptCaretIndex` and advance the
    /// caret. The command head ("/cap ") stays untouched.
    private func insertIntoPrompt(_ text: String) {
        guard let split = splitSlashBuffer() else { return }
        var prompt = split.prompt
        let caret = max(0, min(promptCaretIndex ?? prompt.count, prompt.count))
        let insertIdx = prompt.index(prompt.startIndex, offsetBy: caret)
        prompt.insert(contentsOf: text, at: insertIdx)
        slashBuffer = split.head + prompt
        promptCaretIndex = caret + text.count
    }

    /// Backspace at `promptCaretIndex`. If the caret is at the start of the
    /// prompt and the prompt is empty, this drops the trailing space and
    /// transitions back to draft mode (so `/cap ` → `/cap`). With caret at
    /// 0 and a non-empty prompt, this is a no-op — refusing to silently
    /// merge prompt content into the command name.
    private func handlePromptBackspace() {
        guard let split = splitSlashBuffer() else { return }
        var prompt = split.prompt
        let caret = max(0, min(promptCaretIndex ?? prompt.count, prompt.count))
        if caret > 0 {
            let removeIdx = prompt.index(prompt.startIndex, offsetBy: caret - 1)
            prompt.remove(at: removeIdx)
            slashBuffer = split.head + prompt
            promptCaretIndex = caret - 1
        } else if prompt.isEmpty {
            // Drop the trailing space — back to draft mode.
            slashBuffer = String(split.head.dropLast())
            promptCaretIndex = nil
        }
        // else: caret == 0 && prompt non-empty → no-op.
    }

    private func handleBackspace() {
        // `deleteBackward()` already handles both cases per UIKeyInput:
        // selected text → delete the selection; no selection → delete the
        // character before the caret. The previous `insertText("")` path
        // for selected text was unreliable across hosts (Slack, for one,
        // ignored it and left the selection intact), so the user's tap
        // looked like a no-op when text was highlighted.
        textDocumentProxy.deleteBackward()
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
        let aiCommands: [SlashCommand] = [.cap, .edit, .style, .sticker, .gif, .fix, .tone, .reply, .tl, .search, .ask, .org]
        // Walk the live registry so commands added later (poll, wyr,
        // web, …) flow through automatically without touching this list.
        let localCommands: [SlashCommand] = integrationRegistry.allCommands.compactMap {
            SlashCommand(rawValue: $0.name.lowercased())
        }
        // /history is a keyboard-local command (not an Integration) that
        // browses past /cap and /org outputs. Pin it after the integration
        // commands so it sits at the end of the grid.
        return aiCommands + localCommands + [.history]
    }

    // MARK: - Draft state persistence
    //
    // `slashBuffer` is the only piece of state we need to round-trip across
    // app switches — `updateCommandDetection()` re-derives `activeCommand`,
    // `commandPromptText`, and the right UI surface (draft bar, prompt bar,
    // suggestion chip strip) from it. We stash it in the App Group so the
    // next time the keyboard mounts on the same field it picks up where the
    // user left off.
    //
    // A short TTL keeps stale drafts from re-surfacing days later in an
    // unrelated field — the keyboard extension has no reliable "is this the
    // same field?" signal across launches, so the timestamp is our cheapest
    // safety net.

    private static let draftBufferKey       = "TurtleKB.draftSlashBuffer"
    private static let draftBufferAtKey     = "TurtleKB.draftSlashBufferAt"
    private static let draftBufferTTL: TimeInterval = 5 * 60  // 5 minutes

    private var draftStateStore: UserDefaults {
        UserDefaults(suiteName: SplitContract.storageSuiteName) ?? .standard
    }

    private func persistDraftState() {
        let store = draftStateStore
        if let buf = slashBuffer, !buf.isEmpty {
            store.set(buf, forKey: Self.draftBufferKey)
            store.set(Date().timeIntervalSince1970, forKey: Self.draftBufferAtKey)
        } else {
            store.removeObject(forKey: Self.draftBufferKey)
            store.removeObject(forKey: Self.draftBufferAtKey)
        }
    }

    private func restoreDraftStateIfFresh() {
        let store = draftStateStore
        let savedAt = store.double(forKey: Self.draftBufferAtKey)
        let age = Date().timeIntervalSince1970 - savedAt
        guard savedAt > 0, age >= 0, age <= Self.draftBufferTTL,
              let buf = store.string(forKey: Self.draftBufferKey),
              !buf.isEmpty
        else {
            // Either nothing was stashed, or it's gone stale. Clear so we
            // don't keep paying the read on every appearance.
            store.removeObject(forKey: Self.draftBufferKey)
            store.removeObject(forKey: Self.draftBufferAtKey)
            return
        }
        // Don't clobber an active typing session — if the user is already
        // mid-command on this mount, the live state wins over the snapshot.
        guard slashBuffer == nil, activeCommand == nil, !isGenerating else { return }
        slashBuffer = buf
        updateCommandDetection()
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
        // Mic only makes sense while composing a slash-command prompt;
        // hide it in the suggestions strip so it doesn't crowd the chips.
        [cmdPill, cmdPromptLabel, cmdSendButton, cmdMicButton]
            .forEach { $0.isHidden = true }
        cmdSuggestionsStack.isHidden = false

        if commandBar.isHidden {
            commandBar.alpha = 0
            commandBar.isHidden = false
            recomputeKeyboardHeight()
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
        // Hide normal command-bar controls (including the mic — it only
        // belongs in slash-command compose mode); show only the chips.
        [cmdPill, cmdPromptLabel, cmdSendButton, cmdMicButton]
            .forEach { $0.isHidden = true }
        cmdPresetStrip?.isHidden = true
        cmdSuggestionsStack.isHidden = false
        updateCaret()

        if commandBar.isHidden {
            commandBar.alpha = 0
            commandBar.isHidden = false
            recomputeKeyboardHeight()
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
        // First entry into prompt mode for this command — anchor the caret
        // at the end of whatever prompt text already exists (typically 0
        // for a fresh "/cap " transition). Once set, the caret is owned by
        // `insertIntoPrompt` / `handlePromptBackspace` / `handlePromptTap`
        // so subsequent showCommandBar calls (re-rendered on each keystroke)
        // don't clobber it.
        if activeCommand == nil {
            promptCaretIndex = currentPromptText()?.count ?? 0
        }
        activeCommand = cmd
        suggestionMode = .slashCommand
        // Leaving draft mode — let the caret measure full label text again.
        caretAnchorWidth = nil
        slashAutocompleteTopMatch = nil
        // User picked / fully typed a command — collapse the strip so
        // the keys get their full vertical space back.
        hideSlashStrip()

        // /edit and /style both need a reference image before the user can
        // describe what to do with it — fire the system image picker on
        // first entry. The picker callback re-invokes `showCommandBar(cmd)`
        // with `pickerSourceCommand` set, at which point `stagedEditImage`
        // is non-nil and we fall through to render the prompt bar.
        if cmd.needsReferenceImage, stagedEditImage == nil, !editPickerActive {
            editPickerActive = true
            pickerSourceCommand = cmd
            presentEditImagePicker()
            return
        }

        // Coming from word-suggestion mode? Restore normal controls first.
        // Mic only un-hides when the user has voice enabled in
        // Personalization — keep that gate honoured here.
        let voiceEnabled = personalizationStore.int(
            forKey: PersonalizationKeys.voiceEnabled, fallback: 1) != 0
        [cmdPill, cmdPromptLabel, cmdSendButton].forEach { $0.isHidden = false }
        cmdMicButton.isHidden = !voiceEnabled
        cmdSuggestionsStack.isHidden = true

        // Single-space padding on each side so the emoji doesn't butt
        // against the pill's rounded corner. Two was the previous value,
        // which left a visible gap on the left of the palette.
        cmdPill.text = " \(cmd.emoji) /\(cmd.rawValue) "
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
                if cmd.needsReferenceImage && stagedEditImage != nil {
                    // Keep these short — the caret is anchored to the END
                    // of the placeholder text, and a long placeholder
                    // pushes the caret past the scroll view's visible
                    // frame so the user can't see it blinking. Examples
                    // moved into the banner / docs instead.
                    switch cmd {
                    case .style:   cmdPromptLabel.text = "📎 pick a style…"
                    case .sticker: cmdPromptLabel.text = "📎 describe the sticker…"
                    case .gif:     cmdPromptLabel.text = "📎 describe the animation…"
                    default:       cmdPromptLabel.text = "📎 describe the edit…"
                    }
                } else {
                    cmdPromptLabel.text      = cmd.needsPrompt ? "type prompt above…" : "ready — tap \(cmd.buttonTitle)"
                }
                cmdPromptLabel.textColor = KeyboardPalette.barText.withAlphaComponent(0.40)
            } else {
                cmdPromptLabel.text      = commandPromptText
                cmdPromptLabel.textColor = KeyboardPalette.barText.withAlphaComponent(0.90)
            }
        }

        if commandBar.isHidden {
            commandBar.alpha  = 0
            commandBar.isHidden = false
            recomputeKeyboardHeight()
            UIView.animate(withDuration: 0.18) { self.commandBar.alpha = 1 }
        }
        commandBar.layoutIfNeeded()
        updateCaret()
    }

    // Shown while the buffer is `/` or `/xy` — i.e. the user is mid-typing a
    // command name that doesn't yet match a known command. Renders the
    // typed characters in white plus the rest of the best-matching command
    // in dim gray as a ghost, terminal-style. Pressing space accepts the
    // ghost (existing detection handles it); tapping the Send button —
    // re-labelled `→ /name` — also accepts.
    private func showDraftCommandBar(buffer: String) {
        activeCommand  = nil
        suggestionMode = .slashCommand

        // The slash compose bar is the "prompt area" the user wants the
        // mic in — un-hide it alongside the other controls (still gated
        // on the Personalization voice toggle).
        let voiceEnabled = personalizationStore.int(
            forKey: PersonalizationKeys.voiceEnabled, fallback: 1) != 0
        [cmdPill, cmdPromptLabel, cmdSendButton].forEach { $0.isHidden = false }
        cmdMicButton.isHidden = !voiceEnabled
        cmdPresetStrip?.isHidden = true
        cmdSpinner.stopAnimating()
        cmdSuggestionsStack.isHidden = true

        // Strip the leading "/" and lowercase for matching.
        let body = buffer.hasPrefix("/")
            ? String(buffer.dropFirst()).lowercased()
            : buffer.lowercased()
        let allMatches = slashAutocompleteAllMatches(query: body)
        let topMatch = allMatches.first
        slashAutocompleteTopMatch = topMatch?.rawValue

        // Keep the suggestion chip strip mounted for the entire draft
        // state — as long as the user has typed something past `/` and
        // *any* command still matches. It used to require 2+ candidates,
        // so narrowing `/c` → `/ca` (only `cap` left) made the strip blink
        // out. The strip stays visible until either no command matches or
        // the user commits a command (space → prompt mode).
        if !body.isEmpty, !allMatches.isEmpty {
            showSlashStrip(matches: allMatches)
        } else {
            hideSlashStrip()
        }

        cmdSuggestionsStack.isHidden = true
        cmdPill.text = " / "

        if body.isEmpty {
            // User just typed `/`. No ghost yet — the empty pill says
            // enough on its own. A faint placeholder keeps the bar from
            // looking inert. Caret sits at the start of the placeholder
            // so it's obviously where the next character will land.
            cmdPromptLabel.attributedText = nil
            cmdPromptLabel.text = "type a command…"
            cmdPromptLabel.textColor = UIColor.white.withAlphaComponent(0.40)
            cmdSendButton.setTitle("Send", for: .normal)
            caretAnchorWidth = 0
        } else if let match = topMatch {
            // Best match found. Render typed prefix opaque white, the
            // rest of the command name as a dimmer ghost. Tapping Send
            // still accepts the suggestion — we just don't echo the
            // command name onto the button, which made the prompt bar
            // look cluttered (the chip strip above already names every
            // candidate).
            let typed = body
            let full = match.rawValue
            let ghost = full.hasPrefix(typed)
                ? String(full.dropFirst(typed.count))
                : ""
            let attr = NSMutableAttributedString(
                string: typed,
                attributes: [
                    .foregroundColor: KeyboardPalette.barText.withAlphaComponent(0.95),
                    .font: cmdPromptLabel.font as Any,
                ])
            if !ghost.isEmpty {
                attr.append(NSAttributedString(
                    string: ghost,
                    attributes: [
                        .foregroundColor: KeyboardPalette.barText.withAlphaComponent(0.32),
                        .font: cmdPromptLabel.font as Any,
                    ]))
            }
            cmdPromptLabel.attributedText = attr
            cmdSendButton.setTitle("Send", for: .normal)
            // Caret sits right after the typed prefix, just before the
            // dim-gray ghost. Measure only the typed portion.
            let typedWidth = (typed as NSString)
                .size(withAttributes: [.font: cmdPromptLabel.font as Any]).width
            caretAnchorWidth = typedWidth
        } else {
            // No match at all. Show the typed body verbatim plus a
            // small explanatory tail so the user knows nothing's wrong
            // with the keyboard — they just typed something unknown.
            let attr = NSMutableAttributedString(
                string: body,
                attributes: [
                    .foregroundColor: KeyboardPalette.barText.withAlphaComponent(0.95),
                    .font: cmdPromptLabel.font as Any,
                ])
            attr.append(NSAttributedString(
                string: "  no match",
                attributes: [
                    .foregroundColor: KeyboardPalette.barText.withAlphaComponent(0.32),
                    .font: cmdPromptLabel.font as Any,
                ]))
            cmdPromptLabel.attributedText = attr
            cmdSendButton.setTitle("Send", for: .normal)
            let typedWidth = (body as NSString)
                .size(withAttributes: [.font: cmdPromptLabel.font as Any]).width
            caretAnchorWidth = typedWidth
        }

        if commandBar.isHidden {
            commandBar.alpha    = 0
            commandBar.isHidden = false
            recomputeKeyboardHeight()
            UIView.animate(withDuration: 0.15) { self.commandBar.alpha = 1 }
        }
        commandBar.layoutIfNeeded()
        updateCaret()
    }

    /// Single best-matching command for the partial query. Prefix wins
    /// over substring — `/c` returns `/cap`, never `/notion`. Nil if
    /// nothing matches.
    private func slashAutocompleteTopMatch(query: String) -> SlashCommand? {
        slashAutocompleteAllMatches(query: query).first
    }

    /// All matching commands ordered prefix-first then substring. Powers
    /// the chip strip when the user is mid-type and has 2+ candidates
    /// (e.g. `/sp` → `/split`, `/splits`). Capped at `cmdSuggestionBtns`
    /// length so we never overflow the suggestion slot.
    private func slashAutocompleteAllMatches(query: String) -> [SlashCommand] {
        guard !query.isEmpty else { return [] }
        let all = SlashCommand.allCases
        let prefixes = all.filter { $0.rawValue.lowercased().hasPrefix(query) }
        let substrings = all.filter {
            let s = $0.rawValue.lowercased()
            return !s.hasPrefix(query) && s.contains(query)
        }
        return Array((prefixes + substrings).prefix(cmdSuggestionBtns.count))
    }

    private func showSlashStrip(matches: [SlashCommand]) {
        slashStrip.show(matches)
        slashStripHeight.constant = slashStripH
        recomputeKeyboardHeight()
        keyboardContainer.bringSubviewToFront(slashStrip)
    }

    private func hideSlashStrip() {
        slashStrip.hide()
        slashStripHeight.constant = 0
        recomputeKeyboardHeight()
    }

    /// Height of the chrome above the keys right now.
    ///
    /// - The command-bar slot (`commandBarH`) is **always** reserved at
    ///   the top, even when the bar is empty. This is the slot that hosts
    ///   word-suggestion chips, shortcut chips, and the slash prompt UI —
    ///   if we let it collapse to 0 whenever it's empty, the keys would
    ///   visibly jump every time suggestions appeared or disappeared.
    /// - The slash autocomplete strip stacks above the command bar.
    /// - When the image preview is up it displaces the command-bar slot
    ///   entirely (the preview owns the chrome) and uses its own height.
    private var effectiveChromeH: CGFloat {
        if isPreviewVisible { return previewH }
        let strip: CGFloat = (slashStrip?.isHidden == false) ? slashStripH : 0
        return strip + commandBarH
    }

    private var isPreviewVisible: Bool {
        previewOverlay != nil && previewOverlay?.isHidden == false
    }

    /// Fixed slot height for the image-preview chrome that sits above the
    /// keys after /cap, /org, etc. Sized to fit a comfortable square image
    /// plus the variant button row (Image · Sticker · GIF · ✕) and a bit of
    /// padding. The keys stay visible and usable below this slot.
    private var previewH: CGFloat { isPad ? 300 : 260 }

    /// Recompute the keyboard's height from current chrome visibility and
    /// kick a key-rows rebuild so frame-positioned keys snap to the new
    /// y-origin. Safe to call from any visibility-changing site; bails
    /// out if the target height already matches.
    private func recomputeKeyboardHeight() {
        let target = effectiveChromeH + rowsH
        guard abs(heightConstraint.constant - target) > 0.5 else { return }
        heightConstraint.constant = target
        preferredContentSize = CGSize(width: 0, height: target)
        // Force the input view to resize in the same frame so we don't
        // see the container overflow upward into the host app for a
        // beat while iOS is processing `preferredContentSize`.
        view.setNeedsLayout()
        view.layoutIfNeeded()
        rebuildKeyboard()
    }

    /// Tap-to-accept handler invoked from the Send button while the bar
    /// is in draft mode. Replaces the slash buffer with `/<name> ` and
    /// routes through the normal detection so the command bar enters
    /// its prompt state (with `/edit` firing the picker, presets
    /// surfacing for `/tone`, etc.).
    private func handleSlashSuggestionTap(_ name: String) {
        slashBuffer = "/\(name) "
        commandPromptText = ""
        updateCommandDetection()
    }

    private func hideCommandBar() {
        guard !commandBar.isHidden, !isGenerating else { return }
        activeCommand = nil
        pendingSuggestions = []
        suggestionMode = .none
        caretAnchorWidth = nil
        promptCaretIndex = nil
        slashAutocompleteTopMatch = nil
        hideSlashStrip()
        // Drop any staged /edit or /style reference — the user backed out
        // before describing the edit/restyle. Re-entering either command
        // re-launches the picker.
        stagedEditImage = nil
        pickerSourceCommand = nil
        UIView.animate(withDuration: 0.15, animations: {
            self.commandBar.alpha = 0
        }, completion: { _ in
            self.commandBar.isHidden = true
            self.commandBar.alpha    = 1
            self.resetCommandBarMode()
            self.recomputeKeyboardHeight()
        })
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
            : KeyboardPalette.barText.withAlphaComponent(0.85)
        cmdMicButton.backgroundColor = listening
            ? KeyboardPalette.chipBg
            : KeyboardPalette.chipBg.withAlphaComponent(0.7)
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
        // Draft mode with a ghost completion: Send acts as
        // "accept the suggested command". Routes through the same path
        // a typed space would.
        if activeCommand == nil, let suggested = slashAutocompleteTopMatch {
            handleSlashSuggestionTap(suggested)
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
        // Consume the staged image for /edit and /style (cleared after the
        // request builds so a failed call doesn't trap the bytes in memory).
        let referenceImage: Data? = cmd.needsReferenceImage ? stagedEditImage : nil
        if cmd.needsReferenceImage { stagedEditImage = nil }
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
                    // For animated GIFs (`/gif` output) build a multi-frame
                    // `UIImage` so the preview animates AND retain the raw
                    // bytes so the GIF variant pill can paste them
                    // verbatim. `UIImage(data:)` alone would silently keep
                    // only the first frame.
                    let image: UIImage? = Self.isAnimatedGIF(data)
                        ? (Self.animatedImage(fromGIFData: data) ?? UIImage(data: data))
                        : UIImage(data: data)
                    if let image = image {
                        showImagePreview(image,
                                         sourceData: data,
                                         command: cmd.rawValue,
                                         prompt: prompt)
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
        [cmdPill, cmdPromptLabel, cmdSendButton].forEach { $0.isHidden = false }
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
        scrollCaretIntoView()
    }

    private func updateCaretPosition() {
        guard let label = cmdPromptLabel, let font = label.font else {
            cmdCaretLeading.constant = 0
            return
        }
        // Prompt mode: caret position is driven by `promptCaretIndex` so
        // the user can tap mid-text and have inserts / backspaces happen
        // at that spot. No `maxWidth` clamp here — the scroll view makes
        // the caret visible even when it lands past the visible frame.
        if activeCommand != nil, let idx = promptCaretIndex {
            let prompt = currentPromptText() ?? ""
            if prompt.isEmpty {
                // Empty prompt = placeholder is what's rendered (e.g. "type
                // prompt above…"). Anchor the caret to the END of the
                // placeholder so it visually sits at the tail of the
                // sentence, not awkwardly to the left of it. Clamp to the
                // scroll view's visible width so a long placeholder
                // doesn't push the caret off-screen — once the user
                // types, the prompt becomes non-empty and the caret
                // tracks `promptCaretIndex` against real text.
                let text = label.text ?? ""
                let w = (text as NSString).size(withAttributes: [.font: font]).width
                let visibleW = (cmdPromptScrollView?.bounds.width ?? .greatestFiniteMagnitude)
                // 6pt right margin so the 2pt caret never butts against
                // the mic button. `visibleW - 6` is the rightmost x where
                // the caret stays fully inside the scroll view's frame.
                cmdCaretLeading.constant = min(w, max(0, visibleW - 6))
                return
            }
            let clamped = max(0, min(idx, prompt.count))
            let prefix = String(prompt.prefix(clamped))
            let width = (prefix as NSString).size(withAttributes: [.font: font]).width
            cmdCaretLeading.constant = width
            return
        }
        // Draft mode supplies an explicit width so the caret lands between
        // the typed prefix and the ghost completion, not at the end of the
        // ghost.
        if let override = caretAnchorWidth {
            cmdCaretLeading.constant = override
            return
        }
        guard let text = label.text, !text.isEmpty else {
            cmdCaretLeading.constant = 0
            return
        }
        let size = (text as NSString).size(withAttributes: [.font: font])
        cmdCaretLeading.constant = size.width
    }

    /// Scroll the prompt area so the caret sits comfortably inside the
    /// visible frame. Padding keeps the caret away from the trailing edge
    /// so the user can still see a few characters past it while typing.
    private func scrollCaretIntoView() {
        guard let scroll = cmdPromptScrollView else { return }
        scroll.layoutIfNeeded()
        let caretX = cmdCaretLeading.constant
        let visibleW = scroll.bounds.width
        guard visibleW > 0 else { return }
        let pad: CGFloat = 24
        let leftEdge = scroll.contentOffset.x
        let rightEdge = leftEdge + visibleW
        var newOffsetX = leftEdge
        if caretX < leftEdge + pad {
            newOffsetX = max(0, caretX - pad)
        } else if caretX > rightEdge - pad {
            newOffsetX = caretX - visibleW + pad
        }
        // Don't scroll past the content — when content fits, contentOffset
        // stays at 0.
        let maxOffset = max(0, scroll.contentSize.width - visibleW)
        newOffsetX = max(0, min(newOffsetX, maxOffset))
        if abs(newOffsetX - scroll.contentOffset.x) > 0.5 {
            scroll.setContentOffset(CGPoint(x: newOffsetX, y: 0), animated: false)
        }
    }

    /// Tap inside the prompt scroll view → set `promptCaretIndex` to the
    /// character closest to the tap. Only acts while we're in prompt mode
    /// (i.e. a command is active); in draft mode the caret has no meaning.
    @objc private func handlePromptTap(_ gesture: UITapGestureRecognizer) {
        guard activeCommand != nil,
              let label = cmdPromptLabel,
              let font = label.font
        else { return }
        guard let prompt = currentPromptText(), !prompt.isEmpty else {
            promptCaretIndex = 0
            updateCaret()
            return
        }
        // Tap location relative to the label's leading edge — same
        // coordinate space as our cumulative-width measurements below.
        let xInLabel = gesture.location(in: label).x
        promptCaretIndex = nearestCharIndex(in: prompt, font: font, x: xInLabel)
        updateCaret()
    }

    /// Character index in `text` whose left edge is closest to `x` (where
    /// `x` is measured from the text's leading edge). Iterates char-by-char
    /// — O(n), fine for the short prompts the command bar holds.
    private func nearestCharIndex(in text: String, font: UIFont, x: CGFloat) -> Int {
        let ns = text as NSString
        var best = 0
        var bestDist = CGFloat.greatestFiniteMagnitude
        // 0...length so the caret can land past the final character.
        for i in 0...ns.length {
            let prefix = ns.substring(to: i)
            let width = (prefix as NSString).size(withAttributes: [.font: font]).width
            let dist = abs(width - x)
            if dist < bestDist {
                bestDist = dist
                best = i
            }
        }
        return best
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
        recomputeKeyboardHeight()
        UIView.animate(withDuration: 0.15) { self.bannerContainer.alpha = 1 }
        hideBannerTimer?.invalidate()
        hideBannerTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: false) { [weak self] _ in
            UIView.animate(withDuration: 0.2, animations: {
                self?.bannerContainer.alpha = 0
            }, completion: { _ in
                self?.bannerContainer.isHidden = true
                self?.bannerContainer.alpha    = 1
                self?.recomputeKeyboardHeight()
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
            // Anchor to the very top of the keyboardContainer (not
            // `commandBarH` below it). The host needs to cover the
            // always-reserved command-bar slot too, otherwise the top
            // strip of key rows leaks through above the Quick Panel / web
            // panel — the iPad number row was visibly poking out before
            // this change.
            NSLayoutConstraint.activate([
                host.topAnchor.constraint(equalTo: keyboardContainer.topAnchor),
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
        // Gemini is the only backend; the key is loaded from the
        // build-time `.env` via `Secrets.geminiApiKey`. If no key is
        // configured, requests will surface a clear error from
        // `GeminiLlmService.complete`.
        self.llm = GeminiLlmService(apiKey: Secrets.geminiApiKey)
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
        // `.compatible` asks the system for a JPEG-coded representation;
        // it's smaller on disk and far cheaper to thumbnail than HEIC's
        // 10-bit color path. Pairs with ImageIO's CGImageSource thumbnail
        // decode in the picker callback to keep peak memory bounded.
        config.preferredAssetRepresentationMode = .compatible
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self
        picker.modalPresentationStyle = .fullScreen
        present(picker, animated: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        // Capture the originating command BEFORE dismissing — if the
        // extension is under memory pressure, the dismiss completion may
        // not fire on the same instance, and we want the data we need
        // pinned in this closure.
        let cmd = self.pickerSourceCommand ?? .edit
        self.pickerSourceCommand = nil
        let provider = results.first?.itemProvider

        picker.dismiss(animated: true) { [weak self] in
            guard let self = self else { return }
            self.editPickerActive = false

            guard let provider = provider else {
                // User cancelled — back the command bar out entirely.
                // Re-entering would just re-launch the picker on the next
                // showCommandBar(cmd), because the picker's launch guard
                // is `needsReferenceImage && stagedEditImage == nil &&
                // !editPickerActive`, all of which are true again here.
                // The user can re-invoke /edit / /style / /sticker by
                // typing `/` again or via the Quick Panel.
                DispatchQueue.main.async { [weak self] in
                    guard let self = self else { return }
                    self.slashBuffer = nil
                    self.hideCommandBar()
                }
                return
            }

            // Critical: use `loadDataRepresentation` + ImageIO thumbnailing
            // instead of `loadObject(ofClass: UIImage.self)`. Loading a full
            // UIImage decodes the entire asset into RAM (~50 MB for a 12 MP
            // photo) which blows past the keyboard extension's ~50 MB
            // ceiling and gets the extension killed — the user sees iOS
            // either crash the extension or hot-swap back to the system
            // keyboard. The data path streams bytes and decodes straight
            // to a 1024-pt thumbnail, keeping peak memory at a few MB.
            let typeID = "public.image"
            provider.loadDataRepresentation(forTypeIdentifier: typeID) { data, _ in
                let png: Data? = data.flatMap { ImageDownsizer.downsizedPNG(fromData: $0) }
                DispatchQueue.main.async { [weak self] in
                    guard let self = self else { return }
                    if let png = png {
                        self.stagedEditImage = png
                    }
                    self.showCommandBar(cmd)
                }
            }
        }
    }
}

// MARK: - QuickPanelDelegate

extension KeyboardViewController: QuickPanelDelegate {

    func quickPanelDidSelect(_ command: SlashCommand) {
        dismissQuickPanel()
        // /history is keyboard-local with its own panel — no IntegrationKit
        // wiring, no AI round-trip. Mount it directly so it shares the
        // overlay slot the Quick Panel just vacated.
        if command == .history {
            showHistoryPanel()
            return
        }
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

    private func showHistoryPanel() {
        let panel = HistoryPanelView(columns: isPad ? 6 : 4)
        panel.onDismiss = { [weak self] in self?.unmountIntegrationPanel() }
        panel.onCopied  = { [weak self] in
            self?.showBanner("📋 Image copied — long-press field to paste")
        }
        mountIntegrationPanel(panel)
    }

    func quickPanelDidDismiss() {
        dismissQuickPanel()
    }
}

// MARK: - VoiceInputController.Sink

extension KeyboardViewController: VoiceInputController.Sink {

    func onListeningStarted() {
        setMicListeningUI(true)
        showListeningOverlay()
    }

    func onListeningStopped() {
        setMicListeningUI(false)
        hideListeningOverlay()
    }

    func onPartial(_ text: String) {
        listeningOverlay?.updateTranscript(text)
        appendDictation(text)
    }

    func onFinal(_ text: String) {
        // Two destinations:
        //   • If the user invoked voice from inside a slash-command flow
        //     (`/ask hello`) the existing path stuffs the transcript into
        //     the command bar.
        //   • Otherwise, insert the transcript straight into the host
        //     text field — that's the Wispr-Flow-style chat dictation.
        if voicePromptPrefix != nil {
            appendDictation(text)
        } else {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                textDocumentProxy.insertText(trimmed)
            }
        }
        voicePromptPrefix = nil
    }

    func onError(_ userVisibleMessage: String) {
        voicePromptPrefix = nil
        showBanner("⚠️ \(userVisibleMessage)")
    }

    func onInfo(_ userVisibleMessage: String) {
        // Don't reset voicePromptPrefix — the user is mid-flow (switching
        // to the Turtle app to dictate) and will return here for insertion.
        showBanner("🐢 \(userVisibleMessage)")
    }

    private func appendDictation(_ spoken: String) {
        guard let prefix = voicePromptPrefix else { return }
        let trimmed = spoken.trimmingCharacters(in: .whitespacesAndNewlines)
        slashBuffer = prefix + trimmed
        // Dictation overwrites the prompt out-of-band; snap the caret to
        // the end of the new text so the user can keep typing without the
        // cursor sitting at index 0 of a freshly-dictated sentence.
        promptCaretIndex = currentPromptText()?.count
        updateCommandDetection()
    }

    // MARK: - Listening overlay (Wispr-style "Listening / iPad Microphone")

    private static var listeningOverlayKey: UInt8 = 0
    private var listeningOverlay: ListeningOverlayView? {
        get { objc_getAssociatedObject(self, &Self.listeningOverlayKey) as? ListeningOverlayView }
        set { objc_setAssociatedObject(self, &Self.listeningOverlayKey, newValue, .OBJC_ASSOCIATION_RETAIN_NONATOMIC) }
    }

    private func showListeningOverlay() {
        guard listeningOverlay == nil else { return }
        let overlay = ListeningOverlayView()
        overlay.translatesAutoresizingMaskIntoConstraints = false
        overlay.onConfirm = { [weak self] in self?.voiceController.requestStop() }
        overlay.onCancel  = { [weak self] in self?.voiceController.cancel() }
        keyboardContainer.addSubview(overlay)
        NSLayoutConstraint.activate([
            overlay.topAnchor.constraint(equalTo: keyboardContainer.topAnchor),
            overlay.leadingAnchor.constraint(equalTo: keyboardContainer.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: keyboardContainer.trailingAnchor),
            overlay.bottomAnchor.constraint(equalTo: keyboardContainer.bottomAnchor),
        ])
        keyboardContainer.bringSubviewToFront(overlay)
        listeningOverlay = overlay
    }

    private func hideListeningOverlay() {
        listeningOverlay?.removeFromSuperview()
        listeningOverlay = nil
    }
}

// MARK: - ListeningOverlayView

/// Mirrors Wispr Flow's in-keyboard listening surface (image 18): black
/// background, a pulsing dot pattern + "Listening / iPad Microphone"
/// caption, and X / ✓ buttons in the corners. Live transcripts replace
/// the caption when the recognizer reports partials.
private final class ListeningOverlayView: UIView {

    var onConfirm: (() -> Void)?
    var onCancel:  (() -> Void)?

    private let transcriptLabel = UILabel()
    private let captionStack    = UIStackView()
    private let dotsView        = PulsingDotsView()
    private let listeningLabel  = UILabel()
    private let micLabel        = UILabel()
    private let cancelButton    = UIButton(type: .system)
    private let confirmButton   = UIButton(type: .system)

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        layout()
    }
    required init?(coder: NSCoder) { fatalError() }

    func updateTranscript(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            transcriptLabel.text = ""
            transcriptLabel.isHidden = true
            captionStack.isHidden = false
        } else {
            transcriptLabel.text = trimmed
            transcriptLabel.isHidden = false
            captionStack.isHidden = true
        }
    }

    private func layout() {
        // ✕ — cancel
        cancelButton.setImage(UIImage(systemName: "xmark"), for: .normal)
        cancelButton.tintColor = .white
        cancelButton.backgroundColor = UIColor.white.withAlphaComponent(0.14)
        cancelButton.layer.cornerRadius = 22
        cancelButton.addTarget(self, action: #selector(cancelPressed), for: .touchUpInside)

        // ✓ — confirm
        confirmButton.setImage(UIImage(systemName: "checkmark"), for: .normal)
        confirmButton.tintColor = .black
        confirmButton.backgroundColor = .white
        confirmButton.layer.cornerRadius = 26
        confirmButton.addTarget(self, action: #selector(confirmPressed), for: .touchUpInside)

        // Caption shown when there's no transcript yet.
        listeningLabel.text = "Listening"
        listeningLabel.textColor = .white
        listeningLabel.font = .systemFont(ofSize: 18, weight: .medium)
        listeningLabel.textAlignment = .center

        micLabel.text = "iPad Microphone"
        micLabel.textColor = UIColor.white.withAlphaComponent(0.55)
        micLabel.font = .systemFont(ofSize: 14)
        micLabel.textAlignment = .center

        captionStack.axis = .vertical
        captionStack.alignment = .center
        captionStack.spacing = 4
        captionStack.addArrangedSubview(listeningLabel)
        captionStack.addArrangedSubview(micLabel)

        // Live transcript, hidden until first partial.
        transcriptLabel.textColor = .white
        transcriptLabel.font = .systemFont(ofSize: 20, weight: .regular)
        transcriptLabel.numberOfLines = 3
        transcriptLabel.textAlignment = .center
        transcriptLabel.isHidden = true

        [cancelButton, confirmButton, dotsView, captionStack, transcriptLabel]
            .forEach {
                $0.translatesAutoresizingMaskIntoConstraints = false
                addSubview($0)
            }

        NSLayoutConstraint.activate([
            // ✕ top-left (matches Wispr image 18 top-left position).
            cancelButton.topAnchor.constraint(equalTo: topAnchor, constant: 14),
            cancelButton.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 18),
            cancelButton.widthAnchor.constraint(equalToConstant: 44),
            cancelButton.heightAnchor.constraint(equalToConstant: 44),

            // ✓ top-right.
            confirmButton.topAnchor.constraint(equalTo: topAnchor, constant: 10),
            confirmButton.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -18),
            confirmButton.widthAnchor.constraint(equalToConstant: 52),
            confirmButton.heightAnchor.constraint(equalToConstant: 52),

            // Dots + caption stacked vertically in the centre.
            dotsView.centerXAnchor.constraint(equalTo: centerXAnchor),
            dotsView.centerYAnchor.constraint(equalTo: centerYAnchor, constant: -12),
            dotsView.widthAnchor.constraint(equalToConstant: 140),
            dotsView.heightAnchor.constraint(equalToConstant: 12),

            captionStack.topAnchor.constraint(equalTo: dotsView.bottomAnchor, constant: 18),
            captionStack.centerXAnchor.constraint(equalTo: centerXAnchor),

            transcriptLabel.topAnchor.constraint(equalTo: dotsView.bottomAnchor, constant: 18),
            transcriptLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 32),
            transcriptLabel.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -32),
        ])

        dotsView.startAnimating()
    }

    @objc private func confirmPressed() { onConfirm?() }
    @objc private func cancelPressed()  { onCancel?() }
}

/// Eight white dots pulsing in sequence — purely decorative.
private final class PulsingDotsView: UIView {
    private let dotCount = 8
    private var dotLayers: [CALayer] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        for _ in 0..<dotCount {
            let layer = CALayer()
            layer.backgroundColor = UIColor.white.cgColor
            self.layer.addSublayer(layer)
            dotLayers.append(layer)
        }
    }
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let size: CGFloat = 6
        let totalW = bounds.width
        let gap = (totalW - CGFloat(dotCount) * size) / CGFloat(dotCount - 1)
        var x: CGFloat = 0
        let y = (bounds.height - size) / 2
        for layer in dotLayers {
            layer.frame = CGRect(x: x, y: y, width: size, height: size)
            layer.cornerRadius = size / 2
            x += size + gap
        }
    }

    func startAnimating() {
        for (i, layer) in dotLayers.enumerated() {
            let anim = CABasicAnimation(keyPath: "opacity")
            anim.fromValue = 0.3
            anim.toValue = 1.0
            anim.duration = 0.6
            anim.autoreverses = true
            anim.repeatCount = .infinity
            anim.beginTime = CACurrentMediaTime() + Double(i) * 0.08
            layer.add(anim, forKey: "pulse")
        }
    }
}
