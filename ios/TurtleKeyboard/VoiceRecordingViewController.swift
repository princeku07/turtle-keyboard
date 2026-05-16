import UIKit
import AVFoundation
import Speech

/// Host-app side of the Wispr-Flow-style dictation flow.
///
/// The keyboard extension can't hold the mic (iOS sandbox + audio-session
/// rules), so when the user taps the mic key the keyboard sets a rendezvous
/// flag in the shared App Group and we land here via either:
///   • `turtlekeyboard://voice` deep link (when programmatic open succeeds), or
///   • `applicationDidBecomeActive` picking up the flag (when the user
///     swiped back to Turtle manually).
///
/// What this VC does:
///   1. Shows the Wispr-style "Swipe back to your app" coachmark.
///   2. Activates `.playAndRecord` so the audio session survives backgrounding,
///      starts `AVAudioEngine` + `SFSpeechRecognizer`, **and keeps recording
///      after the user swipes away** (the app has `UIBackgroundModes = audio`).
///   3. Streams partial transcripts to the App Group on every recognizer
///      callback so the keyboard can render them live.
///   4. Watches App Group flags for `stop` / `cancel` signals from the
///      keyboard (the user tapped ✓ or X in the keyboard's listening UI).
///      On stop it writes the final transcript + posts a Darwin notification.
///
/// The user does not interact with this VC after the initial coachmark —
/// once they swipe back, all subsequent UI lives inside the keyboard.
final class VoiceRecordingViewController: UIViewController {

    private static let appGroupID = "group.com.turtlekeyboard.split"
    // Keys shared with the keyboard extension. Keep in sync with
    // VoiceInputController.kPartial / kTranscript / kError / kStop / kCancel.
    private static let kPartial    = "voice.partialTranscript"
    private static let kTranscript = "voice.pendingTranscript"
    private static let kError      = "voice.pendingError"
    private static let kStop       = "voice.stopRequested"
    private static let kCancel     = "voice.cancelRequested"
    // Darwin notification names. The host posts kFinishedName when the
    // final transcript is written; it observes kControlName to react to
    // the keyboard's stop / cancel signals without polling.
    private static let kFinishedName: CFString = "com.turtlekeyboard.voice.didFinish" as CFString
    private static let kPartialName:  CFString = "com.turtlekeyboard.voice.didPartial" as CFString
    private static let kControlName:  CFString = "com.turtlekeyboard.voice.control"    as CFString
    private static let kCoachShown = "voice.coachShown.v1"

    private let audioEngine = AVAudioEngine()
    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    private var transcript: String = ""
    private var didDeliver = false
    private var didStartRecognition = false
    private var darwinObserver: UnsafeMutableRawPointer?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .white
        recognizer = SFSpeechRecognizer(locale: .current) ?? SFSpeechRecognizer()

        layoutCoachmark()
        registerDarwinControl()
        // Observe lifecycle so we can keep the engine alive when backgrounded.
        NotificationCenter.default.addObserver(
            self, selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification, object: nil)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // Apple requires the mic to be activated while the app is in
        // foreground. We do it immediately — no need to wait for the
        // coachmark to be dismissed; the coachmark is purely informational.
        requestAuthAndStart()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        unregisterDarwinControl()
    }

    // MARK: - Coachmark UI (Wispr-style "Swipe back to your app")

    private func layoutCoachmark() {
        let dimmer = UIView()
        dimmer.backgroundColor = .black
        dimmer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(dimmer)

        let card = UIView()
        card.backgroundColor = .white
        card.layer.cornerRadius = 28
        card.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(card)

        let title = UILabel()
        title.text = "Swipe back to your app"
        title.font = .systemFont(ofSize: 28, weight: .semibold)
        title.textColor = .black
        title.textAlignment = .center
        title.numberOfLines = 0

        let body = UILabel()
        body.text = "We wish you didn't have to switch apps to use voice, but Apple now requires it to activate the microphone."
        body.font = .systemFont(ofSize: 15)
        body.textColor = UIColor.black.withAlphaComponent(0.65)
        body.textAlignment = .center
        body.numberOfLines = 0

        // Illustration — phone with a "Listening / iPad Microphone" hint
        // and the iOS gesture-bar dot, mirroring Wispr's image 17.
        let illustration = CoachIllustrationView()
        illustration.translatesAutoresizingMaskIntoConstraints = false

        let hint = UILabel()
        hint.text = "Swipe right on the bar below"
        hint.font = .systemFont(ofSize: 14, weight: .medium)
        hint.textColor = .white
        hint.textAlignment = .center
        hint.backgroundColor = .black
        hint.layer.cornerRadius = 18
        hint.layer.masksToBounds = true
        hint.numberOfLines = 0

        let close = UIButton(type: .system)
        close.setImage(UIImage(systemName: "xmark"), for: .normal)
        close.tintColor = .black
        close.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)

        [title, body, illustration, hint, close].forEach {
            $0.translatesAutoresizingMaskIntoConstraints = false
            card.addSubview($0)
        }

        NSLayoutConstraint.activate([
            dimmer.topAnchor.constraint(equalTo: view.topAnchor),
            dimmer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            dimmer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            dimmer.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            card.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            card.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            card.widthAnchor.constraint(lessThanOrEqualToConstant: 420),
            card.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            card.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24),

            close.topAnchor.constraint(equalTo: card.topAnchor, constant: 20),
            close.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -20),
            close.widthAnchor.constraint(equalToConstant: 22),
            close.heightAnchor.constraint(equalToConstant: 22),

            title.topAnchor.constraint(equalTo: card.topAnchor, constant: 60),
            title.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 28),
            title.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -28),

            illustration.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 28),
            illustration.centerXAnchor.constraint(equalTo: card.centerXAnchor),
            illustration.widthAnchor.constraint(equalToConstant: 200),
            illustration.heightAnchor.constraint(equalToConstant: 220),

            body.topAnchor.constraint(equalTo: illustration.bottomAnchor, constant: 24),
            body.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 28),
            body.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -28),

            hint.topAnchor.constraint(equalTo: body.bottomAnchor, constant: 24),
            hint.centerXAnchor.constraint(equalTo: card.centerXAnchor),
            hint.heightAnchor.constraint(equalToConstant: 56),
            hint.widthAnchor.constraint(greaterThanOrEqualToConstant: 220),
            hint.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -36),
        ])
    }

    // MARK: - Permissions + recognition

    private func requestAuthAndStart() {
        guard !didStartRecognition else { return }
        SFSpeechRecognizer.requestAuthorization { [weak self] speechStatus in
            guard let self = self else { return }
            let speechOK = (speechStatus == .authorized)
            let micCb: (Bool) -> Void = { micOK in
                DispatchQueue.main.async {
                    if speechOK && micOK {
                        self.startRecognition()
                    } else {
                        self.finish(withError: speechOK
                                    ? "Microphone permission denied"
                                    : "Speech recognition denied")
                    }
                }
            }
            if #available(iOS 17.0, *) {
                AVAudioApplication.requestRecordPermission(completionHandler: micCb)
            } else {
                AVAudioSession.sharedInstance().requestRecordPermission(micCb)
            }
        }
    }

    private func startRecognition() {
        guard !didStartRecognition else { return }
        guard let recognizer = recognizer, recognizer.isAvailable else {
            finish(withError: "Speech recognition unavailable")
            return
        }

        let session = AVAudioSession.sharedInstance()
        do {
            // `.playAndRecord` is required for the audio session to survive
            // backgrounding (combined with UIBackgroundModes=audio in
            // Info.plist). `.measurement` mode is best for speech but only
            // works under `.record` — accept the trade-off for background
            // continuity.
            try session.setCategory(.playAndRecord, mode: .default,
                                    options: [.allowBluetooth, .defaultToSpeaker])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            finish(withError: "Audio session: \(error.localizedDescription)")
            return
        }

        audioEngine.reset()
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        self.request = request

        self.task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            guard let self = self else { return }
            if let result = result {
                let text = result.bestTranscription.formattedString
                DispatchQueue.main.async {
                    self.transcript = text
                    self.publishPartial(text)
                }
                if result.isFinal {
                    DispatchQueue.main.async { self.deliverAndDismiss() }
                }
            }
            if let error = error {
                let ns = error as NSError
                if ns.domain == "kAFAssistantErrorDomain"
                    && (ns.code == 203 || ns.code == 216 || ns.code == 1110) {
                    DispatchQueue.main.async { self.deliverAndDismiss() }
                } else {
                    DispatchQueue.main.async {
                        self.finish(withError: "Mic error \(ns.code)")
                    }
                }
            }
        }

        let inputNode = audioEngine.inputNode
        let format = inputNode.outputFormat(forBus: 0)
        guard format.sampleRate > 0 else {
            finish(withError: "Mic format invalid")
            return
        }
        inputNode.removeTap(onBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            self?.request?.append(buffer)
        }

        audioEngine.prepare()
        do {
            try audioEngine.start()
            didStartRecognition = true
        } catch {
            finish(withError: "Engine.start: \(error.localizedDescription)")
        }
    }

    private func publishPartial(_ text: String) {
        guard let d = UserDefaults(suiteName: Self.appGroupID) else { return }
        d.set(text, forKey: Self.kPartial)
        // Best-effort wake-up signal so the keyboard repaints immediately
        // when the user is already back in the host app. The keyboard also
        // polls on a timer as a safety net.
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(Self.kPartialName), nil, nil, true)
    }

    // MARK: - Lifecycle

    @objc private func appDidEnterBackground() {
        // Mark coachmark as seen the first time the user swipes away —
        // we don't want to show it again on subsequent voice triggers.
        UserDefaults.standard.set(true, forKey: Self.kCoachShown)
        // Do NOT tear down the recognition task here. The session is
        // `.playAndRecord` and the bundle declares `audio` background
        // mode, so iOS keeps the engine running. The recognizer continues
        // to deliver partials; we keep streaming them to the App Group
        // until the keyboard signals stop / cancel or the recognizer
        // hits its own timeout.
    }

    @objc private func cancelTapped() {
        cancelEverything(deleteFlags: true)
        dismissSelf()
    }

    // MARK: - Darwin control channel from the keyboard

    /// The keyboard signals stop / cancel by writing the App Group flag
    /// AND posting `kControlName`. We observe the notification so we don't
    /// have to poll while backgrounded.
    private func registerDarwinControl() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        let observer = Unmanaged.passUnretained(self).toOpaque()
        self.darwinObserver = observer
        CFNotificationCenterAddObserver(
            center, observer,
            { _, ptr, _, _, _ in
                guard let ptr = ptr else { return }
                let me = Unmanaged<VoiceRecordingViewController>
                    .fromOpaque(ptr).takeUnretainedValue()
                DispatchQueue.main.async { me.handleControlSignal() }
            },
            Self.kControlName, nil, .deliverImmediately)
    }

    private func unregisterDarwinControl() {
        guard let observer = darwinObserver else { return }
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        CFNotificationCenterRemoveObserver(
            center, observer, CFNotificationName(Self.kControlName), nil)
        darwinObserver = nil
    }

    private func handleControlSignal() {
        guard let d = UserDefaults(suiteName: Self.appGroupID) else { return }
        if d.bool(forKey: Self.kCancel) {
            d.removeObject(forKey: Self.kCancel)
            d.removeObject(forKey: Self.kStop)
            cancelEverything(deleteFlags: true)
            dismissSelf()
            return
        }
        if d.bool(forKey: Self.kStop) {
            d.removeObject(forKey: Self.kStop)
            // End audio so the recognizer flushes its final hypothesis.
            // `deliverAndDismiss` will be triggered by the recognizer's
            // `isFinal` callback or the 0.4s safety timer below.
            request?.endAudio()
            tearDownAudio()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
                self?.deliverAndDismiss()
            }
        }
    }

    // MARK: - Teardown

    private func deliverAndDismiss() {
        guard !didDeliver else { return }
        didDeliver = true
        if let d = UserDefaults(suiteName: Self.appGroupID) {
            let trimmed = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { d.set(trimmed, forKey: Self.kTranscript) }
            d.removeObject(forKey: Self.kError)
            d.removeObject(forKey: Self.kPartial)
        }
        postFinishNotification()
        dismissSelf()
    }

    private func finish(withError message: String) {
        guard !didDeliver else { return }
        didDeliver = true
        tearDownAudio()
        if let d = UserDefaults(suiteName: Self.appGroupID) {
            d.set(message, forKey: Self.kError)
            d.removeObject(forKey: Self.kTranscript)
            d.removeObject(forKey: Self.kPartial)
        }
        postFinishNotification()
        dismissSelf()
    }

    /// Cancel without delivering. Used for the X button + cancel signal.
    private func cancelEverything(deleteFlags: Bool) {
        guard !didDeliver else { return }
        didDeliver = true
        task?.cancel()
        tearDownAudio()
        if deleteFlags, let d = UserDefaults(suiteName: Self.appGroupID) {
            d.removeObject(forKey: Self.kTranscript)
            d.removeObject(forKey: Self.kPartial)
            d.removeObject(forKey: Self.kError)
        }
        postFinishNotification()
    }

    private func tearDownAudio() {
        if audioEngine.isRunning {
            audioEngine.inputNode.removeTap(onBus: 0)
            audioEngine.stop()
        }
        try? AVAudioSession.sharedInstance().setActive(
            false, options: .notifyOthersOnDeactivation)
        request = nil
        task = nil
    }

    private func postFinishNotification() {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(Self.kFinishedName), nil, nil, true)
    }

    private func dismissSelf() {
        if presentingViewController != nil {
            dismiss(animated: true)
        }
    }
}

// MARK: - Coachmark illustration

/// Tiny Core Graphics rendering of the Wispr-style phone-with-mic illustration
/// — a rounded phone outline with a waveform centered inside and a gesture
/// indicator dot near the home bar.
private final class CoachIllustrationView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(white: 0.94, alpha: 1.0)
        layer.cornerRadius = 24
    }
    required init?(coder: NSCoder) { fatalError() }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }

        // Phone outline.
        let phoneRect = rect.insetBy(dx: 32, dy: 16)
        let phone = UIBezierPath(roundedRect: phoneRect, cornerRadius: 24)
        ctx.setStrokeColor(UIColor.black.cgColor)
        ctx.setLineWidth(3)
        ctx.addPath(phone.cgPath)
        ctx.strokePath()

        // Top "send" pill mimicking iOS chat composer.
        let pill = UIBezierPath(
            roundedRect: CGRect(x: phoneRect.minX + 12, y: phoneRect.minY + 14,
                                width: phoneRect.width - 36, height: 16),
            cornerRadius: 8)
        ctx.setFillColor(UIColor(white: 0.85, alpha: 1.0).cgColor)
        ctx.addPath(pill.cgPath); ctx.fillPath()
        // Up-arrow circle.
        let arrow = UIBezierPath(ovalIn:
            CGRect(x: phoneRect.maxX - 30, y: phoneRect.minY + 12, width: 20, height: 20))
        ctx.setFillColor(UIColor(red: 0.30, green: 0.55, blue: 1.0, alpha: 1.0).cgColor)
        ctx.addPath(arrow.cgPath); ctx.fillPath()

        // Check mark in dark circle (mirrors Wispr's ✓ in the listening UI).
        let check = UIBezierPath(ovalIn:
            CGRect(x: phoneRect.maxX - 30, y: phoneRect.minY + 50, width: 22, height: 22))
        ctx.setFillColor(UIColor(white: 0.18, alpha: 1.0).cgColor)
        ctx.addPath(check.cgPath); ctx.fillPath()

        // Waveform bars in the middle.
        let waveY = phoneRect.midY + 4
        let bars: [CGFloat] = [10, 22, 14, 28, 16, 24, 12]
        let barW: CGFloat = 4
        let gap: CGFloat = 6
        let totalW = CGFloat(bars.count) * barW + CGFloat(bars.count - 1) * gap
        var x = phoneRect.midX - totalW / 2
        ctx.setFillColor(UIColor.black.cgColor)
        for h in bars {
            let p = UIBezierPath(roundedRect:
                CGRect(x: x, y: waveY - h/2, width: barW, height: h),
                cornerRadius: barW / 2)
            ctx.addPath(p.cgPath); ctx.fillPath()
            x += barW + gap
        }

        // "Listening / iPad Microphone" caption.
        let listening = "Listening" as NSString
        let mic = "iPad Microphone" as NSString
        let pSmall: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 10, weight: .medium),
            .foregroundColor: UIColor.black,
        ]
        let pTiny: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 9),
            .foregroundColor: UIColor(white: 0.4, alpha: 1.0),
        ]
        let listSize = listening.size(withAttributes: pSmall)
        listening.draw(at: CGPoint(x: phoneRect.midX - listSize.width/2,
                                   y: waveY + 22),
                       withAttributes: pSmall)
        let micSize = mic.size(withAttributes: pTiny)
        mic.draw(at: CGPoint(x: phoneRect.midX - micSize.width/2,
                             y: waveY + 38),
                 withAttributes: pTiny)

        // Home-indicator bar at bottom.
        let bar = UIBezierPath(roundedRect:
            CGRect(x: phoneRect.midX - 40, y: phoneRect.maxY - 12,
                   width: 80, height: 4),
            cornerRadius: 2)
        ctx.setFillColor(UIColor.black.cgColor)
        ctx.addPath(bar.cgPath); ctx.fillPath()

        // Purple "swipe right" indicator dot near the home bar.
        let dot = UIBezierPath(ovalIn:
            CGRect(x: phoneRect.maxX - 24, y: phoneRect.maxY - 24, width: 22, height: 22))
        ctx.setFillColor(UIColor(red: 0.69, green: 0.59, blue: 0.86, alpha: 1.0).cgColor)
        ctx.addPath(dot.cgPath); ctx.fillPath()
    }
}
