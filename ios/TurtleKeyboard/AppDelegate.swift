import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    // App Group rendezvous key the keyboard sets when the user taps mic.
    // Keep in sync with VoiceInputController.kVoiceRequested.
    private static let voiceAppGroup    = "group.com.turtlekeyboard.split"
    private static let kVoiceRequested  = "voice.requested"
    // Anything older than this is ignored — guards against firing the
    // recorder on unrelated app activations (e.g. the user launched
    // Turtle from Springboard).
    private static let voiceRequestTTL: TimeInterval = 30

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = UINavigationController(rootViewController: ViewController())
        window?.makeKeyAndVisible()

        // Cold-launch via URL — iOS hands the URL through launchOptions
        // instead of (or in addition to) calling application(_:open:options:),
        // so route it here too.
        if let url = launchOptions?[.url] as? URL {
            DispatchQueue.main.async { _ = self.route(url: url) }
        }
        return true
    }

    /// On iOS 18+, keyboard extensions can't programmatically open their
    /// container app — so the keyboard sets an App Group flag and asks the
    /// user to switch apps. When the user does, this handler picks up the
    /// flag and auto-presents the recording sheet.
    func applicationDidBecomeActive(_ application: UIApplication) {
        guard let d = UserDefaults(suiteName: Self.voiceAppGroup) else { return }
        let requestedAt = d.double(forKey: Self.kVoiceRequested)
        guard requestedAt > 0 else { return }
        let age = Date().timeIntervalSince1970 - requestedAt
        // Always clear the flag so we don't re-fire on the next activation.
        d.removeObject(forKey: Self.kVoiceRequested)
        guard age >= 0, age <= Self.voiceRequestTTL else { return }
        DispatchQueue.main.async { self.presentVoice() }
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
        case "poll":
            guard let id = artifactId(in: url) else { return false }
            present(PollSheetViewController(pollId: id))
            return true
        case "wyr":
            guard let id = artifactId(in: url) else { return false }
            present(WyrSheetViewController(wyrId: id))
            return true
        case "voice":
            presentVoice()
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

    /// Full-screen modal because the user is going to swipe back to their
    /// host app anyway — the recording UI is throwaway, not a nav-stack
    /// destination.
    private func presentVoice() {
        guard let nav = window?.rootViewController as? UINavigationController else { return }
        // Don't stack a second recording sheet on top of an existing one
        // — can happen if the user re-activates while it's still up.
        if nav.presentedViewController is VoiceRecordingViewController { return }
        let vc = VoiceRecordingViewController()
        vc.modalPresentationStyle = .fullScreen
        nav.dismiss(animated: false) { nav.present(vc, animated: true) }
    }
}
