import Foundation
#if os(iOS)
import AVFoundation
import Speech

/// iOS port of Android's `VoiceInputController`. Wraps `SFSpeechRecognizer` +
/// `AVAudioEngine` so the IME can call `toggle(sink:)` from the mic key without
/// caring about lifecycle. Streams partial transcripts via `Sink.onPartial` for
/// live banner updates and emits the locked-in transcript via `onFinal` on
/// stop, silence, or error.
///
/// Permissions: `NSMicrophoneUsageDescription` + `NSSpeechRecognitionUsageDescription`
/// in **both** the host app and the keyboard extension Info.plist. The keyboard
/// also requires the user to enable Allow Full Access (`RequestsOpenAccess = true`).
final class VoiceInputController {

    protocol Sink: AnyObject {
        /// Called repeatedly with the latest in-flight transcript guess.
        func onPartial(_ text: String)
        /// Called once when recognition locks in. `text` may be empty.
        func onFinal(_ text: String)
        /// Recognition could not start or aborted with a fatal error.
        func onError(_ userVisibleMessage: String)
        /// Lifecycle hooks for UI state (banner pulse etc.).
        func onListeningStarted()
        func onListeningStopped()
    }

    private let audioEngine = AVAudioEngine()
    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    private(set) var isListening: Bool = false
    private weak var activeSink: Sink?

    init(locale: Locale = .current) {
        self.recognizer = SFSpeechRecognizer(locale: locale) ?? SFSpeechRecognizer()
    }

    // MARK: - Permissions

    /// Mic + speech-recognition both authorized? Keyboard extensions can't
    /// trigger system permission prompts directly when Full Access is off, so
    /// callers should route the user to the host app for the first grant.
    static var hasPermissions: Bool {
        let mic: Bool
        if #available(iOS 17.0, *) {
            mic = AVAudioApplication.shared.recordPermission == .granted
        } else {
            mic = AVAudioSession.sharedInstance().recordPermission == .granted
        }
        return mic && SFSpeechRecognizer.authorizationStatus() == .authorized
    }

    /// Request both permissions. Safe to call from the host app; in the
    /// extension this is a no-op when Full Access is off.
    static func requestPermissions(_ completion: @escaping (Bool) -> Void) {
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

    // MARK: - Public API

    /// Tap-to-toggle. If already listening, stops and emits the final transcript.
    func toggle(sink: Sink) {
        if isListening { stop() } else { start(sink: sink) }
    }

    func start(sink: Sink) {
        guard !isListening else { return }

        guard Self.hasPermissions else {
            sink.onError("Microphone permission required")
            return
        }
        guard let recognizer = recognizer, recognizer.isAvailable else {
            sink.onError("Speech recognition not available")
            return
        }

        // Configure + activate the audio session BEFORE reading the input
        // node's format — the format reports 0 Hz / 0 channels until the
        // session is active, which makes installTap throw.
        //
        // Keyboard extensions share the host app's audio session, so the
        // host's existing config can reject ours. Try Apple's recommended
        // speech config first; if the host owns playback, retry with
        // `.playAndRecord` + `.mixWithOthers` so we coexist instead of
        // fighting for exclusive control.
        let session = AVAudioSession.sharedInstance()
        if let error = activateSession(session) {
            sink.onError("Audio session: \(error.localizedDescription)")
            return
        }

        // Reset clears any residual nodes/state from a prior session that
        // ended in an error path before teardown completed.
        audioEngine.reset()

        // Following Apple's SpeakToMe sample order exactly — this matters in
        // a keyboard extension where the audio path is fragile:
        //   1. build request (NO `requiresOnDeviceRecognition` — that flag
        //      makes the recognizer refuse to bind audio until the on-device
        //      language model is ready, which throws 561145187 at engine.start
        //      on devices/locales where the model isn't downloaded).
        //   2. start recognitionTask BEFORE installing the tap, so the
        //      recognizer is holding the audio path open when buffers arrive.
        //   3. install tap on the input node.
        //   4. prepare + start the engine last.
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        self.request = request

        var lastPartial = ""
        self.task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            guard let self = self else { return }
            if let result = result {
                let text = result.bestTranscription.formattedString
                if text != lastPartial {
                    lastPartial = text
                    DispatchQueue.main.async { self.activeSink?.onPartial(text) }
                }
                if result.isFinal { self.finish(withFinalText: text) }
            }
            if let error = error {
                let nsError = error as NSError
                if nsError.domain == "kAFAssistantErrorDomain"
                    && (nsError.code == 203 || nsError.code == 216 || nsError.code == 1110) {
                    self.finish(withFinalText: lastPartial)
                } else {
                    self.fail(with: Self.describe(error: nsError))
                }
            }
        }

        let inputNode = audioEngine.inputNode
        // Apple's sample uses `outputFormat(forBus: 0)` here — that returns
        // the node's *connection* format which the engine has agreed to
        // produce, vs. inputFormat which is the raw hardware format the
        // engine may still be negotiating.
        let format = inputNode.outputFormat(forBus: 0)
        guard format.sampleRate > 0 else {
            // Diagnostic dump so we know which session knob failed.
            sink.onError("Mic format invalid — \(diagnosticDump(session: session))")
            cancel()
            return
        }
        inputNode.removeTap(onBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            self?.request?.append(buffer)
        }

        audioEngine.prepare()
        // Re-activate the session immediately before engine.start. Inside a
        // keyboard extension the system can silently deactivate us between
        // setActive and any subsequent work — this closes the window. The
        // diagnostic in the field showed inputAvail=true / route=BuiltIn at
        // engine.start, meaning the session was set up correctly but had
        // been quietly dropped from this process.
        try? session.setActive(true, options: .notifyOthersOnDeactivation)
        do {
            try audioEngine.start()
        } catch {
            let dump = diagnosticDump(session: session)
            cancel()
            sink.onError("Engine.start \((error as NSError).code): \(dump)")
            return
        }

        self.activeSink = sink
        self.isListening = true

        DispatchQueue.main.async { sink.onListeningStarted() }
    }

    /// Stop capturing and let the recognizer flush its final hypothesis.
    /// `Sink.onFinal` (or `onError`) will fire shortly after.
    func stop() {
        guard isListening else { return }
        request?.endAudio()
        // Tear down the audio path now; the recognition task continues until
        // it emits its final result, which lands in the closure above.
        audioEngine.inputNode.removeTap(onBus: 0)
        audioEngine.stop()
    }

    /// Throw away whatever is in flight. No `onFinal` is delivered.
    func cancel() {
        guard isListening else { return }
        task?.cancel()
        audioEngine.inputNode.removeTap(onBus: 0)
        audioEngine.stop()
        teardown()
    }

    func destroy() {
        cancel()
        recognizer = nil
    }

    // MARK: - Internal

    private func finish(withFinalText text: String) {
        let sink = activeSink
        teardown()
        DispatchQueue.main.async {
            sink?.onFinal(text)
            sink?.onListeningStopped()
        }
    }

    private func fail(with message: String) {
        let sink = activeSink
        audioEngine.inputNode.removeTap(onBus: 0)
        audioEngine.stop()
        teardown()
        DispatchQueue.main.async {
            sink?.onError(message)
            sink?.onListeningStopped()
        }
    }

    /// Try a sequence of audio-session configurations and return nil on the
    /// first one that activates, or the last error if all fail. Order matters:
    /// the most speech-optimal config first, then progressively more lenient
    /// fallbacks for hosts that already hold an exclusive session.
    private func activateSession(_ session: AVAudioSession) -> Error? {
        let configs: [(AVAudioSession.Category, AVAudioSession.Mode, AVAudioSession.CategoryOptions)] = [
            (.record,        .measurement, []),
            (.record,        .default,     []),
            (.playAndRecord, .default,     [.mixWithOthers, .allowBluetooth]),
            (.playAndRecord, .default,     [.mixWithOthers, .defaultToSpeaker, .allowBluetooth]),
        ]
        var lastError: Error?
        for (cat, mode, opts) in configs {
            do {
                try session.setCategory(cat, mode: mode, options: opts)
                // Speech recognition wants 16 kHz mono; making it explicit
                // avoids AVAudioEngine negotiating a hardware rate that it
                // then can't bind inside the extension sandbox.
                try? session.setPreferredSampleRate(16_000)
                try? session.setPreferredIOBufferDuration(0.02)
                try session.setActive(true, options: .notifyOthersOnDeactivation)
                return nil
            } catch {
                lastError = error
                // Deactivate before trying the next config; some failures
                // leave the session half-attached.
                try? session.setActive(false, options: .notifyOthersOnDeactivation)
                continue
            }
        }
        return lastError
    }

    private func diagnosticDump(session: AVAudioSession) -> String {
        let cat = session.category.rawValue
        let mode = session.mode.rawValue
        let inputAvail = session.isInputAvailable
        let route = session.currentRoute.inputs.map { $0.portType.rawValue }.joined(separator: ",")
        return "cat=\(cat) mode=\(mode) inputAvail=\(inputAvail) route=[\(route)]"
    }

    private func teardown() {
        request = nil
        task = nil
        activeSink = nil
        isListening = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private static func describe(error: NSError) -> String {
        switch error.code {
        case 1: return "Mic permission missing"
        case 2: return "Speech recognition not authorized"
        case 102, 201: return "Recognizer unavailable"
        case 203: return "Didn't catch that"
        case 209, 216: return "No speech detected"
        default: return "Mic error \(error.code)"
        }
    }
}
#endif
