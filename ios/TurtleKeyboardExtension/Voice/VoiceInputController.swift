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

    // Shared with the host app. Must match `group.com.samarth.turtlekeyboard.split`
    // declared in both `.entitlements` files.
    private static let appGroupID = "group.com.samarth.turtlekeyboard.split"
    private static let kPartial    = "voice.partialTranscript"
    private static let kTranscript = "voice.pendingTranscript"
    private static let kError      = "voice.pendingError"
    private static let kStartedAt  = "voice.startedAt"
    private static let kStop       = "voice.stopRequested"
    private static let kCancel     = "voice.cancelRequested"
    /// Rendezvous flag the host app reads when it cold-launches or
    /// foregrounds. Once the host is alive in the background, recording
    /// is driven by the Darwin notification — no need to set this.
    private static let kVoiceRequested = "voice.requested"
    /// Heartbeat the host's `VoiceSessionManager` writes on activate +
    /// each Darwin trigger. Recent timestamp → take the fast path
    /// (no swipe). Stale / missing → host has been suspended, fall back
    /// to swipe + cold-launch.
    private static let kHostAliveAt   = "voice.hostAliveAt"
    private static let hostAliveTTL: TimeInterval = 60 * 30  // 30 minutes

    /// Darwin notification names (kept narrowly scoped to this feature).
    /// `finishedName`  — host wrote final transcript / error / cancel.
    /// `partialName`   — host pushed a new partial.
    /// `controlName`   — keyboard signals stop / cancel to the host.
    /// `requestedName` — keyboard tells the host to start recording.
    private static let finishedName:  CFString = "com.samarth.turtlekeyboard.voice.didFinish"  as CFString
    private static let partialName:   CFString = "com.samarth.turtlekeyboard.voice.didPartial" as CFString
    private static let controlName:   CFString = "com.samarth.turtlekeyboard.voice.control"    as CFString
    private static let requestedName: CFString = "com.samarth.turtlekeyboard.voice.requested"  as CFString
    /// Deep link used only when the host has been killed and we have to
    /// cold-launch it. The day-to-day path is the Darwin notification.
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
        // Always set the rendezvous flag — if the host has to cold-launch
        // it will pick this up via `applicationDidBecomeActive`.
        defaults.set(now, forKey: Self.kVoiceRequested)

        self.activeSink = sink
        self.hostInputVC = host
        self.isListening = true
        sink.onListeningStarted()
        startPollTimer()

        // Fire the Darwin "requested" signal. If the host is alive in the
        // background (recent heartbeat) it will start recording within
        // ~100 ms and the user never has to leave the current app.
        postRequestedSignal()

        if isHostAliveInBackground(defaults: defaults, now: now) {
            // Fast path: host is warm, expect partials shortly. Arm a
            // 1.5s watchdog — if neither a partial nor a fresh heartbeat
            // arrives, the host was killed after the last heartbeat
            // (e.g. force-quit) and we fall through to the toast path.
            armWatchdog(sink: sink, host: host, sentAt: now)
            return
        }

        // Host is dead (force-quit, never opened this session, or cleared
        // by iOS). Foreground it programmatically — `extensionContext.open`
        // to our own container app is permitted from a Full-Access
        // keyboard, and `micTapped` already gated on `hasFullAccess`. The
        // user lands on the swipe-back coachmark and dictation continues
        // from there. Only if iOS refuses the open do we fall back to a
        // banner asking the user to launch Turtle manually.
        promptUserToOpenHost(sink: sink)
    }

    private func promptUserToOpenHost(sink: Sink) {
        // Tear down listening state — recording can't start until the host
        // is foregrounded and the user has swiped back. The voice.requested
        // rendezvous flag stays in the App Group, so the coachmark fires on
        // the host's next foreground regardless of how it got there.
        stopPollTimer()
        isListening = false
        let host = hostInputVC
        activeSink = nil
        hostInputVC = nil
        DispatchQueue.main.async { sink.onListeningStopped() }

        // Foreground the host. On success the host app appears with the
        // swipe-back coachmark — the keyboard is no longer visible, so no
        // banner from this side is needed. Only on failure do we surface
        // a single-line prompt with the next step.
        guard let host = host else {
            DispatchQueue.main.async { sink.onInfo("Open Turtle to enable voice") }
            return
        }
        openHostApp(from: host) { success in
            guard !success else { return }
            DispatchQueue.main.async {
                sink.onInfo("Open Turtle to enable voice")
            }
        }
    }

    private func postRequestedSignal() {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(Self.requestedName), nil, nil, true)
    }

    private func isHostAliveInBackground(defaults: UserDefaults, now: TimeInterval) -> Bool {
        let alive = defaults.double(forKey: Self.kHostAliveAt)
        guard alive > 0 else { return false }
        return (now - alive) <= Self.hostAliveTTL
    }

    private var watchdog: Timer?
    private func armWatchdog(sink: Sink, host: UIInputViewController, sentAt: TimeInterval) {
        watchdog?.invalidate()
        watchdog = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: false) {
            [weak self] _ in
            guard let self = self, self.isListening else { return }
            // Two signals that the host is alive and just waiting for the
            // user to speak: a partial already came in, OR the host wrote
            // a fresher heartbeat than the one we read at start().
            if self.lastPartialDelivered != nil { return }
            if let d = UserDefaults(suiteName: Self.appGroupID) {
                let freshHeartbeat = d.double(forKey: Self.kHostAliveAt)
                if freshHeartbeat > sentAt { return }
            }
            // Heartbeat was stale (host force-quit between heartbeats).
            // Same UX as the never-alive path: ask the user to open it.
            self.promptUserToOpenHost(sink: sink)
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
        // Two open paths, tried in order:
        //   1. `extensionContext.open(_:)` — the only Apple-blessed API,
        //      works on iOS ≤ 17 and sometimes on 18. Requires Full Access
        //      (already gated in `KeyboardViewController.micTapped`) and
        //      `LSApplicationQueriesSchemes` listing `turtlekeyboard`
        //      (set in the extension's Info.plist).
        //   2. Runtime `+[UIApplication sharedApplication]` →
        //      `openURL:options:completionHandler:` — the *modern* open
        //      method called via an IMP cast. The deprecated single-arg
        //      `openURL:` is a no-op on iOS 18, so we bypass it entirely.
        //      Wispr Flow and similar dictation keyboards rely on this
        //      runtime path; it is what makes the cold-start launch work
        //      on current iOS.
        let url = Self.launchURL

        if let ctx = host.extensionContext {
            ctx.open(url) { [weak self] success in
                if success {
                    DispatchQueue.main.async { completion(true) }
                    return
                }
                DispatchQueue.main.async {
                    self?.openViaSharedApplication(url: url, completion: completion)
                }
            }
            return
        }
        openViaSharedApplication(url: url, completion: completion)
    }

    private func openViaSharedApplication(url: URL,
                                          completion: @escaping (Bool) -> Void) {
        // `UIApplication.shared` is unavailable to extensions at compile
        // time, but the class and its `+sharedApplication` class method
        // both exist at runtime — fetch the instance dynamically.
        let sharedSel = NSSelectorFromString("sharedApplication")
        guard let appClass = NSClassFromString("UIApplication") as? NSObject.Type,
              appClass.responds(to: sharedSel),
              let unmanaged = appClass.perform(sharedSel),
              let app = unmanaged.takeUnretainedValue() as? NSObject
        else {
            completion(false)
            return
        }

        // Reach `open(_:options:completionHandler:)` by its IMP and call
        // it via a C function pointer. `perform(_:with:)` only supports
        // one argument so the modern signature can't go through it.
        let modernSel = NSSelectorFromString("openURL:options:completionHandler:")
        if app.responds(to: modernSel),
           let method = class_getInstanceMethod(type(of: app), modernSel) {
            typealias OpenFn = @convention(c) (
                AnyObject, Selector, URL, NSDictionary, ((Bool) -> Void)?
            ) -> Void
            let openFn = unsafeBitCast(method_getImplementation(method),
                                       to: OpenFn.self)
            openFn(app, modernSel, url, NSDictionary()) { ok in
                DispatchQueue.main.async { completion(ok) }
            }
            return
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
        watchdog?.invalidate()
        watchdog = nil
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
