import UIKit
import AVFoundation
import Speech

/// Headless voice-recording engine that lives in the host app from launch
/// to terminate. The keyboard extension tells this object what to do via
/// App-Group flags + Darwin notifications — `AppDelegate` wires them up.
///
/// Why this isn't a `UIViewController`:
///   Apple disallows starting `AVAudioEngine` from a backgrounded app
///   *unless* the audio session was already active. We solve that by
///   pre-warming `.playAndRecord` the moment the host app becomes active
///   (the user opened Turtle at least once this session), and by holding
///   a `UIBackgroundTaskIdentifier` so iOS keeps us alive a bit longer
///   when the user swipes back to their original app. As long as Turtle
///   has been opened recently, the keyboard can fire a Darwin
///   notification and recording starts immediately — no swipe needed.
///   When the host has been killed / suspended past the iOS grace window,
///   we fall back to the swipe-to-app coachmark flow.
final class VoiceSessionManager {

    static let shared = VoiceSessionManager()

    // Keep these strings in sync with VoiceInputController + the recording VC.
    private static let appGroupID  = "group.com.samarth.turtlekeyboard.split"
    private static let kRequested  = "voice.requested"
    private static let kPartial    = "voice.partialTranscript"
    private static let kTranscript = "voice.pendingTranscript"
    private static let kError      = "voice.pendingError"
    private static let kStop       = "voice.stopRequested"
    private static let kCancel     = "voice.cancelRequested"
    /// Heartbeat the keyboard checks to decide whether to take the
    /// "host is alive, fire Darwin" fast path or the "show swipe
    /// coachmark" slow path.
    private static let kHostAliveAt = "voice.hostAliveAt"

    private static let kFinishedName: CFString = "com.samarth.turtlekeyboard.voice.didFinish"   as CFString
    private static let kPartialName:  CFString = "com.samarth.turtlekeyboard.voice.didPartial"  as CFString
    private static let kControlName:  CFString = "com.samarth.turtlekeyboard.voice.control"    as CFString
    private static let kRequestedName: CFString = "com.samarth.turtlekeyboard.voice.requested" as CFString

    private let audioEngine = AVAudioEngine()
    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    // Silent-playback keep-alive: AVAudioEngine + a player node looping a
    // zero buffer. iOS only lets backgrounded apps *continue* an audio
    // session, not start one — so we never let the session deactivate.
    // Plays inaudible audio so the session counts as "in use".
    private var keepaliveNode: AVAudioPlayerNode?
    private var keepaliveBuffer: AVAudioPCMBuffer?
    private var engineRunning = false

    private var requestedObserver:  UnsafeMutableRawPointer?
    private var controlObserver:    UnsafeMutableRawPointer?
    private var bgTaskID: UIBackgroundTaskIdentifier = .invalid

    private var transcript = ""
    private(set) var isRecording = false
    private var sessionActive = false

    /// Called once from AppDelegate after launch.
    func register() {
        recognizer = SFSpeechRecognizer(locale: .current) ?? SFSpeechRecognizer()
        registerDarwinObservers()
        NotificationCenter.default.addObserver(
            self, selector: #selector(appDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(appWillTerminate),
            name: UIApplication.willTerminateNotification, object: nil)
    }

    // MARK: - Lifecycle hooks

    @objc private func appDidBecomeActive() {
        writeHeartbeat()
        // Pre-warm the audio session so a Darwin-triggered start can run
        // even when we're back in the background a few seconds later.
        primeAudioSession()
        // Eagerly ask for permissions on first foreground so they're already
        // granted by the time the user actually fires the mic.
        ensureAuthorized { _ in }
    }

    @objc private func appDidEnterBackground() {
        // Keep the app alive for as long as iOS will allow once we go to
        // the background, so subsequent voice triggers don't have to
        // re-launch us cold.
        extendBackgroundLifetime()
    }

    @objc private func appWillTerminate() {
        unregisterDarwinObservers()
        clearHeartbeat()
    }

    // MARK: - Audio session pre-warm

    private func primeAudioSession() {
        let session = AVAudioSession.sharedInstance()
        if !sessionActive {
            do {
                // `.voiceChat` mode + `.mixWithOthers` keeps us coexisting
                // with whatever app the user is actively in (Slack, etc.).
                // No `.defaultToSpeaker` — that's a route override and isn't
                // needed for a silent keep-alive.
                try session.setCategory(.playAndRecord, mode: .voiceChat,
                                        options: [.allowBluetooth, .mixWithOthers])
                try session.setActive(true, options: .notifyOthersOnDeactivation)
                sessionActive = true
            } catch {
                sessionActive = false
                return
            }
        }
        startKeepaliveEngine()
    }

    /// Start `AVAudioEngine` with a silent-buffer player looping forever.
    /// The act of having audio I/O in flight keeps `AVAudioSession` active
    /// across backgrounding — without this, iOS deactivates the session
    /// the moment Slack foregrounds, and the Darwin-triggered recording
    /// fails with `insufficientPriority`.
    private func startKeepaliveEngine() {
        guard !engineRunning else { return }
        let session = AVAudioSession.sharedInstance()
        let sampleRate = session.sampleRate > 0 ? session.sampleRate : 44_100
        guard let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)
        else { return }

        let player = AVAudioPlayerNode()
        audioEngine.attach(player)
        audioEngine.connect(player, to: audioEngine.mainMixerNode, format: format)

        // A short zero-filled buffer looped forever. AVAudioPCMBuffer is
        // zero-initialised, so no manual fill needed.
        let frames = AVAudioFrameCount(sampleRate / 2)  // 0.5 s
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames)
        else { return }
        buffer.frameLength = frames

        // Touch the input node format too, so the engine negotiates
        // recording-capable hardware up front. (Without this iOS sometimes
        // configures the engine in playback-only mode and `installTap`
        // throws when recording starts.)
        _ = audioEngine.inputNode.outputFormat(forBus: 0)

        audioEngine.prepare()
        do {
            try audioEngine.start()
            player.scheduleBuffer(buffer, at: nil, options: [.loops], completionHandler: nil)
            player.play()
            keepaliveNode = player
            keepaliveBuffer = buffer
            engineRunning = true
        } catch {
            // Keepalive failed — fall back to per-request session activation.
            engineRunning = false
        }
    }

    private func extendBackgroundLifetime() {
        // Re-arm if previous task expired.
        if bgTaskID != .invalid {
            UIApplication.shared.endBackgroundTask(bgTaskID)
            bgTaskID = .invalid
        }
        bgTaskID = UIApplication.shared.beginBackgroundTask(
            withName: "TurtleVoiceWarm") { [weak self] in
            guard let self = self else { return }
            UIApplication.shared.endBackgroundTask(self.bgTaskID)
            self.bgTaskID = .invalid
        }
    }

    // MARK: - Heartbeat

    /// Keyboard reads this. Recent timestamp → fast path (Darwin trigger).
    func writeHeartbeat() {
        guard let d = UserDefaults(suiteName: Self.appGroupID) else { return }
        d.set(Date().timeIntervalSince1970, forKey: Self.kHostAliveAt)
    }

    private func clearHeartbeat() {
        guard let d = UserDefaults(suiteName: Self.appGroupID) else { return }
        d.removeObject(forKey: Self.kHostAliveAt)
    }

    // MARK: - Public start hook (called from AppDelegate URL / activate paths)

    /// Force a fresh recording cycle. Used by the swipe-back fallback.
    func startIfRequested() {
        guard let d = UserDefaults(suiteName: Self.appGroupID) else { return }
        let requestedAt = d.double(forKey: Self.kRequested)
        guard requestedAt > 0 else { return }
        d.removeObject(forKey: Self.kRequested)
        let age = Date().timeIntervalSince1970 - requestedAt
        guard age <= 30 else { return }
        startRecording()
    }

    // MARK: - Permissions

    private func ensureAuthorized(_ completion: @escaping (Bool) -> Void) {
        SFSpeechRecognizer.requestAuthorization { speechStatus in
            let speechOK = (speechStatus == .authorized)
            let micCb: (Bool) -> Void = { micOK in
                DispatchQueue.main.async { completion(speechOK && micOK) }
            }
            if #available(iOS 17.0, *) {
                AVAudioApplication.requestRecordPermission(completionHandler: micCb)
            } else {
                AVAudioSession.sharedInstance().requestRecordPermission(micCb)
            }
        }
    }

    // MARK: - Recording

    private func startRecording() {
        guard !isRecording else { return }
        ensureAuthorized { [weak self] ok in
            guard let self = self else { return }
            guard ok else {
                self.publishError("Microphone or speech permission denied")
                return
            }
            self.actuallyStart()
        }
    }

    private func actuallyStart() {
        guard let recognizer = recognizer, recognizer.isAvailable else {
            publishError("Speech recognition unavailable")
            return
        }
        // Make sure the keep-alive is up. If it isn't (failed earlier or
        // never started because user never foregrounded), retry now.
        primeAudioSession()
        if !engineRunning {
            publishError("Open Turtle once to enable voice")
            return
        }
        extendBackgroundLifetime()

        transcript = ""
        clearAppGroupTranscripts()

        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        request = req

        task = recognizer.recognitionTask(with: req) { [weak self] result, error in
            guard let self = self else { return }
            if let result = result {
                let text = result.bestTranscription.formattedString
                self.transcript = text
                self.publishPartial(text)
                if result.isFinal { self.finalizeAndPublish() }
            }
            if let error = error {
                let ns = error as NSError
                if ns.domain == "kAFAssistantErrorDomain"
                    && (ns.code == 203 || ns.code == 216 || ns.code == 1110) {
                    self.finalizeAndPublish()
                } else {
                    self.publishError("Mic error \(ns.code)")
                }
            }
        }

        // Engine is already running for keep-alive — DO NOT reset/restart
        // it (that drops the session, which is exactly what we worked
        // around). Just attach the input tap to the live engine.
        let input = audioEngine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0 else {
            publishError("Mic format invalid")
            return
        }
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buf, _ in
            self?.request?.append(buf)
        }
        isRecording = true
    }

    private func stopRecording() {
        guard isRecording else { return }
        request?.endAudio()
        // Recognizer will emit its final hypothesis through the callback;
        // arm a safety timer in case the final never lands.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            self?.finalizeAndPublish()
        }
    }

    private func cancelRecording() {
        guard isRecording else {
            clearAppGroupTranscripts()
            return
        }
        task?.cancel()
        tearDownEngine()
        clearAppGroupTranscripts()
        isRecording = false
        // Tell the keyboard we've torn down so its overlay closes.
        postDarwin(Self.kFinishedName)
    }

    private func finalizeAndPublish() {
        guard isRecording else { return }
        isRecording = false
        tearDownEngine()
        if let d = UserDefaults(suiteName: Self.appGroupID) {
            let trimmed = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { d.set(trimmed, forKey: Self.kTranscript) }
            d.removeObject(forKey: Self.kPartial)
            d.removeObject(forKey: Self.kError)
        }
        postDarwin(Self.kFinishedName)
    }

    private func tearDownEngine() {
        // Only pull the input tap and recognition request. The engine
        // itself, the silent keep-alive player, and the audio session all
        // stay running so the next mic tap can record from the background
        // without having to re-prime anything.
        if audioEngine.isRunning {
            audioEngine.inputNode.removeTap(onBus: 0)
        }
        request = nil
        task = nil
    }

    // MARK: - App-Group writes

    private func publishPartial(_ text: String) {
        guard let d = UserDefaults(suiteName: Self.appGroupID) else { return }
        d.set(text, forKey: Self.kPartial)
        postDarwin(Self.kPartialName)
    }

    private func publishError(_ message: String) {
        // We may be reporting before recording was fully up — only tear
        // down if the engine actually started.
        if isRecording {
            tearDownEngine()
            isRecording = false
        }
        if let d = UserDefaults(suiteName: Self.appGroupID) {
            d.set(message, forKey: Self.kError)
            d.removeObject(forKey: Self.kPartial)
            d.removeObject(forKey: Self.kTranscript)
        }
        postDarwin(Self.kFinishedName)
    }

    private func clearAppGroupTranscripts() {
        guard let d = UserDefaults(suiteName: Self.appGroupID) else { return }
        d.removeObject(forKey: Self.kPartial)
        d.removeObject(forKey: Self.kTranscript)
        d.removeObject(forKey: Self.kError)
    }

    private func postDarwin(_ name: CFString) {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(name), nil, nil, true)
    }

    // MARK: - Darwin observers (keyboard → host)

    private func registerDarwinObservers() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        let ptr = Unmanaged.passUnretained(self).toOpaque()
        requestedObserver = ptr
        controlObserver = ptr

        let requestedCallback: CFNotificationCallback = { _, p, _, _, _ in
            guard let p = p else { return }
            let me = Unmanaged<VoiceSessionManager>.fromOpaque(p).takeUnretainedValue()
            DispatchQueue.main.async { me.handleRequestedNotification() }
        }
        let controlCallback: CFNotificationCallback = { _, p, _, _, _ in
            guard let p = p else { return }
            let me = Unmanaged<VoiceSessionManager>.fromOpaque(p).takeUnretainedValue()
            DispatchQueue.main.async { me.handleControlNotification() }
        }
        CFNotificationCenterAddObserver(
            center, ptr, requestedCallback,
            Self.kRequestedName, nil, .deliverImmediately)
        CFNotificationCenterAddObserver(
            center, ptr, controlCallback,
            Self.kControlName, nil, .deliverImmediately)
    }

    private func unregisterDarwinObservers() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        if let ptr = requestedObserver {
            CFNotificationCenterRemoveObserver(
                center, ptr, CFNotificationName(Self.kRequestedName), nil)
        }
        if let ptr = controlObserver {
            CFNotificationCenterRemoveObserver(
                center, ptr, CFNotificationName(Self.kControlName), nil)
        }
        requestedObserver = nil
        controlObserver = nil
    }

    private func handleRequestedNotification() {
        writeHeartbeat()
        // Always clear the rendezvous flag here — we're handling it.
        if let d = UserDefaults(suiteName: Self.appGroupID) {
            d.removeObject(forKey: Self.kRequested)
        }
        // If we're already recording for some reason, ignore.
        guard !isRecording else { return }
        // Extend life now in case we get suspended mid-record.
        extendBackgroundLifetime()
        startRecording()
    }

    private func handleControlNotification() {
        guard let d = UserDefaults(suiteName: Self.appGroupID) else { return }
        if d.bool(forKey: Self.kCancel) {
            d.removeObject(forKey: Self.kCancel)
            d.removeObject(forKey: Self.kStop)
            cancelRecording()
            return
        }
        if d.bool(forKey: Self.kStop) {
            d.removeObject(forKey: Self.kStop)
            stopRecording()
        }
    }
}
