import UIKit

/// Host-app screen for connecting Notion. Sign in → fetch top-level pages
/// → user picks a default parent → store it. After this completes the
/// keyboard's `/notion` command can fire.
final class NotionConnectViewController: UIViewController {

    private let brandGreen = UIColor.systemGreen
    private let cardGreen  = UIColor.secondarySystemGroupedBackground

    private let store: SplitStore = UserDefaultsSplitStore(suiteName: SplitContract.storageSuiteName)
    private lazy var auth = NotionAuth(store: store, presentationAnchor: view.window)

    private let statusView = ConnectionStatusView()
    private let actionButton = UIButton(type: .system)
    private let pickerLabel = UILabel()
    private let pickerStack = UIStackView()
    private var pages: [NotionPage] = []
    private var pagesTask: Task<Void, Never>?

    deinit {
        pagesTask?.cancel()
        NotificationCenter.default.removeObserver(self)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        title = "Connect Notion"
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Done", style: .done, target: self, action: #selector(dismissTapped))

        statusView.onRetry = { [weak self] in self?.refresh() }
        NotificationCenter.default.addObserver(self, selector: #selector(networkChanged),
                                               name: AppNetworkMonitor.didChange, object: nil)

        actionButton.setTitleColor(.white, for: .normal)
        actionButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        actionButton.backgroundColor = .systemGreen
        actionButton.layer.cornerRadius = 10
        actionButton.layer.cornerCurve = .continuous
        actionButton.contentEdgeInsets = UIEdgeInsets(top: 10, left: 16, bottom: 10, right: 16)
        actionButton.addTarget(self, action: #selector(actionTapped), for: .touchUpInside)

        pickerLabel.text = "Default parent page"
        pickerLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        pickerLabel.textColor = .secondaryLabel

        pickerStack.axis = .vertical
        pickerStack.spacing = 8

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        let stack = UIStackView(arrangedSubviews: [statusView, actionButton, pickerLabel, pickerStack])
        stack.axis = .vertical
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        view.addSubview(scroll)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            stack.topAnchor.constraint(equalTo: scroll.topAnchor, constant: 16),
            stack.leadingAnchor.constraint(equalTo: scroll.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: scroll.trailingAnchor, constant: -16),
            stack.bottomAnchor.constraint(equalTo: scroll.bottomAnchor, constant: -16),
            stack.widthAnchor.constraint(equalTo: scroll.widthAnchor, constant: -32),
        ])
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refresh()
    }

    private func refresh() {
        if !auth.isConfigured {
            statusView.render(.needsAttention, service: "Notion",
                              detail: "Notion connection is temporarily unavailable.")
            actionButton.setTitle("Unavailable", for: .normal)
            actionButton.isEnabled = false
            pickerLabel.isHidden = true
            pickerStack.isHidden = true
            return
        }
        if !AppNetworkMonitor.shared.isOnline {
            statusView.render(.needsAttention, service: "Notion",
                              detail: "You’re offline. Reconnect to the internet and try again.", canRetry: true)
            actionButton.setTitle(auth.isSignedIn ? "Disconnect" : "Sign in to Notion", for: .normal)
            actionButton.isEnabled = auth.isSignedIn
            pickerLabel.isHidden = true
            pickerStack.isHidden = true
            return
        }
        if !auth.isSignedIn {
            statusView.render(.notConnected, service: "Notion",
                              detail: "Sign in to enable /notion in the keyboard.")
            actionButton.setTitle("Sign in to Notion", for: .normal)
            actionButton.isEnabled = true
            pickerLabel.isHidden = true
            pickerStack.isHidden = true
            return
        }
        let workspace = auth.workspaceName ?? "your workspace"
        let parent = store.string(forKey: NotionKeys.defaultParentT, fallback: "")
        let parentLine = parent.isEmpty ? "Pick a parent page below" : "Default parent: \(parent)"
        statusView.render(.connected, service: "Notion", detail: "\(workspace) · \(parentLine)")
        actionButton.setTitle("Disconnect", for: .normal)
        pickerLabel.isHidden = false
        pickerStack.isHidden = false
        loadPages()
    }

    @objc private func actionTapped() {
        if !auth.isConfigured {
            showAlert(title: "Notion unavailable",
                      message: "Notion connection isn’t available right now. Please try again after updating Turtle.")
            return
        }
        if auth.isSignedIn {
            auth.signOut()
            refresh()
            return
        }
        actionButton.isEnabled = false
        actionButton.setTitle("Signing in…", for: .normal)
        statusView.render(.loading, service: "Notion", detail: "Waiting for Notion sign-in…")
        auth.signIn { [weak self] result in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.actionButton.isEnabled = true
                switch result {
                case .success:
                    HostPrivacySafeTelemetry.integrationConnected(.notion)
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    self.refresh()
                case .failure:
                    self.statusView.render(.needsAttention, service: "Notion",
                                           detail: "Couldn’t connect. Check your connection and try again.", canRetry: true)
                    self.actionButton.setTitle("Try sign-in again", for: .normal)
                }
            }
        }
    }

    private func loadPages() {
        guard let token = Optional(store.string(forKey: NotionKeys.accessToken, fallback: "")),
              !token.isEmpty
        else { return }
        pagesTask?.cancel()
        statusView.render(.loading, service: "Notion", detail: "Loading your pages…")
        pagesTask = Task { @MainActor in
            do {
                let result = try await NotionClient.searchPages(accessToken: token)
                guard !Task.isCancelled else { return }
                self.pages = result
                let detail = result.isEmpty ? "Connected, but no shared pages were found." : "Connected · Choose a parent page below."
                self.statusView.render(.connected, service: "Notion", detail: detail, canRetry: result.isEmpty)
                self.renderPicker()
            } catch {
                guard !Task.isCancelled else { return }
                self.statusView.render(.needsAttention, service: "Notion",
                                       detail: "Couldn’t load pages. Check your connection and retry.", canRetry: true)
            }
        }
    }

    private func renderPicker() {
        pickerStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        let currentId = store.string(forKey: NotionKeys.defaultParent, fallback: "")
        for page in pages {
            let row = UIButton(type: .system)
            row.setTitle("  \(page.title)", for: .normal)
            row.contentHorizontalAlignment = .leading
            row.titleLabel?.font = .systemFont(ofSize: 14, weight: page.id == currentId ? .bold : .regular)
            row.setTitleColor(page.id == currentId ? .white : .label, for: .normal)
            row.backgroundColor = page.id == currentId ? brandGreen : cardGreen
            row.layer.cornerRadius = 10
            row.layer.cornerCurve = .continuous
            row.contentEdgeInsets = UIEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
            row.addAction(UIAction(handler: { [weak self] _ in
                self?.store.setString(page.id, forKey: NotionKeys.defaultParent)
                self?.store.setString(page.title, forKey: NotionKeys.defaultParentT)
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                self?.refresh()
            }), for: .touchUpInside)
            pickerStack.addArrangedSubview(row)
        }
        if pages.isEmpty {
            let empty = UILabel()
            empty.text = "No pages found. Share a page with the integration in Notion (… menu → Add connections)."
            empty.font = .systemFont(ofSize: 13)
            empty.textColor = .secondaryLabel
            empty.numberOfLines = 0
            pickerStack.addArrangedSubview(empty)
        }
    }

    @objc private func dismissTapped() { dismiss(animated: true) }
    @objc private func networkChanged() { refresh() }

    private func showAlert(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }
}
