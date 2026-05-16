import Foundation
#if os(iOS)
import UIKit

/// Wispr-Flow-style voice input. The keyboard extension can't reliably hold
/// the mic (audio session fights, intermittent `engine.start` failures inside
/// the appex sandbox), so we hand recording off to the host app:
///
///   1. User taps the mic key.
///   2. `start(sink:)` clears any stale transcript in the shared App Group and
///      asks the system to open `turtlekeyboard://voice` — iOS backgrounds the
///      current app and foregrounds Turtle.
///   3. The Turtle host app's URL handler presents a recording sheet, runs
///      `SFSpeechRecognizer` + `AVAudioEngine` (full app entitlements, no
///      sandbox restriction), and on stop writes the transcript to the shared
///      `UserDefaults(suiteName: AppGroup.id)` and posts a Darwin notification.
///   4. The user swipes back to the original app. The keyboard re-appears,
///      this controller reads the pending transcript and delivers it via
///      `Sink.onFinal`. `KeyboardViewController` inserts it as if it had been
///      typed.
///
/// No mic / speech permission prompts ever happen inside the extension —
/// that's all in the host app, which has the entitlements and Info.plist
/// strings to do it correctly. `Allow Full Access` is still required because
/// `extensionContext.open(_:completionHandler:)` is gated on it.
extension VoiceInputController.Sink {
    /// Backwards-compatible default — older sinks treat info messages
    /// like errors. Concrete sinks should override to keep listening
    /// state armed.
    func onInfo(_ userVisibleMessage: String) { onError(userVisibleMessage) }
}

final class VoiceInputController {

    /// Kept identical to the previous version so `KeyboardViewController`
    /// doesn't need to change. `onPartial` is no longer fired (the host app
    /// owns the recognizer); only `onFinal` / `onError` arrive.
    protocol Sink: AnyObject {
        func onPartial(_ text: String)
        func onFinal(_ text: String)
        func onError(_ userVisibleMessage: String)
        func onListeningStarted()
        func onListeningStopped()
        /// Non-fatal status update. Used for the "swipe to Turtle app"
        /// coachmark — the listening state stays armed because the user
        /// is mid-flow, not in an error path. Default impl forwards to
        /// `onError` so older Sinks still see the message.
        func onInfo(_ userVisibleMessage: String)
    }

    // Shared with the host app. Must match `group.com.turtlekeyboard.split`
    // declared in both `.entitlements` files.
    private static let appGroupID = "group.com.turtlekeyboard.split"
    private static let kPartial    = "voice.partialTranscript"
    private static let kTranscript = "voice.pendingTranscript"
    private static let kError      = "voice.pendingError"
    private static let kStartedAt  = "voice.startedAt"
    private static let kStop       = "voice.stopRequested"
    private static let kCancel     = "voice.cancelRequested"
    /// Rendezvous flag the host app reads in `applicationDidBecomeActive`
    /// — when set & recent, the host presents the recording sheet.
    private static let kVoiceRequested = "voice.requested"
    /// Darwin notification names (kept narrowly scoped to this feature).
    /// `finishedName` — host wrote final transcript / error / cancel.
    /// `partialName`  — host pushed a new partial.
    /// `controlName`  — keyboard signals stop / cancel to the host.
    private static let finishedName: CFString = "com.turtlekeyboard.voice.didFinish" as CFString
    private static let partialName:  CFString = "com.turtlekeyboard.voice.didPartial" as CFString
    private static let controlName:  CFString = "com.turtlekeyboard.voice.control"    as CFString
    /// Deep link the host app handles when programmatic open is allowed.
    private static let launchURL   = URL(string: "turtlekeyboard://voice")!

    private(set) var isListening: Bool = false
    private weak var activeSink: Sink?
    /// Weak ref to the input view controller so we can call `open(_:)`.
    private weak var hostInputVC: UIInputViewController?
    private var finishedObserver: UnsafeMutableRawPointer?
    private var partialObserver:  UnsafeMutableRawPointer?
    /// Repaint timer — Darwin notifications can be coalesced under load,
    /// so we also poll the App Group every 200 ms while listening.
    private var pollTimer: Timer?

    init() {
        registerDarwinObservers()
    }

    deinit {
        unregisterDarwinObservers()
        pollTimer?.invalidate()
    }

    // MARK: - Public API — API-compatible with the old controller

    /// No-op stubs preserved for source compatibility with the old class.
    /// Permission lives in the host app now.
    static var hasPermissions: Bool { true }
    static func requestPermissions(_ completion: @escaping (Bool) -> Void) {
        DispatchQueue.main.async { completion(true) }
    }

    /// `KeyboardViewController` passes itself as both `sink` and `host` — it
    /// is a `UIInputViewController`, so the second arg comes for free in the
    /// existing call site. (If your KVC call still passes only `sink:`, see
    /// the convenience overload below.)
    func start(sink: Sink, host: UIInputViewController) {
        guard !isListening else { return }
        guard let defaults = UserDefaults(suiteName: Self.appGroupID) else {
            sink.onError("App Group misconfigured")
            return
        }
        let now = Date().timeIntervalSince1970
        // Clear stale state from a prior round.
        defaults.removeObject(forKey: Self.kTranscript)
        defaults.removeObject(forKey: Self.kPartial)
        defaults.removeObject(forKey: Self.kError)
        defaults.removeObject(forKey: Self.kStop)
        defaults.removeObject(forKey: Self.kCancel)
        defaults.set(now, forKey: Self.kStartedAt)
        defaults.set(now, forKey: Self.kVoiceRequested)

        self.activeSink = sink
        self.hostInputVC = host
        self.isListening = true
        sink.onListeningStarted()
        startPollTimer()

        // Best-effort programmatic launch. On iOS 18+ this returns false
        // for keyboard extensions, but the rendezvous flag means the host
        // will still start recording when the user swipes to it manually.
        openHostApp(from: host) { [weak self] opened in
            guard let self = self, self.isListening else { return }
            DispatchQueue.main.async {
                if !opened {
                    sink.onInfo("Swipe up to Turtle app to start dictation")
                }
            }
        }
    }

    /// User tapped ✓ in the keyboard's listening overlay. Tell the host
    /// to flush its final hypothesis.
    func requestStop() {
        guard isListening else { return }
        if let d = UserDefaults(suiteName: Self.appGroupID) {
            d.set(true, forKey: Self.kStop)
        }
        postControlSignal()
    }

    /// Convenience overload for the existing `voiceController.start(sink: self)`
    /// call site. Falls back to walking the responder chain to find the
    /// input view controller.
    func start(sink: Sink) {
        if let host = (sink as? UIInputViewController) ?? findInputVC(from: sink) {
            start(sink: sink, host: host)
        } else {
            sink.onError("Voice unavailable: no host controller")
        }
    }

    /// User tapped mic again — treat as ✓ in the listening overlay.
    func stop() { requestStop() }

    /// User tapped X in the listening overlay (or hit cancel some other way).
    /// Tell the host to throw away whatever it's captured.
    func cancel() {
        guard isListening else { return }
        if let d = UserDefaults(suiteName: Self.appGroupID) {
            d.set(true, forKey: Self.kCancel)
            d.removeObject(forKey: Self.kPartial)
            d.removeObject(forKey: Self.kTranscript)
        }
        postControlSignal()
        // Drop local state immediately so the UI snaps back; the host
        // will see the cancel flag on its next Darwin notification and
        // tear down.
        stopPollTimer()
        let sink = activeSink
        isListening = false
        activeSink = nil
        hostInputVC = nil
        DispatchQueue.main.async { sink?.onListeningStopped() }
    }

    private func postControlSignal() {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(Self.controlName), nil, nil, true)
    }

    func destroy() {
        cancel()
        unregisterDarwinObservers()
    }

    /// Drains all known App Group state and delivers it to the sink in the
    /// right order: error > final > partial. Called from
    /// `viewDidAppear`, the Darwin observer, and the poll timer.
    func consumePendingTranscript() {
        guard let d = UserDefaults(suiteName: Self.appGroupID) else { return }
        if let err = d.string(forKey: Self.kError) {
            d.removeObject(forKey: Self.kError)
            d.removeObject(forKey: Self.kPartial)
            deliverError(err)
            return
        }
        if let text = d.string(forKey: Self.kTranscript) {
            d.removeObject(forKey: Self.kTranscript)
            d.removeObject(forKey: Self.kPartial)
            deliverFinal(text)
            return
        }
        if isListening, let partial = d.string(forKey: Self.kPartial) {
            deliverPartial(partial)
        }
    }

    private var lastPartialDelivered: String?

    private func deliverPartial(_ text: String) {
        guard text != lastPartialDelivered else { return }
        lastPartialDelivered = text
        let sink = activeSink
        DispatchQueue.main.async { sink?.onPartial(text) }
    }

    // MARK: - Internal

    private func openHostApp(from host: UIInputViewController,
                             completion: @escaping (Bool) -> Void) {
        // Two prerequisites that were missing earlier and caused this to
        // silently fail:
        //   1. `LSApplicationQueriesSchemes` in the extension's Info.plist
        //      must list `turtlekeyboard`. Without it iOS denies the open
        //      regardless of Full Access.
        //   2. Use the typed `extensionContext.open(_:completionHandler:)`
        //      — it's the only API Apple actually honours from a keyboard
        //      extension. The `openURL:` selector-walk works on older iOS
        //      and is kept here as a fallback.
        let url = Self.launchURL

        if let ctx = host.extensionContext {
            ctx.open(url) { [weak self] success in
                if success {
                    DispatchQueue.main.async { completion(true) }
                    return
                }
                // System refused. Try the responder-chain walk before
                // giving up — some older iOS keyboards still allow it.
                DispatchQueue.main.async {
                    self?.openViaResponderChain(from: host, url: url,
                                                completion: completion)
                }
            }
            return
        }
        openViaResponderChain(from: host, url: url, completion: completion)
    }

    private func openViaResponderChain(from host: UIInputViewController,
                                       url: URL,
                                       completion: @escaping (Bool) -> Void) {
        let openSel = NSSelectorFromString("openURL:")
        // Walk from view → window → application, which is the chain order
        // most likely to surface UIApplication on older iOS.
        let starts: [UIResponder?] = [
            host.viewIfLoaded?.window,
            host.viewIfLoaded,
            host,
        ]
        for start in starts {
            var responder: UIResponder? = start
            while let r = responder {
                if let app = r as? UIApplication, app.responds(to: openSel) {
                    let result = app.perform(openSel, with: url)
                    completion(result != nil)
                    return
                }
                responder = r.next
            }
        }
        completion(false)
    }

    private func findInputVC(from any: AnyObject) -> UIInputViewController? {
        var r: UIResponder? = any as? UIResponder
        while let cur = r {
            if let ivc = cur as? UIInputViewController { return ivc }
            r = cur.next
        }
        return nil
    }

    private func deliverFinal(_ text: String) {
        guard isListening else { return }
        stopPollTimer()
        isListening = false
        let sink = activeSink
        activeSink = nil
        hostInputVC = nil
        lastPartialDelivered = nil
        DispatchQueue.main.async {
            sink?.onFinal(text)
            sink?.onListeningStopped()
        }
    }

    private func deliverError(_ message: String) {
        guard isListening else { return }
        stopPollTimer()
        isListening = false
        let sink = activeSink
        activeSink = nil
        hostInputVC = nil
        lastPartialDelivered = nil
        DispatchQueue.main.async {
            sink?.onError(message)
            sink?.onListeningStopped()
        }
    }

    // MARK: - Polling

    private func startPollTimer() {
        stopPollTimer()
        // Run on main RunLoop in common modes so it ticks during touch
        // tracking (the user may still be holding the keyboard area).
        let t = Timer(timeInterval: 0.2, repeats: true) { [weak self] _ in
            self?.consumePendingTranscript()
        }
        RunLoop.main.add(t, forMode: .common)
        pollTimer = t
    }

    private func stopPollTimer() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    // MARK: - Darwin notification bridge

    private func registerDarwinObservers() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        let observer = Unmanaged.passUnretained(self).toOpaque()
        self.finishedObserver = observer
        self.partialObserver = observer
        let callback: CFNotificationCallback = { _, ptr, _, _, _ in
            guard let ptr = ptr else { return }
            let me = Unmanaged<VoiceInputController>
                .fromOpaque(ptr).takeUnretainedValue()
            DispatchQueue.main.async { me.consumePendingTranscript() }
        }
        CFNotificationCenterAddObserver(
            center, observer, callback,
            Self.finishedName, nil, .deliverImmediately)
        CFNotificationCenterAddObserver(
            center, observer, callback,
            Self.partialName, nil, .deliverImmediately)
    }

    private func unregisterDarwinObservers() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        if let observer = finishedObserver {
            CFNotificationCenterRemoveObserver(
                center, observer, CFNotificationName(Self.finishedName), nil)
        }
        if let observer = partialObserver {
            CFNotificationCenterRemoveObserver(
                center, observer, CFNotificationName(Self.partialName), nil)
        }
        finishedObserver = nil
        partialObserver = nil
    }
}
#endif
