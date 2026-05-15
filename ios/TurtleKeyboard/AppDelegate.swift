import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

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

    func application(_ app: UIApplication,
                     open url: URL,
                     options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        return route(url: url)
    }

    /// Routes a `turtlekeyboard://<screen-id>` URL to the matching host
    /// screen. Returning false signals iOS that the URL wasn't handled.
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
        default:
            return false
        }
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
