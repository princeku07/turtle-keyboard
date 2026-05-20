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

    /// Frequency-based word suggester. Loads `commands/dict/en_unigrams.txt`
    /// (shared with the Android keyboard) on a background queue and
    /// blends it with the per-user vocabulary that grows as the user
    /// types. Replaces the previous UITextChecker-only path.
    private let suggestionEngine = SuggestionEngine()

    /// Serial background queue for suggestion work. UITextChecker still
    /// powers the typo-correction fallback so we keep this queue around
    /// for that work; the new engine's reads are cheap and run inline.
    private let suggestionQueue = DispatchQueue(label: "turtle.suggest", qos: .userInitiated)

    /// In-flight suggestion calculation, kept here so we can cancel it
    /// when a new keystroke arrives mid-pass — that's the debounce that
    /// makes fast typing feel responsive instead of stuttery.
    private var suggestionWorkItem: DispatchWorkItem?

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
    private var rowH:        CGFloat { isPad ? 44 : 45 }
    private var rowGap:      CGFloat { isPad ? 6  : 7 }
    private var commandBarH: CGFloat { isPad ? 46 : 50 }
    private var keyGap:      CGFloat { isPad ? 8  : 6 }
    private var bottomPad:   CGFloat { isPad ? 4  : 6 }

    // Command-bar internals shrink on iPhone — the prompt label needs to
    // claim what's left after cancel + pill + mic + send buttons, and
    // iPhone widths (375-393pt portrait) don't have headroom for the
    // generous iPad spacing.
    private var cmdSidePadding:   CGFloat { isPad ? 12 : 6 }
    // Square so `cmdMicH / 2` corner radius renders as a perfect circle
    // instead of a vertical oval. iOS native mic / FAB buttons are
    // circular; matching that here keeps the bar's pill row visually
    // consistent.
    private var cmdMicH:          CGFloat { isPad ? 34 : 32 }
    private var cmdMicW:          CGFloat { cmdMicH }
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
    private var cmdPill:             PaddedLabel!
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
    private let slashStripH: CGFloat = 42
    private var heightConstraint:    NSLayoutConstraint!
    private var hideBannerTimer:     Timer?
    private var backspaceTimer:      Timer?
    /// Aurora-style loading overlay that takes over the command-bar slot
    /// while an AI request is in flight. Lazily created on first use,
    /// kept around afterwards so subsequent generations don't pay the
    /// layer-allocation cost.
    private var generatingWave:      GeneratingWaveView?
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

    // Cached personalization flags. Reading these from the App Group
    // UserDefaults on every keystroke was a measurable hot-path cost
    // (`handleSpaceDoubleTap`, `showCommandBar`, `showDraftCommandBar`
    // all hit the store). The user can only flip these from the host
    // app, so we refresh the cache when the keyboard mounts on a field.
    private var cachedVoiceEnabled: Bool = true
    private var cachedQuickPanelEnabled: Bool = true

    private func refreshPersonalizationCache() {
        cachedVoiceEnabled = personalizationStore.int(
            forKey: PersonalizationKeys.voiceEnabled, fallback: 1) != 0
        cachedQuickPanelEnabled = personalizationStore.int(
            forKey: PersonalizationKeys.quickPanelEnabled, fallback: 1) != 0
    }

    // MARK: - Key press popup
    //
    // Apple's keyboard pops a larger version of the glyph above the
    // pressed key so the user can confirm what they hit even though
    // their finger is covering the key itself. We mirror that with a
    // single recycled `UIView` (reusing it avoids per-keystroke
    // allocations on the hot typing path).

    private var keyPopupView: UIView?
    private var keyPopupLabel: UILabel?
    /// Safety timer that auto-hides the popup if `keyTapped` doesn't
    /// fire on touch-up (which can happen when the `UIVisualEffectView`
    /// sibling backing or row-level gap routing interferes with the
    /// UIControl touch-tracking path). Cancelled when `keyTapped`
    /// fires normally — so most key presses never wait for it.
    private var keyPopupAutoHide: DispatchWorkItem?

    /// Silence-detect auto-stop for voice dictation. Reset every time
    /// a partial transcript arrives; if the timer fires (no partials
    /// for `voiceSilenceTimeout` seconds) we request the host to stop
    /// recording — the same effect the old ✓ button had.
    private var voiceSilenceTimer: Timer?
    /// 1.8s matches the silence-stop threshold Siri uses on iOS.
    /// Short enough that the listening UI dismisses promptly when
    /// the user finishes speaking; long enough to tolerate brief
    /// pauses mid-utterance.
    private static let voiceSilenceTimeout: TimeInterval = 1.8

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
        refreshPersonalizationCache()
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

        // Warm up the suggestion engine in the background. When the
        // 82k-word unigram dictionary finishes loading (~150-300 ms on
        // device), repaint the chip strip so the user sees suggestions
        // without having to type a fresh keystroke first.
        suggestionEngine.onReady = { [weak self] in
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                if self.suggestionMode == .suggestedShortcuts
                    || self.suggestionMode == .wordSuggestion {
                    self.refreshSuggestions()
                }
            }
        }
        suggestionEngine.loadAsync()
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
        cmdMicButton?.tintColor = text
        cmdMicButton?.backgroundColor = chipBg
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
        // Personalization toggles may have changed in the host app between
        // mounts. Re-cache so per-keystroke checks read in-memory.
        refreshPersonalizationCache()
        // If the user just returned from the Turtle host app after dictating,
        // a transcript is sitting in the App Group waiting to be inserted.
        // The Darwin notification handles the live case; this catches the
        // suspended-appex case where we missed the notification.
        voiceController.consumePendingTranscript()
        // Re-hydrate any in-progress slash draft the user left behind when
        // they switched apps (e.g. `/ca`, `/cap a samurai cat`). Must run
        // before refreshSuggestions — when the draft restores, the
        // command bar takes over the suggestion slot.
        restoreDraftStateIfFresh()
        // Auto-capitalize the first character on empty fields / after
        // sentence-end punctuation — matches the iOS default behaviour.
        autoEngageShiftIfNeeded()
        // Populate the chip strip on mount. Subsequent text changes
        // refresh via textDidChange + the in-line `processKey` calls.
        refreshSuggestions()
    }

    /// Auto-shift-once when the cursor is at a position where the next
    /// character should be capitalized: empty field, start of a new
    /// line, or right after sentence-end punctuation (`.`, `!`, `?`)
    /// followed by a space. The existing shift-once → letter handler
    /// in `processKey` already auto-unshifts after the first letter is
    /// typed, so the user gets "Hello world" without ever tapping ⇧.
    private func autoEngageShiftIfNeeded() {
        guard mode == .qwerty, !isCapsLock else { return }
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        let trimmed = before.trimmingCharacters(in: .whitespaces)
        let shouldShift: Bool
        if trimmed.isEmpty || before.isEmpty {
            // Empty field OR cursor sitting on a fresh new line.
            shouldShift = true
        } else if let last = trimmed.last,
                  (last == "." || last == "!" || last == "?"),
                  before.hasSuffix(" ") || before.hasSuffix("\n") {
            // Sentence end — next character starts a new sentence.
            shouldShift = true
        } else if before.hasSuffix("\n") {
            // New line typed — capitalize what follows.
            shouldShift = true
        } else {
            shouldShift = false
        }
        if shouldShift != isShiftedOnce {
            isShiftedOnce = shouldShift
            updateKeyVisualsForShiftState()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Stash the in-progress slash draft so the next keyboard mount
        // (returning from another app) picks it up where the user left off.
        persistDraftState()
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        // Single dispatch path for the chip strip. `refreshSuggestions`
        // re-derives the right chips from the live proxy state.
        guard activeCommand == nil, !isGenerating else { return }
        refreshSuggestions()
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
        // Pin to a dark navy background regardless of theme. The variant
        // pills (white background, brand-green text) and the close ✕
        // (white text on translucent-white) are designed against a dark
        // surface — using `bgColor` made the overlay light-gray in the
        // Light theme, which collapsed the contrast and made the close
        // button vanish entirely.
        overlay.backgroundColor = UIColor(red: 0.020, green: 0.031, blue: 0.102, alpha: 1.0)
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
            // Capsule — row height is 38pt, so 19 reads as fully rounded.
            b.layer.cornerRadius = 19
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
        closeBtn.layer.cornerRadius = 19  // matches the variant pills
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
        cmdPill = PaddedLabel()
        // Pixel-symmetric padding — space characters don't render at
        // identical widths in mono fonts, so adding " " before vs after
        // the command name used to look unbalanced. `textInsets` is
        // measured in points and is identical on left and right.
        cmdPill.textInsets = UIEdgeInsets(top: 0, left: 8, bottom: 0, right: 8)
        cmdPill.font               = .monospacedSystemFont(ofSize: 12, weight: .bold)
        cmdPill.backgroundColor    = KeyboardPalette.chipBg
        cmdPill.textColor          = KeyboardPalette.barText
        // Capsule — matches the pill height (locked to `cmdMicH` below)
        // so the pill + mic + send all share the same capsule shape.
        cmdPill.layer.cornerRadius = cmdMicH / 2
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
        cmdCaret.backgroundColor = KeyboardPalette.barText
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
        cmdMicButton = UIButton(type: .system)
        cmdMicButton.isHidden = !cachedVoiceEnabled
        cmdMicButton.setImage(
            UIImage(systemName: "mic.fill",
                    withConfiguration: UIImage.SymbolConfiguration(pointSize: 15, weight: .medium)),
            for: .normal)
        cmdMicButton.tintColor = KeyboardPalette.barText
        cmdMicButton.backgroundColor = KeyboardPalette.chipBg  // match Send weight
        cmdMicButton.layer.cornerRadius = cmdMicH / 2          // perfect circle
        cmdMicButton.setContentHuggingPriority(.required, for: .horizontal)
        cmdMicButton.addTarget(self, action: #selector(micTapped), for: .touchUpInside)
        cmdMicButton.translatesAutoresizingMaskIntoConstraints = false
        commandBar.addSubview(cmdMicButton)

        // Send / Generate button
        cmdSendButton = UIButton(type: .system)
        cmdSendButton.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        cmdSendButton.setTitleColor(KeyboardPalette.barText, for: .normal)
        cmdSendButton.backgroundColor    = KeyboardPalette.chipBg
        cmdSendButton.layer.cornerRadius = cmdMicH / 2  // capsule, matches mic
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
            btn.layer.cornerRadius        = 18  // capsule on the 36pt stack height
            btn.contentEdgeInsets         = UIEdgeInsets(top: 0, left: 10, bottom: 0, right: 10)
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
        bannerLabel.textColor     = KeyboardPalette.barText
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
            cmdPill.heightAnchor.constraint(equalToConstant: cmdMicH),

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
            // Lock the Send button to the same height as the mic so the
            // two buttons read as one capsule pair across the bar's
            // trailing edge. Without this the Send button heights
            // depended on its text + contentEdgeInsets and ended up
            // visibly different from the mic.
            cmdSendButton.heightAnchor.constraint(equalToConstant: cmdMicH),

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
                   && $0 !== listeningOverlay
                   && $0 !== keyPopupView }
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
        // The command bar / banner share the chrome slot above the keys
        // and host the suggestion chips. After a rebuild they end up
        // buried beneath the freshly-added key row containers (since
        // those land at the top of the stacking order), which is why
        // chip text could appear cut off / invisible. Lift them back
        // above the rows here.
        if let bar = commandBar, !bar.isHidden {
            keyboardContainer.bringSubviewToFront(bar)
        }
        if let banner = bannerContainer, !banner.isHidden {
            keyboardContainer.bringSubviewToFront(banner)
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
        // Key popup (the magnified glyph above the pressed key) must
        // stay on top of everything so it isn't clipped by the freshly
        // rebuilt key rows when a mode-switch rebuild fires.
        if let popup = keyPopupView, !popup.isHidden {
            keyboardContainer.bringSubviewToFront(popup)
        }
    }

    private func buildRow(keys: [String], rowIndex: Int, totalRows: Int, y: CGFloat) -> UIView {
        let w         = kbWidth
        // KeyRowView extends hit-testing into the keyGap dead zones so a
        // tap that lands between two visible keys still registers on the
        // nearest one — same way Apple's iOS keyboard treats the gap.
        let container = KeyRowView(frame: CGRect(x: 0, y: y, width: w, height: rowH))
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

        // When the active theme uses a transparent backdrop (dark mode
        // matches native iOS — host content shows through), we render
        // each key as a UIVisualEffectView blur material with a faint
        // tint instead of a flat opaque color. Themes with an opaque
        // backdrop (light, turtle) keep their solid keys.
        let translucentTheme = themeUsesTranslucentKeys()

        for (i, key) in keys.enumerated() {
            let btn = makeKey(label: key)
            let w = i < widths.count ? widths[i] : 44
            let frame = CGRect(x: xOffset, y: 0, width: w, height: rowH)
            btn.frame = frame
            if translucentTheme {
                // Mount blur + tint as SIBLINGS of the button behind it
                // (not as subviews of the button). Putting them inside
                // the UIButton hierarchy interferes with how UIButton
                // lays out its managed imageView / titleLabel — which
                // was the reason special-key icons (⇧, ⌫, ↵, 🌐) were
                // disappearing in the dark theme. With them as
                // siblings, the button's icon/text always renders on
                // top of the blur regardless of UIButton internals.
                addTranslucentBacking(behind: btn, in: container,
                                       frame: frame,
                                       tintColor: btn.backgroundColor ?? KeyboardPalette.keyNormal)
                btn.backgroundColor = .clear
                btn.layer.shadowOpacity = 0
            } else {
                // Pin the shadow to the button's rounded rect so CALayer
                // doesn't have to offscreen-rasterize the layer to compute
                // shadow alpha on every frame. With ~30 buttons and a
                // press-scale CATransform animation firing on every tap,
                // the implicit shadow path was the dominant per-keystroke
                // GPU/CPU cost — typing fast made every key re-rasterize.
                btn.layer.shadowPath = CGPath(
                    roundedRect: CGRect(x: 0, y: 0, width: w, height: rowH),
                    cornerWidth: 7, cornerHeight: 7, transform: nil)
                // Tell the rasterization cache that the shadow doesn't
                // depend on the layer's bitmap contents either — a flat
                // backgroundColor + cornerRadius is the entire visual.
                btn.layer.shouldRasterize = false
            }
            container.addSubview(btn)
            xOffset += w + keyGap
        }
        return container
    }

    /// True if the active theme's keyboard backdrop is translucent —
    /// in which case we want native-style translucent keys (blur +
    /// faint tint) so host content shows through the gaps the way
    /// Apple's keyboard does. Themes with a fully opaque backdrop
    /// don't gain anything from blur and keep their solid colors.
    private func themeUsesTranslucentKeys() -> Bool {
        var alpha: CGFloat = 1
        KeyboardPalette.bg.getRed(nil, green: nil, blue: nil, alpha: &alpha)
        // Apple's native dark keyboard backdrop sits around 0.55 alpha.
        // Anything not nearly-opaque counts as a translucent theme.
        return alpha < 0.95
    }

    /// Pick the blur material for the active theme. Glyph color tells
    /// us whether the theme is light or dark — a light glyph means a
    /// dark theme (and we want `.systemMaterialDark` so the key reads
    /// dark with a bright glyph), and vice versa.
    private func blurMaterialForCurrentTheme() -> UIBlurEffect.Style {
        var white: CGFloat = 0
        KeyboardPalette.keyText.getWhite(&white, alpha: nil)
        return white > 0.5 ? .systemMaterialDark : .systemMaterialLight
    }

    /// Mount a system blur + theme tint as siblings of `btn` inside
    /// `container`, positioned at `frame` and stacked BEHIND the
    /// button (so the button's icon / text stays visible on top).
    /// Picks the blur material based on the active theme — dark
    /// themes (white glyphs) use `.systemMaterialDark`, light themes
    /// (dark glyphs) use `.systemMaterialLight` so the key always
    /// reads with proper contrast against its glyph.
    private func addTranslucentBacking(behind btn: UIButton,
                                       in container: UIView,
                                       frame: CGRect,
                                       tintColor: UIColor) {
        let blur = UIVisualEffectView(effect: UIBlurEffect(style: blurMaterialForCurrentTheme()))
        blur.frame = frame
        blur.isUserInteractionEnabled = false
        blur.layer.cornerRadius = 7
        blur.layer.masksToBounds = true
        container.addSubview(blur)

        // Tint overlay inside the blur's contentView so it inherits
        // the blur's rounded clip. The theme's key color already
        // carries the right alpha (letter keys at ~18% white, special
        // keys at ~15% white) so it paints verbatim.
        let tint = UIView(frame: blur.bounds)
        tint.backgroundColor = tintColor
        tint.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        tint.isUserInteractionEnabled = false
        blur.contentView.addSubview(tint)
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
            // When shift latches, Apple's iOS keyboard flips the icon
            // to a contrasting tone — black icon on the white-ish key
            // in dark mode. `keyTextShiftOn` is the theme's pick for
            // that contrast.
            btn.tintColor = KeyboardPalette.keyTextShiftOn; btn.backgroundColor = keyShiftOn
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
            // Apple's iOS keyboard uses regular-weight letters at ~23pt.
            // The previous `.light` weight made glyphs feel spindly and
            // hard to read at a glance — bumping to `.regular` gives them
            // the same visual presence as the native keyboard.
            btn.titleLabel?.font = .systemFont(ofSize: 23, weight: .regular)
            btn.setTitleColor(normalText, for: .normal)
            btn.backgroundColor = keyNormal
        }

        btn.layer.cornerRadius  = 7; btn.layer.masksToBounds = false
        // Apple's iOS keys have a barely-there 1pt bottom shadow with
        // ~20% opacity — just enough to give the key a sense of being a
        // raised tile. Our previous 0.45 opacity at 1.5pt was way too
        // heavy: in dark mode it added a visible black fringe under
        // every key that Apple's keyboard doesn't have.
        btn.layer.shadowColor   = UIColor.black.cgColor
        btn.layer.shadowOffset  = CGSize(width: 0, height: 1.0)
        btn.layer.shadowOpacity = 0.20; btn.layer.shadowRadius = 0
        btn.accessibilityLabel  = label
        // Character fires on touchDown (snappy, matches Apple's keyboard).
        // touchUpInside / touchUpOutside / touchCancel only reset the
        // press-scale visual so the key doesn't stay pinched if the
        // finger lifts off-button or the gesture is cancelled (e.g. a
        // long-press taking over).
        btn.addTarget(self, action: #selector(keyTouchDown(_:)), for: .touchDown)
        btn.addTarget(self, action: #selector(keyTapped(_:)),    for: .touchUpInside)
        btn.addTarget(self, action: #selector(keyTapped(_:)),    for: .touchUpOutside)
        btn.addTarget(self, action: #selector(keyTapped(_:)),    for: .touchCancel)

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
    //
    // Character insertion fires on **touch down** rather than touchUpInside.
    // iOS's native keyboard does this — it's why a fast tap registers the
    // moment your finger touches the key, even if the finger drifts
    // slightly off the button before lifting. The previous touchUpInside
    // path required the finger to lift *inside* the button bounds, so
    // sloppy fast typing lost characters silently to touchUpOutside.
    //
    // touchUpInside / touchUpOutside / touchCancel now only reset the
    // press-scale visual; the character has already been committed.

    @objc private func keyTouchDown(_ sender: UIButton) {
        // Commit the keystroke FIRST — that's what the user is waiting
        // for. The haptic and press-scale animation run after, so the
        // character is in the host field by the time the visual press
        // feedback even starts. Subjective responsiveness wins.
        guard let key = sender.accessibilityLabel else { return }
        // Snapshot the visible glyph BEFORE processKey runs — shift-once
        // letters get reset to lowercase by `updateKeyVisualsForShiftState`
        // inside processKey, but the popup should still show the glyph
        // the user actually typed.
        let displayChar = sender.title(for: .normal) ?? key
        processKey(key)
        showKeyPopup(for: sender, label: key, displayChar: displayChar)
        // Safety net: even if `keyTapped` never fires (rare, but
        // happens when the visual-effect backing or gap-route hit
        // testing interferes with UIControl's touch tracking), the
        // popup must not be left visible. 0.45s matches Apple — feels
        // instant for taps, allows hold-to-see for a beat.
        scheduleKeyPopupAutoHide()
        haptic.impactOccurred()
        UIView.animate(withDuration: 0.05) { sender.transform = CGAffineTransform(scaleX: 0.93, y: 0.93) }
    }

    @objc private func keyTapped(_ sender: UIButton) {
        // Reset the press-scale visual. The character was inserted in
        // `keyTouchDown` already.
        hideKeyPopup()
        UIView.animate(withDuration: 0.08) { sender.transform = .identity }
    }

    private func scheduleKeyPopupAutoHide() {
        keyPopupAutoHide?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.hideKeyPopup()
        }
        keyPopupAutoHide = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45, execute: work)
    }

    /// Lazily build the recycled popup view + label. Cheap to call on
    /// every keystroke; only the first call actually constructs.
    private func ensureKeyPopupView() {
        guard keyPopupView == nil else { return }
        let popup = UIView()
        popup.layer.cornerRadius = 8
        popup.layer.shadowColor = UIColor.black.cgColor
        popup.layer.shadowOpacity = 0.30
        popup.layer.shadowOffset = CGSize(width: 0, height: 2)
        popup.layer.shadowRadius = 4
        popup.isUserInteractionEnabled = false
        popup.isHidden = true

        let label = UILabel()
        label.font = .systemFont(ofSize: 34, weight: .regular)
        label.textAlignment = .center
        label.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        popup.addSubview(label)

        keyboardContainer.addSubview(popup)
        keyPopupView = popup
        keyPopupLabel = label
    }

    /// Show the magnified-glyph popup above `btn`. Hidden for modifier
    /// / mode-switch keys — Apple's keyboard doesn't pop those either.
    private func showKeyPopup(for btn: UIButton, label: String, displayChar: String) {
        // Glyph keys only — modifiers, mode switches, space, return
        // all skip the popup the same way the native keyboard does.
        let nonPop: Set<String> = ["🌐", "⇧", "⌫", "?123", "ABC", "=\\<", "↵", "space"]
        if nonPop.contains(label) {
            hideKeyPopup()
            return
        }
        ensureKeyPopupView()
        guard let popup = keyPopupView,
              let labelView = keyPopupLabel,
              let row = btn.superview else { return }

        let keyFrame = row.convert(btn.frame, to: keyboardContainer)
        // Popup is wider + taller than the key so the magnified glyph
        // clears the finger. Clamped to the keyboard bounds so popups
        // near the screen edge don't get pushed off-screen.
        let popupW = max(keyFrame.width * 1.5, 44)
        let popupH = keyFrame.height * 1.7
        var popupX = keyFrame.midX - popupW / 2
        let kbW = keyboardContainer.bounds.width
        popupX = max(2, min(popupX, kbW - 2 - popupW))
        let popupY = keyFrame.minY - popupH - 2

        popup.frame = CGRect(x: popupX, y: popupY, width: popupW, height: popupH)
        popup.backgroundColor = KeyboardPalette.keyNormal
        // Pin shadow path so the layer doesn't offscreen-rasterize on
        // every show — same fix we apply to the keys themselves.
        popup.layer.shadowPath = CGPath(
            roundedRect: popup.bounds,
            cornerWidth: 8, cornerHeight: 8, transform: nil)

        labelView.frame = popup.bounds
        labelView.text = displayChar
        labelView.textColor = KeyboardPalette.keyText

        keyboardContainer.bringSubviewToFront(popup)
        popup.isHidden = false
    }

    private func hideKeyPopup() {
        keyPopupAutoHide?.cancel()
        keyPopupAutoHide = nil
        keyPopupView?.isHidden = true
    }

    /// Carries the key's logical action. Routed from `keyTouchDown` so
    /// the response feels instant; the touchUp handlers below only reset
    /// the visual press state. Note: a few keys (`↵`, `⌫`, `space`,
    /// mode-switches, slash) have side effects beyond inserting a glyph
    /// — those flows are unchanged, just moved earlier in the touch
    /// lifecycle.
    private func processKey(_ key: String) {
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
        case "↵":
            // Return commits the word right before the cursor — feed
            // it to the suggestion engine so it accrues personal
            // frequency (same as Android's `learn(...)` call site).
            learnWordBeforeCursor()
            proxy.insertText("\n")
            hideCommandBar()
            // New line → next char starts a new "sentence". Re-engage
            // auto-shift so the user doesn't have to tap ⇧ again.
            autoEngageShiftIfNeeded()
        case "⌫":
            handleBackspace()
            updateCommandDetection()
            // After deleting, the cursor may now be at a sentence start
            // (e.g. user cleared the field entirely) — re-engage shift.
            autoEngageShiftIfNeeded()
        case "space":
            // Space also commits the word — learn before inserting.
            learnWordBeforeCursor()
            proxy.insertText(" ")
            handleSpaceDoubleTap()
            updateCommandDetection()
            // Space after sentence-end punctuation kicks auto-shift on.
            autoEngageShiftIfNeeded()
        default:
            var text = key
            if mode == .qwerty, key.count == 1, key.first?.isLetter == true {
                text = (isCapsLock || isShiftedOnce) ? key.uppercased() : key
                if isShiftedOnce && !isCapsLock {
                    isShiftedOnce = false
                    proxy.insertText(text)
                    updateCommandDetection()
                    // Just shift state changed — in-place refresh, not
                    // a full keyboard rebuild.
                    updateKeyVisualsForShiftState(); return
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
                // Shift state only — refresh in place, no full rebuild.
                updateKeyVisualsForShiftState()
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

    /// Extract the word ending at the cursor and feed it to the
    /// suggestion engine. Called from the space / return key paths —
    /// those are the two glyphs that "commit" the previous word the
    /// way Android's IME does it. URLs, slash commands, numbers, and
    /// emoji are filtered inside `SuggestionEngine.learn(_:)`.
    private func learnWordBeforeCursor() {
        // Cheap fast-path bail-out if we're in slash mode — the buffer
        // owns the text, the host field never saw a partial word here.
        guard slashBuffer == nil else { return }
        let context = (textDocumentProxy.documentContextBeforeInput ?? "") as NSString
        let range = context.range(of: "\\S+$", options: .regularExpression)
        guard range.location != NSNotFound else { return }
        let word = context.substring(with: range)
        suggestionEngine.learn(word)
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
        // Shift only toggles glyph case + the shift button's image —
        // no row count or key layout change. Update in-place instead
        // of tearing down every UIButton.
        updateKeyVisualsForShiftState()
    }

    private func handleSpaceDoubleTap() {
        // Respect the Personalization toggle: when off, double-space falls
        // through to whatever the host expects (just two spaces).
        guard cachedQuickPanelEnabled else { return }
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

        // No active slash command. Always refresh the chip strip —
        // `refreshSuggestions` is now synchronous and cheap (no
        // background dispatch), and we want a deterministic update on
        // every keystroke whether or not `textDidChange` fires.
        let wasInSlashMode = suggestionMode == .slashCommand || activeCommand != nil
        if wasInSlashMode {
            hideCommandBar()
        }
        refreshSuggestions()
    }

    // MARK: - Word suggestions (in-keyboard autocomplete strip)

    // Single entry point for the chip strip. Runs synchronously on the
    // main thread — the new suggestion engine's prefix lookup is fast
    // enough (~1-2 ms over 82k words) that the prior off-main debounce
    // added more lag than it saved. Synchronous also means we can't
    // race a fresh keystroke with a stale background result.
    //
    // Fallback chain (each step only runs if the previous is empty):
    //   1. Engine prefix match on the current word (≥2 chars)
    //   2. User vocabulary top words (so the strip is NEVER blank while
    //      the user is mid-word — what made the previous setup look
    //      "broken")
    //   3. For an empty field: catalog shortcuts (email/url/search hints)
    //
    // UITextChecker typo-correction stays off-main and only fires when
    // the engine had nothing — its result replaces the fallback chips
    // if it lands before the user types past the current word.
    private func refreshSuggestions() {
        guard activeCommand == nil, !isGenerating else { return }

        let context = (textDocumentProxy.documentContextBeforeInput ?? "") as NSString
        let range = context.range(of: "\\S+$", options: .regularExpression)
        let currentWord: String = range.location != NSNotFound
            ? context.substring(with: range)
            : ""

        // Slash composition uses its own strip — don't fight it.
        if currentWord.hasPrefix("/") {
            hideSuggestionStripIfShown()
            return
        }

        var picks: [String] = []

        // Step 1 — engine prefix lookup.
        if currentWord.count >= 2 {
            picks = suggestionEngine.suggest(prefix: currentWord, max: 3)
        }

        // Step 2 — user vocabulary fallback (keeps strip alive even
        // when the dictionary is still loading on cold launch, or when
        // the user types a novel prefix that has no matches).
        if picks.isEmpty {
            picks = suggestionEngine.topUserWords(max: 3)
        }

        if !picks.isEmpty {
            showWordSuggestions(picks)
            // Engine didn't match — kick the off-main typo-correction
            // path which can replace the fallback chips with proper
            // misspelling guesses if they land in time.
            if currentWord.count >= 2,
               suggestionEngine.suggest(prefix: currentWord, max: 1).isEmpty {
                scheduleTypoCorrection(for: currentWord)
            }
            return
        }

        // Step 3 — no user vocabulary yet AND no prefix match. For an
        // empty field surface the catalog shortcuts so brand-new
        // installs get SOMETHING; for an unmatched mid-word, hide.
        if currentWord.isEmpty {
            showCatalogShortcutsIfApplicable()
        } else {
            hideSuggestionStripIfShown()
        }
    }

    /// Hide the chip strip but only when one of the suggestion modes
    /// owns it. Avoids fighting a slash-command bar that might also be
    /// up.
    private func hideSuggestionStripIfShown() {
        if suggestionMode == .wordSuggestion || suggestionMode == .suggestedShortcuts {
            hideCommandBar()
        }
        suggestionWorkItem?.cancel()
        suggestionWorkItem = nil
    }

    /// Show the field-kind catalog shortcuts (email / URL / search /
    /// general) if the field allows them. Used as the empty-field
    /// fallback when the user has no learned vocabulary yet.
    private func showCatalogShortcutsIfApplicable() {
        guard let proxy = textDocumentProxy as? (UITextDocumentProxy & UITextInputTraits) else {
            return
        }
        let kind = FieldKind.from(InputContext(proxy: proxy))
        if kind == .sensitive {
            hideSuggestionStripIfShown()
            return
        }
        let shortcuts = Array(SuggestedShortcutCatalog.shortcuts(for: kind).prefix(3))
        if !shortcuts.isEmpty {
            showSuggestedShortcuts(shortcuts)
        } else {
            hideSuggestionStripIfShown()
        }
    }

    /// Off-main UITextChecker typo-correction. Cancellation-safe — if
    /// the user types past the captured `word` before this work
    /// completes, the main-thread block bails out so stale guesses
    /// don't overwrite the current strip.
    private func scheduleTypoCorrection(for word: String) {
        suggestionWorkItem?.cancel()
        let token = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            let nsRange = NSRange(location: 0, length: word.utf16.count)
            let misspelled = self.textChecker.rangeOfMisspelledWord(
                in: word, range: nsRange,
                startingAt: 0, wrap: false, language: "en")
            guard misspelled.location != NSNotFound else { return }
            let guesses = self.textChecker.guesses(
                forWordRange: misspelled, in: word, language: "en") ?? []
            let picks = Array(guesses.prefix(3))
            guard !picks.isEmpty else { return }
            DispatchQueue.main.async { [weak self] in
                guard let self = self,
                      self.activeCommand == nil, !self.isGenerating else { return }
                // Stale-result guard — bail if the user has typed past
                // the word we were computing for.
                let now = (self.textDocumentProxy.documentContextBeforeInput ?? "") as NSString
                let nowRange = now.range(of: "\\S+$", options: .regularExpression)
                let nowWord = nowRange.location != NSNotFound
                    ? now.substring(with: nowRange) : ""
                guard nowWord == word else { return }
                self.showWordSuggestions(picks)
            }
        }
        suggestionWorkItem = token
        suggestionQueue.async(execute: token)
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
        commandBar.bringSubviewToFront(cmdSuggestionsStack)

        // Race guard — see `showWordSuggestions` for why this matters.
        let wasHidden = commandBar.isHidden
        commandBar.layer.removeAllAnimations()
        commandBar.isHidden = false
        commandBar.alpha    = 1
        keyboardContainer.bringSubviewToFront(commandBar)
        if wasHidden {
            recomputeKeyboardHeight()
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
        // Lift the chip stack above any other command-bar subview that
        // might still be drawing on top of it (the pill / prompt /
        // send / mic stay in the bar, just `isHidden = true`). Without
        // this, the chips could be visually buried behind a sibling
        // and their text wouldn't appear.
        commandBar.bringSubviewToFront(cmdSuggestionsStack)
        updateCaret()

        // Cancel any in-flight fade-out so a stale `hideCommandBar`
        // animation can't sneak the bar back to hidden right after we
        // mount fresh chips. This was the "suggestions sometimes
        // appear, sometimes don't" race — a hide animation in progress
        // would set isHidden=true in its completion block AFTER we
        // already populated and re-shown the chips.
        let wasHidden = commandBar.isHidden
        commandBar.layer.removeAllAnimations()
        commandBar.isHidden = false
        commandBar.alpha    = 1
        // Lift the entire bar above the freshly-rebuilt key rows in
        // case anything yanked it back in the stacking order.
        keyboardContainer.bringSubviewToFront(commandBar)
        if wasHidden {
            recomputeKeyboardHeight()
        }
    }

    private func replaceCurrentWord(with replacement: String) {
        let context = (textDocumentProxy.documentContextBeforeInput ?? "") as NSString
        let range = context.range(of: "\\S+$", options: .regularExpression)
        // Empty field / no word at cursor → tap = plain insert, with
        // trailing space so the user can keep typing the next word.
        // This is the empty-field "frequent words" chip path; without
        // it, tapping a chip on an empty field used to silently no-op.
        guard range.location != NSNotFound else {
            textDocumentProxy.insertText(replacement + " ")
            return
        }

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
        [cmdPill, cmdPromptLabel, cmdSendButton].forEach { $0.isHidden = false }
        cmdMicButton.isHidden = !cachedVoiceEnabled
        cmdSuggestionsStack.isHidden = true

        // Padding now lives in `PaddedLabel.textInsets` (set at setup
        // time), so the text itself carries no whitespace. Left + right
        // inset are identical so the pill always looks symmetric.
        cmdPill.text = "\(cmd.emoji) /\(cmd.rawValue)"
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
        // No `commandBar.layoutIfNeeded()` here — forcing a synchronous
        // Auto Layout pass on every keystroke in slash mode is wasted
        // work. The caret's width-measure (`updateCaretPosition`) doesn't
        // need the bar's frame to be flushed, and `scrollCaretIntoView`
        // already flushes the inner scroll view itself when needed.
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

        // Draft mode: the typed `/cap` lives INSIDE the pill, as a
        // single attributed run. Keeping it pinned to the pill on the
        // left avoids the floating-text-in-empty-bar look the previous
        // approach produced. The prompt label is hidden in draft mode
        // (and so is the caret, via `updateCaret`'s label-visibility
        // gate) — the pill IS the visual indicator.
        cmdPill.isHidden = false
        cmdPromptLabel.isHidden = true
        cmdSendButton.isHidden = false
        cmdMicButton.isHidden = !cachedVoiceEnabled
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
        // *any* command still matches. The strip stays visible until
        // either no command matches or the user commits with space.
        if !body.isEmpty, !allMatches.isEmpty {
            showSlashStrip(matches: allMatches)
        } else {
            hideSlashStrip()
        }

        // Build the attributed pill content: "/" + typed (opaque) +
        // ghost completion (dim). The pill grows from a single-character
        // `/` up to "/cap" as the user types, anchored on the left of
        // the bar — same slot the committed pill occupies, so the
        // transition between draft and committed reads as the pill
        // changing content rather than a layout jump.
        let font = cmdPill.font ?? .monospacedSystemFont(ofSize: 12, weight: .bold)
        let typedAttrs: [NSAttributedString.Key: Any] = [
            .foregroundColor: KeyboardPalette.barText,
            .font: font,
        ]
        let ghostAttrs: [NSAttributedString.Key: Any] = [
            .foregroundColor: KeyboardPalette.barText.withAlphaComponent(0.40),
            .font: font,
        ]

        if body.isEmpty {
            // Just `/` — keep the pill compact rather than padding it
            // out with a hint string (which inflates the pill width on
            // every initial slash tap).
            let attr = NSMutableAttributedString(string: "/", attributes: typedAttrs)
            cmdPill.attributedText = attr
            cmdSendButton.setTitle("Send", for: .normal)
        } else if let match = topMatch {
            let typed = body
            let full = match.rawValue
            let ghost = full.hasPrefix(typed)
                ? String(full.dropFirst(typed.count))
                : ""
            let attr = NSMutableAttributedString(string: "/", attributes: typedAttrs)
            attr.append(NSAttributedString(string: typed, attributes: typedAttrs))
            if !ghost.isEmpty {
                attr.append(NSAttributedString(string: ghost, attributes: ghostAttrs))
            }
            cmdPill.attributedText = attr
            cmdSendButton.setTitle("Send", for: .normal)
        } else {
            let attr = NSMutableAttributedString(string: "/", attributes: typedAttrs)
            attr.append(NSAttributedString(string: body, attributes: typedAttrs))
            attr.append(NSAttributedString(string: " · no match", attributes: ghostAttrs))
            cmdPill.attributedText = attr
            cmdSendButton.setTitle("Send", for: .normal)
        }
        // Caret has no meaningful position in draft mode (the pill is the
        // indicator). `updateCaret` keys off `cmdPromptLabel.isHidden`,
        // which is now true, so it'll hide the caret on its own.
        caretAnchorWidth = nil

        if commandBar.isHidden {
            commandBar.alpha    = 0
            commandBar.isHidden = false
            recomputeKeyboardHeight()
            UIView.animate(withDuration: 0.15) { self.commandBar.alpha = 1 }
        }
        // No synchronous layoutIfNeeded — see showCommandBar comment.
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
        }, completion: { finished in
            // Race-safe completion: if a `showWordSuggestions` (or any
            // other re-show path) ran while we were fading out, it
            // already reset `suggestionMode` to something other than
            // `.none`. In that case bail out and restore alpha — the
            // re-show meant the user is meant to see the bar now.
            // Without this guard, fast typing alternating between
            // "no picks" and "some picks" sneaks `isHidden = true`
            // back on top of the freshly-mounted chips.
            guard finished, self.suggestionMode == .none else {
                self.commandBar.alpha = 1
                return
            }
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
        // Replace the spinner-on-Send-button affordance with an
        // aurora-style wave overlay that fills the entire command-bar
        // slot. Mirrors Android's GeneratingLoaderView and reads as a
        // first-class "AI is working" state.
        showGeneratingWave(message: cmd.loadingMessage)

        // Buffer holds the slash text; nothing was typed into the host field,
        // so there's nothing to delete from the proxy.
        slashBuffer = nil
        executeCommand(cmd, prompt: commandPromptText)
    }

    // MARK: - Generating wave overlay

    /// Lazily mount the wave overlay on top of the command bar, hide the
    /// normal bar internals, and start the aurora animation. The wave is
    /// pinned to all four edges of `commandBar`, so it scales correctly
    /// with the bar's dynamic height.
    private func showGeneratingWave(message: String) {
        let wave: GeneratingWaveView
        if let existing = generatingWave {
            wave = existing
        } else {
            wave = GeneratingWaveView()
            wave.translatesAutoresizingMaskIntoConstraints = false
            commandBar.addSubview(wave)
            NSLayoutConstraint.activate([
                wave.topAnchor.constraint(equalTo: commandBar.topAnchor),
                wave.bottomAnchor.constraint(equalTo: commandBar.bottomAnchor),
                wave.leadingAnchor.constraint(equalTo: commandBar.leadingAnchor),
                wave.trailingAnchor.constraint(equalTo: commandBar.trailingAnchor),
            ])
            generatingWave = wave
        }
        // Hide every interactive bar control so the wave is the only
        // thing the user sees in this slot. `hideGeneratingWave` doesn't
        // reverse this — `hideCommandBar` / `showCommandBar` /
        // `showImagePreview` already drive the next visible state from
        // scratch on the success / failure paths.
        [cmdPill, cmdPromptScrollView, cmdSendButton, cmdMicButton,
         cmdSpinner, cmdSuggestionsStack, cmdPresetStrip,
         cmdPromptLabel, cmdCaret]
            .compactMap { $0 }
            .forEach { $0.isHidden = true }

        wave.setMessage(message)
        wave.isHidden = false
        commandBar.bringSubviewToFront(wave)
        // Layout pass so `wave.bounds` is non-zero before the animations
        // read its width.
        commandBar.layoutIfNeeded()
        wave.startAnimating()
    }

    /// Stop and hide the wave overlay. Caller is responsible for
    /// driving the next bar state (preview / banner / cleared bar).
    private func hideGeneratingWave() {
        generatingWave?.stopAnimating()
        generatingWave?.isHidden = true
    }

    // MARK: - Command execution

    private func executeCommand(_ cmd: SlashCommand, prompt: String) {
        // Local commands handled by integrations — no AI hop, no overlay.
        if cmd.isLocal {
            isGenerating = false
            hideGeneratingWave()
            hideCommandBar()
            // `/history` is keyboard-local but has no integration handler
            // (its UI is the in-keyboard image grid). Same panel the
            // Quick Panel tap path lands on — and same async-defer
            // fix-up so we never tear down the command bar / mount the
            // panel during the Send button's touch dispatch.
            if cmd == .history {
                DispatchQueue.main.async { [weak self] in
                    self?.showHistoryPanel()
                }
                return
            }
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
                hideGeneratingWave()

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
                hideGeneratingWave()
                hideCommandBar()
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
            refreshSuggestions()
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
            refreshSuggestions()
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
                // Empty prompt = the rendered label text is a placeholder
                // (e.g. "type prompt above…"), not real content. Position
                // the caret at index 0 — that's where the first typed
                // character will land, and the placeholder visually
                // slides aside as the user types. Anchoring to the END
                // of the placeholder was wrong: it implied the help
                // text was actual content the user had typed.
                cmdCaretLeading.constant = 0
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

    /// Tap callback from `cmdPresetStrip`. Pre-fills the prompt with the
    /// chip's value and parks the caret at the end so the user can either
    /// tap **Generate** to fire as-is, or keep typing to refine it.
    ///
    /// Auto-firing on chip tap (the previous behaviour) was surprising
    /// for /cap especially — users would brush a preset by accident and
    /// burn an image generation. Two-step interaction matches what every
    /// other suggestion chip in the iOS keyboard already does.
    private func handlePresetTap(_ value: String) {
        guard let cmd = activeCommand else { return }
        commandPromptText = value
        cmdPresetStrip.isHidden = true
        cmdPromptLabel.isHidden = false
        cmdPromptLabel.text = value
        cmdPromptLabel.textColor = KeyboardPalette.barText.withAlphaComponent(0.90)
        // Keep `slashBuffer` in sync so `handleSlashBufferKey` continues
        // to insert / backspace at the same prompt the label shows.
        // Without this, the label says "anime" but `slashBuffer` is
        // still "/cap " — tapping Generate sends an empty prompt.
        if let split = splitSlashBuffer() {
            slashBuffer = split.head + value
        }
        // Caret to end of the new prompt so the next keystroke appends.
        promptCaretIndex = value.count
        commandBar.layoutIfNeeded()
        updateCaret()
        // No auto-execute — the user has to tap Generate.
        _ = cmd
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

    /// In-place update for shift state changes. Walks the existing key
    /// buttons and refreshes the shift button's image / background plus
    /// every QWERTY letter's title — far cheaper than tearing down all
    /// ~30 buttons (with their shadows, gestures, target/actions) and
    /// rebuilding them, which is what `rebuildKeyboard()` does. Use
    /// this anywhere only the shift flag changed; full mode switches
    /// still go through `rebuildKeyboard()`.
    private func updateKeyVisualsForShiftState() {
        let shifted = isCapsLock || isShiftedOnce
        let c15 = UIImage.SymbolConfiguration(pointSize: 15, weight: .medium)
        let specialText = KeyboardPalette.keyTextSpecial

        for row in keyboardContainer.subviews {
            if row === commandBar || row === bannerContainer
                || row === slashStrip || row === previewOverlay
                || row === integrationPanelHost || row === quickPanelView
                || row === listeningOverlay || row === keyPopupView {
                continue
            }
            for case let btn as UIButton in row.subviews {
                guard let label = btn.accessibilityLabel else { continue }
                if label == "⇧" {
                    if shifted {
                        btn.setImage(UIImage(systemName: isCapsLock ? "capslock.fill" : "shift.fill",
                                             withConfiguration: c15), for: .normal)
                        btn.tintColor = KeyboardPalette.keyTextShiftOn
                        btn.backgroundColor = keyShiftOn
                    } else {
                        btn.setImage(UIImage(systemName: "shift", withConfiguration: c15), for: .normal)
                        btn.tintColor = specialText
                        btn.backgroundColor = keySpecial
                    }
                } else if mode == .qwerty,
                          label.count == 1,
                          label.first?.isLetter == true {
                    btn.setTitle(displayTitle(for: label), for: .normal)
                }
            }
        }
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
        // Defer the panel teardown + next-state mount to the next runloop
        // tick. We're currently inside the QuickPanel tile's UIAction
        // handler — synchronously removing the QuickPanel from its
        // superview here means UIKit is still mid-dispatch on that
        // tile's touch event when the control deallocates. For most
        // commands the survivor state lands in `commandBar` (a different
        // view), so the timing happens to survive; for `/history` we
        // *immediately* mount a fresh view into the same host slot the
        // QuickPanel was just torn out of, which crashes. The async hop
        // lets UIKit finish the touch dispatch first, then we tear down
        // and remount cleanly.
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.dismissQuickPanel()
            // /history is keyboard-local with its own panel — no
            // IntegrationKit wiring, no AI round-trip. Mount it directly
            // so it shares the overlay slot the Quick Panel just vacated.
            if command == .history {
                self.showHistoryPanel()
                return
            }
            if command.needsPrompt {
                // Open the command bar pre-loaded with the command and
                // an empty prompt; the user types (or dictates) the
                // body and taps Send. Reusing the slash buffer keeps
                // the existing detection / dispatch path intact.
                self.slashBuffer = "/\(command.rawValue) "
                self.updateCommandDetection()
            } else {
                // No prompt needed — fire immediately. `activeCommand`
                // is set synchronously by updateCommandDetection so
                // sendCommand picks it up.
                self.slashBuffer = "/\(command.rawValue)"
                self.updateCommandDetection()
                self.sendCommand()
            }
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
        // Arm the silence watchdog. If no partials arrive within the
        // window the host gets stopped automatically — the user never
        // needs to press a ✓ button.
        armVoiceSilenceTimer()
    }

    func onListeningStopped() {
        cancelVoiceSilenceTimer()
        setMicListeningUI(false)
        hideListeningOverlay()
    }

    func onPartial(_ text: String) {
        listeningOverlay?.updateTranscript(text)
        appendDictation(text)
        // User is still speaking — push the silence countdown back.
        armVoiceSilenceTimer()
    }

    func onFinal(_ text: String) {
        cancelVoiceSilenceTimer()
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
        cancelVoiceSilenceTimer()
        voicePromptPrefix = nil
        showBanner("⚠️ \(userVisibleMessage)")
    }

    /// (Re-)arm the silence watchdog. Called on listening-start and on
    /// every partial — so a continuously talking user never trips it.
    /// When it fires, request the host to commit the current hypothesis,
    /// which dismisses the overlay via `onFinal` / `onListeningStopped`.
    private func armVoiceSilenceTimer() {
        voiceSilenceTimer?.invalidate()
        voiceSilenceTimer = Timer.scheduledTimer(
            withTimeInterval: Self.voiceSilenceTimeout,
            repeats: false
        ) { [weak self] _ in
            self?.voiceController.requestStop()
        }
    }

    private func cancelVoiceSilenceTimer() {
        voiceSilenceTimer?.invalidate()
        voiceSilenceTimer = nil
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
        // No confirm/cancel hooks — the overlay auto-dismisses via the
        // silence timer below. Tapping the mic key again still works
        // because the overlay has `isUserInteractionEnabled = false`
        // so touches fall through to the keys.
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
//
// Android-style listening surface: a transparent backdrop layered OVER
// the keyboard (keys remain visible through it), with a Metal-shaded
// cyan aurora glow pulsing from the centre and a `✦ LISTENING…` label.
// Replaces the previous Wispr-style full-black overlay with dots.
//
// X (cancel) / ✓ (confirm) buttons stay in the corners but rendered
// over the aurora so the user can still bail out or commit the
// transcript explicitly.

private final class ListeningOverlayView: UIView {

    private let aurora       = ListeningAuroraView()
    private let captionLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor.black.withAlphaComponent(0.45)
        // The overlay no longer carries ✕ / ✓ buttons — silence detection
        // auto-dismisses it. Letting touches fall through means the
        // keyboard underneath stays interactive (mic key still toggles).
        isUserInteractionEnabled = false
        layout()
    }
    required init?(coder: NSCoder) { fatalError() }

    func updateTranscript(_ text: String) {
        // Caption is intentionally static — the transcript is typed into
        // the prompt / host text field (see `onPartial` in the controller),
        // not echoed in the overlay. Keep this entry point so callers don't
        // need to change, but no-op the visual update.
    }

    private func layout() {
        // Aurora glow — fills the overlay, draws nothing where the
        // bowl / wisps aren't active so the host keys read through
        // the transparent parts.
        aurora.translatesAutoresizingMaskIntoConstraints = false
        aurora.isUserInteractionEnabled = false
        addSubview(aurora)

        // Centered caption with a soft black shadow so it stays
        // readable as the aurora ribbons pass behind it. Text is
        // hardcoded — transcript goes into the text field, not here.
        captionLabel.text = "✦ LISTENING…"
        captionLabel.font = .systemFont(ofSize: 17, weight: .semibold)
        captionLabel.textColor = .white
        captionLabel.textAlignment = .center
        captionLabel.numberOfLines = 1
        captionLabel.translatesAutoresizingMaskIntoConstraints = false
        captionLabel.layer.shadowColor   = UIColor.black.cgColor
        captionLabel.layer.shadowOpacity = 0.55
        captionLabel.layer.shadowRadius  = 6
        captionLabel.layer.shadowOffset  = .zero
        addSubview(captionLabel)

        NSLayoutConstraint.activate([
            aurora.topAnchor.constraint(equalTo: topAnchor),
            aurora.bottomAnchor.constraint(equalTo: bottomAnchor),
            aurora.leadingAnchor.constraint(equalTo: leadingAnchor),
            aurora.trailingAnchor.constraint(equalTo: trailingAnchor),

            captionLabel.centerXAnchor.constraint(equalTo: centerXAnchor),
            captionLabel.centerYAnchor.constraint(equalTo: centerYAnchor),
            captionLabel.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 24),
            captionLabel.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -24),
        ])

        aurora.start()
    }
}

// MARK: - ListeningAuroraView (Metal hemisphere bowl + smoke wisps)
//
// Direct port of android/.../VoiceStageView's OpenGL ES 2.0 shader:
// a cyan bowl-arc rim with smoke wisps rising from its interior,
// dimmed reflection below a mirror line, and an arc-reveal sweep
// that runs right → bottom → left on open and continues right → left
// on close. Rendered to a transparent drawable so the keyboard keys
// remain visible behind the bowl.

private final class ListeningAuroraView: UIView {

    private let metalView: MTKView
    private let renderer: ListeningAuroraRenderer?

    init() {
        let device = MTLCreateSystemDefaultDevice()
        let mtk = MTKView(frame: .zero, device: device)
        mtk.translatesAutoresizingMaskIntoConstraints = false
        mtk.isUserInteractionEnabled = false
        mtk.isOpaque = false
        mtk.framebufferOnly = true
        mtk.colorPixelFormat = .bgra8Unorm
        mtk.preferredFramesPerSecond = 60
        mtk.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        mtk.isPaused = true
        mtk.enableSetNeedsDisplay = false
        self.metalView = mtk
        self.renderer = device.flatMap { ListeningAuroraRenderer(device: $0) }

        super.init(frame: .zero)
        backgroundColor = .clear
        clipsToBounds = true
        isUserInteractionEnabled = false

        mtk.delegate = renderer
        addSubview(mtk)
        NSLayoutConstraint.activate([
            mtk.topAnchor.constraint(equalTo: topAnchor),
            mtk.bottomAnchor.constraint(equalTo: bottomAnchor),
            mtk.leadingAnchor.constraint(equalTo: leadingAnchor),
            mtk.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    func start() {
        renderer?.openSweep()
        metalView.isPaused = false
    }

    func stop() {
        metalView.isPaused = true
    }
}

private final class ListeningAuroraRenderer: NSObject, MTKViewDelegate {

    // Shader inputs packed into a single struct passed via setFragmentBytes.
    // Mirrors the Android `u_time / u_resolution / u_loudness / u_revealProgress`
    // uniform layout.
    private struct Uniforms {
        var time: Float
        var reveal: Float
        var loudness: Float
        var _pad: Float = 0
        var resolution: SIMD2<Float>
    }

    private let device: MTLDevice
    private let commandQueue: MTLCommandQueue
    private let pipelineState: MTLRenderPipelineState

    // Open/close sweep timing (matches Android exactly).
    private static let openDelay: CFTimeInterval = 0.12
    private static let openDuration: CFTimeInterval = 0.65

    private var openStart: CFTimeInterval = 0
    private var animStart: CFTimeInterval = 0

    init?(device: MTLDevice) {
        guard let queue = device.makeCommandQueue() else { return nil }
        self.device = device
        self.commandQueue = queue

        // Port of `android/.../VoiceStageView.RenderThread.FRAG_SRC` to MSL.
        // The bowl geometry, smoke wisps, halos, palette, and reflection
        // dimming are line-for-line equivalent; only language-specific
        // differences (precision qualifiers, gl_FragColor → return,
        // varying → [[stage_in]]) change between the two.
        let src = """
        #include <metal_stdlib>
        using namespace metal;

        struct VOut {
            float4 position [[position]];
            float2 uv;
        };

        struct Uniforms {
            float time;
            float reveal;
            float loudness;
            float _pad;
            float2 resolution;
        };

        vertex VOut vertexShader(uint vid [[vertex_id]]) {
            float2 pos[6] = {
                float2(-1, -1), float2( 1, -1), float2(-1,  1),
                float2( 1, -1), float2( 1,  1), float2(-1,  1),
            };
            VOut o;
            o.position = float4(pos[vid], 0, 1);
            // Match GLSL convention: a_position * 0.5 + 0.5.
            o.uv = pos[vid] * 0.5 + 0.5;
            return o;
        }

        float hash(float2 p) {
            return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
        }

        float noise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            float a = hash(i);
            float b = hash(i + float2(1.0, 0.0));
            float c = hash(i + float2(0.0, 1.0));
            float d = hash(i + float2(1.0, 1.0));
            return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
        }

        float fbm(float2 p) {
            float v = 0.0;
            float a = 0.5;
            for (int i = 0; i < 6; i++) {
                v += a * noise(p);
                p *= 2.0;
                a *= 0.5;
            }
            return v;
        }

        fragment float4 fragmentShader(VOut in [[stage_in]],
                                       constant Uniforms &u [[buffer(0)]]) {
            float2 v_uv = in.uv;
            float u_time = u.time;
            float u_revealProgress = u.reveal;
            float2 u_resolution = u.resolution;

            // Normalize so uv.y in [-0.5, 0.5]; uv.x widens with aspect.
            // GLSL has y up; Metal's o.uv was set to match GL, so no flip.
            float2 frag = v_uv * u_resolution;
            float2 uv = (frag - 0.5 * u_resolution) / u_resolution.y;

            // Mirror sits a bit below center; small dark gap before reflection.
            float mirrorY = -0.08;
            bool isReflection = uv.y < mirrorY;
            float2 p = uv;
            if (isReflection) {
                p.y = 2.0 * mirrorY - p.y;
            }

            // Place the bowl so its bottom arc lands just above the mirror.
            p.y -= 0.32;

            // Bowl horizontal half-width = 0.35 * aspect (arc spans 70% of
            // keyboard width). xStretch is derived from radius + aspect so
            // radius alone controls vertical extent.
            float aspect = u_resolution.x / u_resolution.y;
            float radius = 0.35;
            float xStretch = radius / (0.35 * aspect);
            float2 ep = p * float2(xStretch, 1.0);
            float d = length(ep);
            float ang = atan2(ep.y, ep.x);

            // ---- Arc reveal: single brush stroke right → bottom → left
            //   sweepDist:  0 at right, 0.5 at bowl floor, 1 at left.
            //   progress:   0 → 1 = open sweep, 1 → 2 = close sweep.
            float sweepDist = clamp(-ang / 3.1416, 0.0, 1.0);
            float front = min(u_revealProgress,         1.0) * 1.2 - 0.1;
            float back  = max(u_revealProgress - 1.0, 0.0) * 1.2 - 0.1;
            float revealMask = smoothstep(back  - 0.06, back  + 0.06, sweepDist)
                             * (1.0 - smoothstep(front - 0.06,
                                                 front + 0.06, sweepDist));

            // ---- Bowl arc: sharp cyan rim accent at d ≈ radius, masked
            // to the bottom half.
            float thickness = 0.20;
            float ringDist = (d - radius) / thickness;
            float falloffScale = mix(1.2, 3.5, step(0.0, ringDist));
            float ring = exp(-pow(ringDist * falloffScale, 2.0));

            float bottomness = pow(clamp(-sin(ang), 0.0, 1.0), 1.5);
            float bowlMask = smoothstep(0.10, -0.95, sin(ang));
            float bowl = ring * (bowlMask * 0.35 + bottomness * 0.70) * revealMask;

            // ---- Wisps rising from the bottom, blown right with wind shear.
            float shear = (p.y + 0.32) * 2.0;

            float2 warp = float2(
                fbm(p * 3.5 + float2(0.0, u_time * 0.25)) - 0.5,
                fbm(p * 3.5 + float2(5.7, u_time * 0.25)) - 0.5
            ) * 0.10;
            float2 pw = p + warp;

            float2 wispUv1 = pw * float2(8.0, 2.8);
            wispUv1.y -= u_time * 0.65;
            wispUv1.x -= u_time * 0.22 + shear;

            float2 wispUv2 = pw * float2(13.0, 4.5) + float2(13.7, 4.2);
            wispUv2.y -= u_time * 0.95;
            wispUv2.x -= u_time * 0.38 + shear * 1.5;

            // Smoke shape — keep peaks bloomy, suppress quiet noise body.
            float wispsRaw = fbm(wispUv1) * 0.55 + fbm(wispUv2) * 0.45;
            float wisps = smoothstep(0.36, 0.78, wispsRaw);
            wisps = pow(wisps, 0.85);

            float insideMask = smoothstep(radius + 0.02, radius - 0.30, d);
            float wispVerticalFade = smoothstep(0.35, -0.32, p.y);

            // Smoke + halo gate: ramps in during second half of open,
            // ramps out during first 70% of close.
            float revealGate = smoothstep(0.3, 1.0, u_revealProgress)
                             * (1.0 - smoothstep(1.0, 1.7, u_revealProgress));
            float wispGlow = wisps * insideMask * wispVerticalFade
                           * 2.4 * revealGate;

            // ---- Soft outer halo, gated by the same ramp.
            float halo = exp(-d * 6.0) * 0.10 * revealGate;

            // ---- Inner halo: softer ring just inside the cyan rim accent.
            float blueR = radius - 0.035;
            float blueRingDist = (d - blueR) / 0.05;
            float blueInnerRing = exp(-blueRingDist * blueRingDist);
            float blueInnerMask = smoothstep(-0.05, -0.95, sin(ang));
            float blueInnerGlow = blueInnerRing * blueInnerMask * 0.55 * revealMask;

            // ---- Aurora palette (matches GeneratingLoaderView's wave palette):
            //   violetEdge → bright cyan rim accent (bowl bottom edge)
            //   blueBody   → cool teal-blue body / inner halo / halo
            //   lavender   → bright mint-cyan smoke wisps
            float3 violetEdge = float3(0.30, 0.87, 1.00);
            float3 blueBody   = float3(0.18, 0.55, 0.93);
            float3 lavender   = float3(0.55, 0.95, 0.85);

            float3 colorBowl = mix(blueBody, violetEdge, bottomness);

            float3 color = colorBowl * bowl
                         + blueBody  * blueInnerGlow
                         + lavender  * wispGlow
                         + blueBody  * halo * 1.2;

            color = min(color, float3(1.0));

            // Reflection — smooth fade away from the mirror.
            if (isReflection) {
                float boundary = smoothstep(mirrorY - 0.08, mirrorY, uv.y);
                float fade     = smoothstep(-0.50, -0.15, uv.y);
                color *= mix(0.40, 1.0, boundary) * fade;
            }

            // Premultiplied alpha — alpha tracks the brightest channel so
            // the rim + smoke composite cleanly over the keyboard.
            float alpha = clamp(max(max(color.r, color.g), color.b), 0.0, 1.0);
            return float4(color * alpha, alpha);
        }
        """

        guard let library = try? device.makeLibrary(source: src, options: nil) else {
            return nil
        }
        guard let vertexFn = library.makeFunction(name: "vertexShader"),
              let fragmentFn = library.makeFunction(name: "fragmentShader") else {
            return nil
        }

        let desc = MTLRenderPipelineDescriptor()
        desc.vertexFunction = vertexFn
        desc.fragmentFunction = fragmentFn
        desc.colorAttachments[0].pixelFormat = .bgra8Unorm
        // Premultiplied alpha — shader returns (rgb * alpha, alpha) so we
        // pair (source = .one, destination = .oneMinusSourceAlpha) to
        // composite cleanly over whatever's behind the keyboard.
        desc.colorAttachments[0].isBlendingEnabled = true
        desc.colorAttachments[0].rgbBlendOperation = .add
        desc.colorAttachments[0].alphaBlendOperation = .add
        desc.colorAttachments[0].sourceRGBBlendFactor = .one
        desc.colorAttachments[0].sourceAlphaBlendFactor = .one
        desc.colorAttachments[0].destinationRGBBlendFactor = .oneMinusSourceAlpha
        desc.colorAttachments[0].destinationAlphaBlendFactor = .oneMinusSourceAlpha

        guard let pipeline = try? device.makeRenderPipelineState(descriptor: desc) else {
            return nil
        }
        self.pipelineState = pipeline
        super.init()
    }

    /// Begin the open sweep (revealProgress 0 → 1 over ~650ms after a
    /// 120ms delay). Resets the animation clock.
    func openSweep() {
        openStart = CACurrentMediaTime()
        animStart = openStart
    }

    /// Smoothstep ease in/out — equivalent to Android's
    /// AccelerateDecelerateInterpolator on a 0..1 range.
    private static func easeInOut(_ t: CFTimeInterval) -> CFTimeInterval {
        let clamped = max(0, min(1, t))
        return clamped * clamped * (3 - 2 * clamped)
    }

    /// Current revealProgress in [0, 1], driven by the wall clock from
    /// `openSweep()`. We only do the open sweep here — the overlay
    /// disappears with the parent view's removal, no close animation.
    private func computeReveal() -> Float {
        let elapsed = CACurrentMediaTime() - openStart - Self.openDelay
        if elapsed <= 0 { return 0 }
        let t = elapsed / Self.openDuration
        let eased = Self.easeInOut(t)
        return Float(eased)
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {}

    func draw(in view: MTKView) {
        guard let drawable = view.currentDrawable,
              let descriptor = view.currentRenderPassDescriptor,
              let buffer = commandQueue.makeCommandBuffer(),
              let encoder = buffer.makeRenderCommandEncoder(descriptor: descriptor) else {
            return
        }
        let size = view.drawableSize
        var uniforms = Uniforms(
            time: Float(CACurrentMediaTime() - animStart),
            reveal: computeReveal(),
            loudness: 0,
            resolution: SIMD2<Float>(Float(size.width), Float(size.height))
        )
        encoder.setRenderPipelineState(pipelineState)
        encoder.setFragmentBytes(&uniforms, length: MemoryLayout<Uniforms>.size, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6)
        encoder.endEncoding()
        buffer.present(drawable)
        buffer.commit()
    }
}

// MARK: - SuggestionEngine
//
// iOS port of `android/.../suggest/SuggestionEngine`. Same on-disk
// dictionary (`commands/dict/en_unigrams.txt`, 82k words sorted by
// global frequency) plus a private per-user vocabulary store, so the
// two platforms produce the same prefix completions and a user that
// types on both ends up with the same personalized weighting.
//
// Suggest priority order, mirroring Android:
//   1. user vocabulary (words the user has typed before, personal freq)
//   2. dictionary prefix completion (global freq)
// We deliberately skip Android's third-tier SymSpell edit-distance
// fallback for now — `UITextChecker.guesses(...)` already covers typo
// correction on iOS and porting SymSpell would double the bundle size
// (its prefix-edits dictionary serializes to ~5 MB). If the user types
// "tge" we still get "the" through UITextChecker; if they type "th"
// they get "the / that / they / them" through our engine.

fileprivate final class SuggestionEngine {

    private static let dictAsset = "en_unigrams"

    /// Words from the bundled dictionary, sorted by frequency desc.
    /// Loaded lazily on a background queue; `nil` until ready.
    private var sortedWords: [String]?
    private var readyLock = NSLock()
    private var loading = false
    private(set) var isReady = false

    /// Per-user vocabulary. Reads / writes are cheap (in-memory cache
    /// backed by App Group UserDefaults under a single JSON key).
    let userStore: UserWordStore

    /// Fires once the bundled dictionary becomes available so the
    /// keyboard can repaint the suggestion strip — without this, the
    /// strip stays empty after a cold launch until the next keystroke.
    var onReady: (() -> Void)?

    init() {
        self.userStore = UserWordStore()
    }

    /// Idempotent. Returns immediately; load runs on a background QoS.
    func loadAsync() {
        readyLock.lock()
        if isReady || loading { readyLock.unlock(); return }
        loading = true
        readyLock.unlock()

        // .userInitiated so the dictionary lands fast on cold launch
        // (was .utility, which can starve under a busy main thread).
        // The keyboard's most important user-facing behaviour after
        // typing speed is suggestion freshness, so we treat this load
        // as user-blocking work.
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            let t0 = CFAbsoluteTimeGetCurrent()
            let words = Self.loadSortedWords()
            self.readyLock.lock()
            self.sortedWords = words
            self.isReady = (words != nil)
            self.loading = false
            let cb = self.onReady
            self.readyLock.unlock()
            let ms = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
            NSLog("TurtleSuggest: dict ready in \(ms)ms (\(words?.count ?? 0) words)")
            cb?()
        }
    }

    /// Top suggestions for `prefix`. Returns lowercase strings — the
    /// caller restores case to match the active shift state when it
    /// commits. Capped at `max`. Safe to call before the dictionary is
    /// loaded; falls back to user vocabulary only in that window.
    func suggest(prefix: String, max: Int = 3) -> [String] {
        guard !prefix.isEmpty, max > 0 else { return [] }
        let q = prefix.lowercased()
        var out: [String] = []
        var seen = Set<String>()

        // 1. User vocabulary (personal frequency).
        for w in userStore.prefixMatches(q, max: max) {
            if seen.insert(w).inserted {
                out.append(w)
                if out.count >= max { return out }
            }
        }

        // 2. Bundled dictionary (global frequency).
        if let dict = readSortedWords(), out.count < max {
            for w in dict where w.hasPrefix(q) {
                if seen.insert(w).inserted {
                    out.append(w)
                    if out.count >= max { return out }
                }
            }
        }

        return out
    }

    /// Top `max` words the user has typed before, ordered by personal
    /// frequency. Used to populate the empty-field chip strip in place
    /// of the old static "Standup / Today / Birthday" shortcuts.
    func topUserWords(max: Int = 3) -> [String] {
        userStore.topWords(max: max)
    }

    /// Record a committed word (called from `processKey` when a word
    /// completes with space / punctuation). Filters out tokens that
    /// aren't pure alpha — URLs, numbers, slash commands, emoji.
    func learn(_ word: String) {
        let w = word.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !w.isEmpty, w.count >= 2 else { return }
        for ch in w.unicodeScalars {
            // Allow letters + apostrophe + hyphen (Android does the same).
            if !CharacterSet.letters.contains(ch) && ch != "'" && ch != "-" {
                return
            }
        }
        userStore.bump(w)
    }

    private func readSortedWords() -> [String]? {
        readyLock.lock(); defer { readyLock.unlock() }
        return sortedWords
    }

    /// Parse the bundled `<word> <freq>` file once. Returns the words
    /// sorted by descending frequency so a prefix scan finds common
    /// hits in the first few hundred entries.
    private static func loadSortedWords() -> [String]? {
        // Try Bundle.main first (the extension's own bundle when called
        // from the appex process). Fall back to Bundle(for:) which
        // anchors to the bundle containing this class — useful when
        // some host edge case shifts Bundle.main unexpectedly.
        var url = Bundle.main.url(forResource: Self.dictAsset, withExtension: "txt")
        if url == nil {
            url = Bundle(for: SuggestionEngine.self).url(forResource: Self.dictAsset, withExtension: "txt")
        }
        guard let url = url else {
            NSLog("TurtleSuggest: en_unigrams.txt NOT FOUND in bundle (main=\(Bundle.main.bundlePath))")
            return nil
        }
        guard let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8) else {
            NSLog("TurtleSuggest: failed to read \(url.path)")
            return nil
        }
        var entries: [(String, Int64)] = []
        entries.reserveCapacity(82_000)
        text.enumerateLines { line, _ in
            guard let sp = line.firstIndex(of: " ") else { return }
            let word = String(line[..<sp])
            let freq = Int64(line[line.index(after: sp)...]) ?? 0
            if !word.isEmpty, freq > 0 {
                entries.append((word, freq))
            }
        }
        entries.sort { $0.1 > $1.1 }
        return entries.map { $0.0 }
    }
}

// MARK: - UserWordStore
//
// iOS port of Android's `UserWordStore`. Persists per-word counts in
// the App Group UserDefaults under a single JSON-encoded key (cheaper
// than thousands of individual `set(:forKey:)` writes on every space).
// Capped at MAX_ENTRIES — eviction drops the lowest-count entries in
// batches once full.

fileprivate final class UserWordStore {

    private static let storeKey = "TurtleKB.userWordCounts"
    private static let maxEntries = 2000
    private static let evictBatch = 200

    private let defaults: UserDefaults
    private var cache: [String: Int]
    private let lock = NSLock()

    init() {
        let store = UserDefaults(suiteName: SplitContract.storageSuiteName) ?? .standard
        self.defaults = store
        if let data = store.data(forKey: Self.storeKey),
           let decoded = try? JSONDecoder().decode([String: Int].self, from: data) {
            self.cache = decoded
        } else {
            self.cache = [:]
        }
    }

    /// Bump the count for `word`, persisting back to UserDefaults. The
    /// disk write coalesces multiple bumps inside the same runloop tick
    /// via the `pendingFlush` flag so a burst of typed words doesn't
    /// re-encode the whole JSON blob N times.
    func bump(_ word: String) {
        lock.lock()
        cache[word, default: 0] += 1
        if cache.count > Self.maxEntries { evictLocked() }
        scheduleFlushLocked()
        lock.unlock()
    }

    /// Words in the store that start with `prefix`, sorted by count
    /// descending. Case-insensitive (the store is lowercase).
    func prefixMatches(_ prefix: String, max: Int) -> [String] {
        guard !prefix.isEmpty, max > 0 else { return [] }
        lock.lock(); defer { lock.unlock() }
        let p = prefix.lowercased()
        var hits: [(String, Int)] = []
        for (k, v) in cache where k.hasPrefix(p) {
            hits.append((k, v))
        }
        hits.sort { $0.1 > $1.1 }
        return hits.prefix(max).map { $0.0 }
    }

    /// Top `max` words by personal frequency, regardless of prefix.
    /// Powers the empty-field "frequent words" chip strip.
    func topWords(max: Int) -> [String] {
        guard max > 0 else { return [] }
        lock.lock(); defer { lock.unlock() }
        return cache.sorted { $0.value > $1.value }
            .prefix(max)
            .map { $0.key }
    }

    func clear() {
        lock.lock()
        cache.removeAll()
        defaults.removeObject(forKey: Self.storeKey)
        lock.unlock()
    }

    // MARK: - Persistence

    private var pendingFlush = false

    /// Schedule a single JSON re-encode at the end of the runloop so a
    /// burst of bumps amortises into one write. Must be called under
    /// `lock`.
    private func scheduleFlushLocked() {
        guard !pendingFlush else { return }
        pendingFlush = true
        DispatchQueue.global(qos: .background).async { [weak self] in
            guard let self = self else { return }
            self.lock.lock()
            self.pendingFlush = false
            let snapshot = self.cache
            self.lock.unlock()
            if let data = try? JSONEncoder().encode(snapshot) {
                self.defaults.set(data, forKey: Self.storeKey)
            }
        }
    }

    private func evictLocked() {
        let target = Self.maxEntries - Self.evictBatch
        let removeCount = cache.count - target
        guard removeCount > 0 else { return }
        let sorted = cache.sorted { $0.value < $1.value }
        for i in 0..<min(removeCount, sorted.count) {
            cache.removeValue(forKey: sorted[i].key)
        }
    }
}

// MARK: - KeyRowView

/// Row container that extends hit-testing into the visual gap between
/// keys. Apple's native keyboard treats those gaps as part of the
/// nearer key's tappable area — a finger that lands a couple of points
/// to the side of a glyph still registers as a tap on that key. The
/// previous plain `UIView` row left the gap as a dead zone, which is
/// what made fast typing feel like characters got "swallowed."
fileprivate final class KeyRowView: UIView {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        // Outside the row entirely → not ours.
        guard bounds.contains(point) else { return nil }
        // Normal hit test first — if the point lands directly on a key
        // button, return it unchanged so things like long-press
        // gesture recognizers attached to the button still fire.
        if let view = super.hitTest(point, with: event), view !== self {
            return view
        }
        // Point is inside the row but landed in the inter-key gap.
        // Forward it to the button whose horizontal midpoint is
        // closest — that's the key the user was aiming for.
        var nearest: UIView?
        var bestDist: CGFloat = .greatestFiniteMagnitude
        for sub in subviews where sub is UIButton {
            let dist = abs(sub.frame.midX - point.x)
            if dist < bestDist {
                bestDist = dist
                nearest = sub
            }
        }
        return nearest
    }
}

// MARK: - PaddedLabel

/// `UILabel` with symmetric pixel-based content insets. Used by the
/// command-bar pill so left / right padding stays balanced regardless of
/// the font (space-character widths vary by font and weight, so the
/// previous space-padded approach drifted as we changed fonts).
fileprivate final class PaddedLabel: UILabel {

    var textInsets: UIEdgeInsets = .zero {
        didSet { invalidateIntrinsicContentSize() }
    }

    override var intrinsicContentSize: CGSize {
        let base = super.intrinsicContentSize
        return CGSize(
            width:  base.width  + textInsets.left + textInsets.right,
            height: base.height + textInsets.top  + textInsets.bottom
        )
    }

    override func drawText(in rect: CGRect) {
        super.drawText(in: rect.inset(by: textInsets))
    }

    override func textRect(forBounds bounds: CGRect,
                           limitedToNumberOfLines numberOfLines: Int) -> CGRect {
        let inset = bounds.inset(by: textInsets)
        let rect = super.textRect(forBounds: inset, limitedToNumberOfLines: numberOfLines)
        return CGRect(
            x: rect.origin.x - textInsets.left,
            y: rect.origin.y - textInsets.top,
            width: rect.size.width + textInsets.left + textInsets.right,
            height: rect.size.height + textInsets.top + textInsets.bottom
        )
    }
}

// MARK: - GeneratingWaveView (Metal aurora)
//
// Fragment-shader port of Android's `GeneratingLoaderView`. Single
// full-screen quad rendered with a Metal pixel shader that does all
// three wave layers in one pass — the 8-colour aurora palette flows
// horizontally, three soft sine ribbons glow over a dark navy ground,
// and overlapping crests brighten via additive blending. Strictly more
// faithful to the Android look than the Core Animation port: the wave
// glow is a true smoothstep falloff (no hard edges), the palette
// crossfades through every neighbouring colour (not just three slim
// gradients sliding past each other), and the whole surface is one
// composited image rather than three masked pipes.
//
// Runtime compilation of the shader keeps the .xcodeproj edit-free —
// no .metal file, no PBXFileReference dance.

import MetalKit

fileprivate final class GeneratingWaveView: UIView {

    private let metalView: MTKView
    private let renderer: AuroraRenderer?
    private let label = UILabel()

    init() {
        let device = MTLCreateSystemDefaultDevice()
        let mtk = MTKView(frame: .zero, device: device)
        mtk.translatesAutoresizingMaskIntoConstraints = false
        mtk.isUserInteractionEnabled = false
        // Non-opaque + clearColor with alpha 0 lets the drawable
        // composite over whatever sits behind the wave view. The
        // fragment shader writes premultiplied alpha so areas
        // outside the wave ribbons read as fully transparent.
        mtk.isOpaque = false
        mtk.framebufferOnly = true
        mtk.colorPixelFormat = .bgra8Unorm
        // Pause until startAnimating; isPaused = true + enableSetNeedsDisplay = false
        // means no drawable acquisition happens while hidden.
        mtk.isPaused = true
        mtk.enableSetNeedsDisplay = false
        mtk.preferredFramesPerSecond = 60
        mtk.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        self.metalView = mtk
        self.renderer = device.flatMap { AuroraRenderer(device: $0) }

        super.init(frame: .zero)
        backgroundColor = .clear
        clipsToBounds = true
        isUserInteractionEnabled = false

        mtk.delegate = renderer
        addSubview(mtk)

        // Pure white at semibold so the message reads cleanly against
        // the aurora. The previous muted mint-cyan blended into the
        // ribbons' colour family and disappeared whenever a crest
        // passed underneath. A heavier black shadow underneath the text
        // boosts contrast on the bright wave peaks even further.
        label.font = .systemFont(ofSize: 15, weight: .semibold)
        label.textColor = .white
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        label.layer.shadowColor   = UIColor.black.cgColor
        label.layer.shadowOpacity = 0.95
        label.layer.shadowRadius  = 6
        label.layer.shadowOffset  = CGSize(width: 0, height: 1)
        addSubview(label)

        NSLayoutConstraint.activate([
            mtk.topAnchor.constraint(equalTo: topAnchor),
            mtk.bottomAnchor.constraint(equalTo: bottomAnchor),
            mtk.leadingAnchor.constraint(equalTo: leadingAnchor),
            mtk.trailingAnchor.constraint(equalTo: trailingAnchor),

            label.centerXAnchor.constraint(equalTo: centerXAnchor),
            label.centerYAnchor.constraint(equalTo: centerYAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) not used") }

    func setMessage(_ text: String) {
        label.text = text
    }

    func startAnimating() {
        renderer?.start()
        metalView.isPaused = false
        // Breathing on the label so the overlay reads as alive even
        // when the user's gaze is on the chat field.
        label.alpha = 0.85
        UIView.animate(
            withDuration: 1.2,
            delay: 0,
            options: [.repeat, .autoreverse, .allowUserInteraction, .curveEaseInOut],
            animations: { self.label.alpha = 1.0 },
            completion: nil
        )
    }

    func stopAnimating() {
        metalView.isPaused = true
        label.layer.removeAllAnimations()
    }
}

// MARK: - AuroraRenderer (Metal renderer for GeneratingWaveView)

fileprivate final class AuroraRenderer: NSObject, MTKViewDelegate {

    private let device: MTLDevice
    private let commandQueue: MTLCommandQueue
    private let pipelineState: MTLRenderPipelineState
    private var startTime: CFTimeInterval = 0

    init?(device: MTLDevice) {
        guard let queue = device.makeCommandQueue() else { return nil }
        self.device = device
        self.commandQueue = queue

        // Runtime-compiled Metal Shading Language source. Two stages:
        //
        //   • vertexShader  — full-screen triangle pair from a 6-element
        //                     in-shader vertex array, no vertex buffer
        //                     needed. UV is computed in screen-relative
        //                     coords so the fragment shader sees y=0 at
        //                     the top and y=1 at the bottom of the bar.
        //
        //   • fragmentShader — composites three sine ribbons over a
        //                     dark navy background. Each ribbon has:
        //                       1. A soft smoothstep falloff around its
        //                          sine path (`thickness` half-width).
        //                       2. A colour sampled from the 8-stop
        //                          aurora palette at a flowing x offset
        //                          (palette cycles per `flowSpeed`).
        //                     Ribbons combine with `1 - (1-acc) * (1-c)`
        //                     (screen-blend) so crests where ribbons
        //                     overlap lift toward pearl-white without
        //                     ever clipping.
        let src = """
        #include <metal_stdlib>
        using namespace metal;

        struct VOut {
            float4 position [[position]];
            float2 uv;
        };

        vertex VOut vertexShader(uint vid [[vertex_id]]) {
            // Two-triangle quad covering NDC.
            float2 pos[6] = {
                float2(-1, -1), float2( 1, -1), float2(-1,  1),
                float2( 1, -1), float2( 1,  1), float2(-1,  1),
            };
            VOut o;
            o.position = float4(pos[vid], 0, 1);
            // Flip y so uv = (0,0) is top-left, (1,1) is bottom-right.
            o.uv = float2((pos[vid].x + 1.0) * 0.5,
                          (1.0 - pos[vid].y) * 0.5);
            return o;
        }

        constant float3 PALETTE[8] = {
            float3(0.063, 0.114, 0.333),  // deep blue   #101D55
            float3(0.118, 0.314, 0.506),  // blue mid    #1E5081
            float3(0.165, 0.482, 0.569),  // cyan        #2A7B91
            float3(0.416, 0.612, 0.549),  // peak        #6A9C8C
            float3(0.180, 0.514, 0.384),  // mint        #2E8362
            float3(0.071, 0.408, 0.353),  // teal        #12685A
            float3(0.118, 0.314, 0.506),  // blue mid (wrap)
            float3(0.063, 0.114, 0.333),  // deep blue (seamless)
        };

        // Crossfade through the 7-segment palette at normalized x in [0,1).
        float3 samplePalette(float x) {
            x = fract(x);
            float s = x * 7.0;
            int idx = int(floor(s));
            float t = s - float(idx);
            // Smoothstep gives a softer transition than linear mix at the
            // segment boundaries — important because the eye picks up the
            // crease in a linear gradient.
            t = smoothstep(0.0, 1.0, t);
            return mix(PALETTE[idx], PALETTE[idx + 1], t);
        }

        struct Wave {
            float yCenter;
            float amp;
            float freq;
            float waveSpeed;
            float flowSpeed;
            float phase;
            float thickness;
        };

        fragment float4 fragmentShader(VOut in [[stage_in]],
                                       constant float &time [[buffer(0)]]) {
            float2 uv = in.uv;
            float t = time;
            float twoPi = 6.28318530718;

            // Three waves. Same constants as android's `WAVES[]` array
            // but `thickness` is expressed in normalized fraction of bar
            // height (not pt) — the smoothstep below uses it as a
            // half-width on uv.y, so it scales with the bar.
            Wave waves[3] = {
                { 0.42, 0.12, 1.2, 0.50, 0.16, 0.00, 0.22 },
                { 0.50, 0.16, 1.0, 0.45, 0.13, 0.30, 0.30 },
                { 0.58, 0.10, 1.5, 0.60, 0.20, 0.55, 0.18 },
            };

            // Start fully transparent — only the wave ribbons paint
            // any visible pixels. The previous dark-navy `result` fill
            // forced the whole bar to render as an opaque tile; now
            // pixels outside every ribbon stay clear and host content
            // shows through.
            float3 result = float3(0.0, 0.0, 0.0);
            float alpha   = 0.0;

            for (int i = 0; i < 3; ++i) {
                Wave w = waves[i];
                float sinePhase = w.phase * twoPi + t * w.waveSpeed;
                float waveY = w.yCenter + w.amp * sin(uv.x * w.freq * twoPi + sinePhase);
                float dist = abs(uv.y - waveY);
                // Soft falloff — fully bright at the ribbon's center,
                // fading to 0 at `thickness` half-width away.
                float intensity = smoothstep(w.thickness, 0.0, dist);
                if (intensity <= 0.001) continue;

                // Colour flows along x at the ribbon's flowSpeed.
                float flowX = uv.x + t * w.flowSpeed + w.phase;
                float3 c = samplePalette(flowX);

                // Boost peak colour so the centre of each ribbon reads
                // brighter than the falloff — gives the ribbons real
                // luminance instead of looking like flat-coloured tubes.
                c = c * (0.55 + 0.85 * intensity);

                // Screen-blend over the accumulating result. Crests
                // where waves overlap brighten toward pearl-white.
                result = 1.0 - (1.0 - result) * (1.0 - c * intensity);
                // Accumulate alpha the same way so overlapping ribbons
                // build a more solid fill while isolated pixels stay
                // partially transparent (smooth edges).
                alpha = 1.0 - (1.0 - alpha) * (1.0 - intensity);
            }

            // Metal's CAMetalLayer expects premultiplied alpha by
            // default — multiply the RGB by alpha so the drawable
            // composites correctly when the MTKView is non-opaque.
            return float4(result * alpha, alpha);
        }
        """

        guard let library = try? device.makeLibrary(source: src, options: nil) else {
            return nil
        }
        guard let vertexFn = library.makeFunction(name: "vertexShader"),
              let fragmentFn = library.makeFunction(name: "fragmentShader") else {
            return nil
        }

        let desc = MTLRenderPipelineDescriptor()
        desc.vertexFunction = vertexFn
        desc.fragmentFunction = fragmentFn
        desc.colorAttachments[0].pixelFormat = .bgra8Unorm
        guard let pipeline = try? device.makeRenderPipelineState(descriptor: desc) else {
            return nil
        }
        self.pipelineState = pipeline
        super.init()
    }

    func start() {
        startTime = CACurrentMediaTime()
    }

    // MARK: MTKViewDelegate

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {
        // No-op — the shader is resolution-independent (works in
        // normalized uv space) so we don't need to rebuild anything on
        // resize.
    }

    func draw(in view: MTKView) {
        guard let drawable = view.currentDrawable,
              let descriptor = view.currentRenderPassDescriptor,
              let buffer = commandQueue.makeCommandBuffer(),
              let encoder = buffer.makeRenderCommandEncoder(descriptor: descriptor) else {
            return
        }
        var t = Float(CACurrentMediaTime() - startTime)
        encoder.setRenderPipelineState(pipelineState)
        encoder.setFragmentBytes(&t, length: MemoryLayout<Float>.size, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6)
        encoder.endEncoding()
        buffer.present(drawable)
        buffer.commit()
    }
}
