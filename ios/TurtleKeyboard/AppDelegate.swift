import UIKit
#if canImport(WidgetKit)
import WidgetKit
#endif

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    // App Group rendezvous key the keyboard sets when the user taps mic.
    // Keep in sync with VoiceInputController.kVoiceRequested.
    private static let voiceAppGroup    = "group.com.samarth.turtlekeyboard.split"
    private static let kVoiceRequested  = "voice.requested"
    // Anything older than this is ignored — guards against firing the
    // recorder on unrelated app activations (e.g. the user launched
    // Turtle from Springboard).
    private static let voiceRequestTTL: TimeInterval = 30

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let navigationAppearance = UINavigationBarAppearance()
        navigationAppearance.configureWithDefaultBackground()
        UINavigationBar.appearance().standardAppearance = navigationAppearance
        UINavigationBar.appearance().scrollEdgeAppearance = navigationAppearance

        window = UIWindow(frame: UIScreen.main.bounds)
        window?.tintColor = .systemGreen
        if OnboardingState.isComplete {
            window?.rootViewController = UINavigationController(rootViewController: ViewController())
        } else {
            HostPrivacySafeTelemetry.onboardingStarted()
            let onboarding = OnboardingViewController()
            onboarding.onComplete = { [weak self] in self?.showHomeAfterOnboarding() }
            window?.rootViewController = onboarding
        }
        window?.makeKeyAndVisible()

        // Spin up the headless voice manager. Once it's registered the
        // keyboard can drive recording via Darwin notifications — the user
        // does NOT need to swipe to Turtle on every mic tap, as long as
        // this host process is still alive in the background.
        VoiceSessionManager.shared.register()

        // Cold-launch via URL — iOS hands the URL through launchOptions
        // instead of (or in addition to) calling application(_:open:options:),
        // so route it here too.
        if let url = launchOptions?[.url] as? URL {
            DispatchQueue.main.async { _ = self.route(url: url) }
        }
        return true
    }

    private func showHomeAfterOnboarding() {
        guard let window = window else { return }
        let home = UINavigationController(rootViewController: ViewController())
        UIView.transition(with: window, duration: 0.35, options: .transitionCrossDissolve) {
            window.rootViewController = home
        }
    }

    /// Swipe-to-Turtle path: the user tapped mic in the keyboard while
    /// this process was dead, saw the toast asking them to open Turtle,
    /// and just did. Present the "Swipe back to your app" coachmark.
    /// `VoiceRecordingViewController` itself listens for the user
    /// swiping back and kicks `VoiceSessionManager.startIfRequested()`
    /// at that moment — by then the keep-alive engine is primed.
    func applicationDidBecomeActive(_ application: UIApplication) {
        VoiceSessionManager.shared.writeHeartbeat()
        presentVoiceCoachmarkIfRequested()
        reloadWidgets()
    }

    /// Enabling the keyboard and granting Full Access both happen in
    /// Settings, outside every Turtle process, so there's no event the
    /// Setup Status widget can hang a reload on. Foregrounding the app is
    /// the closest proxy — it's where the user lands right after flipping
    /// those switches. Cheap, and far rarer than a keyboard mount, so it
    /// doesn't eat the system's daily reload budget.
    private func reloadWidgets() {
        #if canImport(WidgetKit)
        if #available(iOS 14.0, *) {
            WidgetCenter.shared.reloadAllTimelines()
        }
        #endif
    }

    private func presentVoiceCoachmarkIfRequested() {
        guard let d = UserDefaults(suiteName: Self.voiceAppGroup) else { return }
        let requestedAt = d.double(forKey: Self.kVoiceRequested)
        guard requestedAt > 0 else { return }
        let age = Date().timeIntervalSince1970 - requestedAt
        guard age >= 0, age <= Self.voiceRequestTTL else {
            d.removeObject(forKey: Self.kVoiceRequested)
            return
        }
        guard let nav = window?.rootViewController as? UINavigationController else { return }
        if nav.presentedViewController is VoiceRecordingViewController { return }
        let vc = VoiceRecordingViewController()
        vc.modalPresentationStyle = .fullScreen
        nav.dismiss(animated: false) { nav.present(vc, animated: true) }
    }

    func application(_ app: UIApplication,
                     open url: URL,
                     options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        return route(url: url)
    }

    /// Routes a `turtlekeyboard://<screen-id>[/<id>]` URL to the matching
    /// host screen. Returning false signals iOS that the URL wasn't handled.
    ///
    /// Supported routes:
    ///   • `turtlekeyboard://split-detail`            → SplitDetailViewController
    ///   • `turtlekeyboard://join?sheetId=…&owner=…`  → JoinSplitViewController
    ///   • `turtlekeyboard://notion-connect`          → NotionConnectViewController
    ///   • `turtlekeyboard://slack-connect`           → SlackConnectViewController
    ///   • `turtlekeyboard://personalization`         → PersonalizationViewController
    ///   • `turtlekeyboard://poll/<id>`               → PollSheetViewController
    ///   • `turtlekeyboard://wyr/<id>`                → WyrSheetViewController
    ///   • `turtlekeyboard://history[?ts=<ms>]`       → HistoryViewController
    @discardableResult
    private func route(url: URL) -> Bool {
        guard url.scheme == "turtlekeyboard" else { return false }
        // The screen ID lives in the URL "host" component, e.g.
        // turtlekeyboard://split-detail → "split-detail".
        let screenId = url.host ?? url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        switch screenId {
        case "split-detail":
            presentSplitDetail()
            return true
        case "join":
            handleJoinSplit(url: url)
            return true
        case "notion-connect":
            present(NotionConnectViewController())
            return true
        case "slack-connect":
            present(SlackConnectViewController())
            return true
        case "personalization", "personalize":
            present(PersonalizationViewController())
            return true
        case "history":
            // Entry point for the Recent Creations widget. The widget
            // appends `?ts=<timestampMs>` identifying the tapped image;
            // the grid doesn't scroll-to-entry yet, so the id is ignored
            // and we just open History.
            present(HistoryViewController())
            return true
        case "poll":
            guard let id = artifactId(in: url) else { return false }
            present(PollSheetViewController(pollId: id))
            return true
        case "wyr":
            guard let id = artifactId(in: url) else { return false }
            present(WyrSheetViewController(wyrId: id))
            return true
        case "voice":
            // Used only when the keyboard's old programmatic-open path
            // happens to succeed on a given iOS version. Otherwise the
            // user opens Turtle manually and we hit
            // `applicationDidBecomeActive` instead — both routes funnel
            // into the same coachmark.
            VoiceSessionManager.shared.writeHeartbeat()
            presentVoiceCoachmarkIfRequested()
            return true
        default:
            return false
        }
    }

    /// Pulls the trailing path component as the artifact id. Accepts both
    /// `turtlekeyboard://poll/<id>` and `turtlekeyboard://poll?id=<id>`
    /// since Android-emitted URLs use the path form but the query form is
    /// trivial to support and forgiving when copy-pasted by hand.
    private func artifactId(in url: URL) -> String? {
        let trimmed = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if !trimmed.isEmpty { return trimmed.split(separator: "/").last.map(String.init) }
        if let q = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "id" })?.value,
           !q.isEmpty {
            return q
        }
        return nil
    }

    private func present(_ vc: UIViewController) {
        guard let nav = window?.rootViewController as? UINavigationController else { return }
        let modal = UINavigationController(rootViewController: vc)
        nav.dismiss(animated: false) { nav.present(modal, animated: true) }
    }

    private func handleJoinSplit(url: URL) {
        guard let comps = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let sheetId = comps.queryItems?.first(where: { $0.name == "sheetId" })?.value,
              !sheetId.isEmpty
        else { return }
        let owner = comps.queryItems?.first(where: { $0.name == "owner" })?.value ?? ""
        present(JoinSplitViewController(sheetId: sheetId, ownerEmail: owner))
    }

    private func presentSplitDetail() {
        present(SplitDetailViewController())
    }

}
