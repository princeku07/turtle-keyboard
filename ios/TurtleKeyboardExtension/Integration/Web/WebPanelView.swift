import Foundation
#if os(iOS)
import UIKit
import WebKit

// MARK: - WebPanelView
//
// Full-panel WKWebView host. Mirrors Android's WebViewPanel: thin header
// (URL on the left, open-in-Safari + close on the right), a 2 pt progress
// strip while loading, then the WKWebView filling the remainder.
//
// Memory budget: keyboard extensions are tightly capped (~70 MB typical).
// We use a non-persistent data store and ship an "Open in Safari" hand-off
// so heavy pages can be escaped to the system browser without OOM-killing
// the IME process.

final class WebPanelView: UIView {

    typealias CloseHandler = () -> Void
    typealias OpenExternalHandler = (URL) -> Void

    private let webView: WKWebView
    private let urlLabel = UILabel()
    private let progress = UIProgressView(progressViewStyle: .bar)
    private var progressObserver: NSKeyValueObservation?

    private var onClose: CloseHandler?
    private var onOpenExternal: OpenExternalHandler?
    private var currentURL: URL?

    // Palette mirrors WebViewPanel (Android) + KeyboardPalette.
    private static let cream = UIColor(red: 0xF4/255, green: 0xEF/255, blue: 0xE4/255, alpha: 1)
    private static let ink   = UIColor(red: 0x0C/255, green: 0x0C/255, blue: 0x0C/255, alpha: 1)
    private static let muted = UIColor(red: 0x6B/255, green: 0x6B/255, blue: 0x6B/255, alpha: 1)
    private static let lime  = UIColor(red: 0x15/255, green: 0x80/255, blue: 0x3D/255, alpha: 1)

    override init(frame: CGRect) {
        // Non-persistent: cookies/localStorage discarded when the panel closes.
        // Page reloads will re-auth, but the extension stays under its RAM cap.
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .nonPersistent()
        cfg.allowsInlineMediaPlayback = true
        self.webView = WKWebView(frame: .zero, configuration: cfg)
        super.init(frame: frame)
        backgroundColor = Self.cream
        buildHeader()
        buildProgressBar()
        buildWebView()
        observeProgress()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) not used") }

    deinit {
        progressObserver?.invalidate()
        webView.stopLoading()
        webView.navigationDelegate = nil
    }

    // MARK: - Public API

    func setHandlers(onClose: @escaping CloseHandler,
                     onOpenExternal: @escaping OpenExternalHandler) {
        self.onClose = onClose
        self.onOpenExternal = onOpenExternal
    }

    func load(url: URL) {
        currentURL = url
        urlLabel.text = url.absoluteString
        webView.load(URLRequest(url: url))
    }

    // MARK: - Subview construction

    private func buildHeader() {
        let header = UIView()
        header.translatesAutoresizingMaskIntoConstraints = false
        addSubview(header)

        urlLabel.translatesAutoresizingMaskIntoConstraints = false
        urlLabel.font = UIFont.monospacedSystemFont(ofSize: 12, weight: .regular)
        urlLabel.textColor = Self.muted
        urlLabel.lineBreakMode = .byTruncatingMiddle
        header.addSubview(urlLabel)

        let openBtn = UIButton(type: .system)
        openBtn.translatesAutoresizingMaskIntoConstraints = false
        openBtn.setTitle("↗", for: .normal)
        openBtn.setTitleColor(Self.ink, for: .normal)
        openBtn.titleLabel?.font = UIFont.systemFont(ofSize: 18, weight: .semibold)
        openBtn.addTarget(self, action: #selector(handleOpenExternal), for: .touchUpInside)
        header.addSubview(openBtn)

        let closeBtn = UIButton(type: .system)
        closeBtn.translatesAutoresizingMaskIntoConstraints = false
        closeBtn.setTitle("×", for: .normal)
        closeBtn.setTitleColor(Self.ink, for: .normal)
        closeBtn.titleLabel?.font = UIFont.systemFont(ofSize: 22, weight: .semibold)
        closeBtn.addTarget(self, action: #selector(handleClose), for: .touchUpInside)
        header.addSubview(closeBtn)

        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: topAnchor),
            header.leadingAnchor.constraint(equalTo: leadingAnchor),
            header.trailingAnchor.constraint(equalTo: trailingAnchor),
            header.heightAnchor.constraint(equalToConstant: 34),

            urlLabel.leadingAnchor.constraint(equalTo: header.leadingAnchor, constant: 12),
            urlLabel.centerYAnchor.constraint(equalTo: header.centerYAnchor),
            urlLabel.trailingAnchor.constraint(equalTo: openBtn.leadingAnchor, constant: -8),

            openBtn.trailingAnchor.constraint(equalTo: closeBtn.leadingAnchor, constant: -4),
            openBtn.centerYAnchor.constraint(equalTo: header.centerYAnchor),
            openBtn.widthAnchor.constraint(equalToConstant: 32),

            closeBtn.trailingAnchor.constraint(equalTo: header.trailingAnchor, constant: -8),
            closeBtn.centerYAnchor.constraint(equalTo: header.centerYAnchor),
            closeBtn.widthAnchor.constraint(equalToConstant: 32),
        ])

        // Stash header so the progress bar can pin under it.
        header.tag = 0xBEEF
    }

    private func buildProgressBar() {
        progress.translatesAutoresizingMaskIntoConstraints = false
        progress.progressTintColor = Self.lime
        progress.trackTintColor = .clear
        addSubview(progress)

        let header = viewWithTag(0xBEEF)!
        NSLayoutConstraint.activate([
            progress.topAnchor.constraint(equalTo: header.bottomAnchor),
            progress.leadingAnchor.constraint(equalTo: leadingAnchor),
            progress.trailingAnchor.constraint(equalTo: trailingAnchor),
            progress.heightAnchor.constraint(equalToConstant: 2),
        ])
    }

    private func buildWebView() {
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.backgroundColor = .white
        webView.scrollView.backgroundColor = .white
        addSubview(webView)

        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: progress.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    private func observeProgress() {
        progressObserver = webView.observe(\.estimatedProgress, options: [.new]) { [weak self] _, change in
            guard let self = self, let value = change.newValue else { return }
            DispatchQueue.main.async {
                self.progress.setProgress(Float(value), animated: true)
                self.progress.isHidden = value >= 1.0
                if let live = self.webView.url?.absoluteString { self.urlLabel.text = live }
            }
        }
    }

    // MARK: - Actions

    @objc private func handleClose() { onClose?() }

    @objc private func handleOpenExternal() {
        let target = webView.url ?? currentURL
        guard let url = target else { return }
        onOpenExternal?(url)
    }
}
#endif
