import UIKit
import WebKit

/// Results sheet for `turtlekeyboard://wyr/<id>`. iOS counterpart to
/// Android's `WyrSheetView`.
///
/// Android renders a full state machine (LOADING → PLAYING → SUBMITTING →
/// RESULTS) using native UI because the worker has `GET /wyr/<id>` and
/// `POST /wyr/<id>/answers` endpoints. The iOS `WyrClient` ships only
/// `create(...)` today, so the native game UI would need the worker-side
/// reads first. Until those exist, we present the existing shareable URL
/// (`https://www.turtlekeyboard.com/wyr/<id>`) inside a WKWebView so
/// players can still join the game on iOS. Drop-in replace this VC with
/// a native renderer once `WyrClient.read(...)` lands.
final class WyrSheetViewController: UIViewController, WKNavigationDelegate {

    private let brandGreen = UIColor.systemGreen

    private let wyrId: String
    private var webView: WKWebView!
    private let progress = UIProgressView(progressViewStyle: .bar)
    private let statusView = ConnectionStatusView()
    private var progressObserver: NSKeyValueObservation?

    init(wyrId: String) {
        self.wyrId = wyrId
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) not used") }

    deinit {
        progressObserver?.invalidate()
        webView?.stopLoading()
        NotificationCenter.default.removeObserver(self)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        title = "Would You Rather"
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Done", style: .done, target: self, action: #selector(dismissTapped))

        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .nonPersistent()
        cfg.allowsInlineMediaPlayback = true
        webView = WKWebView(frame: .zero, configuration: cfg)
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.backgroundColor = .systemBackground
        webView.scrollView.backgroundColor = .systemBackground
        webView.navigationDelegate = self

        progress.translatesAutoresizingMaskIntoConstraints = false
        progress.progressTintColor = .systemGreen
        progress.trackTintColor = .clear

        view.addSubview(progress)
        view.addSubview(webView)
        statusView.translatesAutoresizingMaskIntoConstraints = false
        statusView.onRetry = { [weak self] in self?.loadGame() }
        view.addSubview(statusView)
        NSLayoutConstraint.activate([
            progress.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            progress.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            progress.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            progress.heightAnchor.constraint(equalToConstant: 2),

            webView.topAnchor.constraint(equalTo: progress.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            statusView.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            statusView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            statusView.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])

        progressObserver = webView.observe(\.estimatedProgress, options: [.new]) { [weak self] _, change in
            guard let self = self, let value = change.newValue else { return }
            DispatchQueue.main.async {
                self.progress.setProgress(Float(value), animated: true)
                self.progress.isHidden = value >= 1.0
            }
        }

        loadGame()
        NotificationCenter.default.addObserver(self, selector: #selector(networkChanged),
                                               name: AppNetworkMonitor.didChange, object: nil)
    }

    private func loadGame() {
        guard AppNetworkMonitor.shared.isOnline else {
            webView.isHidden = true
            statusView.isHidden = false
            statusView.render(.needsAttention, service: "Game",
                              detail: "You’re offline. Reconnect to open this game.", canRetry: true)
            return
        }
        guard let url = URL(string: "https://www.turtlekeyboard.com/wyr/\(wyrId)") else { return }
        statusView.isHidden = false
        statusView.render(.loading, service: "Game", detail: "Opening the shared game…")
        webView.isHidden = false
        webView.load(URLRequest(url: url, cachePolicy: .reloadRevalidatingCacheData, timeoutInterval: 20))
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        statusView.isHidden = true
        UIAccessibility.post(notification: .announcement, argument: "Game loaded")
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        showLoadFailure()
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        showLoadFailure()
    }

    private func showLoadFailure() {
        webView.isHidden = true
        progress.isHidden = true
        statusView.isHidden = false
        statusView.render(.needsAttention, service: "Game",
                          detail: "This game couldn’t load. Check your connection and retry.", canRetry: true)
    }

    @objc private func networkChanged() {
        if AppNetworkMonitor.shared.isOnline { loadGame() }
        else { showLoadFailure() }
    }

    @objc private func dismissTapped() { dismiss(animated: true) }
}
