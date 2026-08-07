import UIKit

/// Host-app screen for connecting Slack. Sign in → fetch user channels →
/// user picks a default channel → store it. The picker also writes a
/// `name → id` map so the keyboard can resolve `#channel-name` overrides.
final class SlackConnectViewController: UIViewController {

    private let brandGreen = UIColor.systemGreen
    private let cardGreen  = UIColor.secondarySystemGroupedBackground

    private let store: SplitStore = UserDefaultsSplitStore(suiteName: SplitContract.storageSuiteName)
    private lazy var auth = SlackAuth(store: store, presentationAnchor: view.window)

    private let statusView = ConnectionStatusView()
    private let actionButton = UIButton(type: .system)
    private let pickerLabel = UILabel()
    private let pickerStack = UIStackView()
    private var channels: [SlackChannel] = []
    private var channelTask: Task<Void, Never>?

    deinit {
        channelTask?.cancel()
        NotificationCenter.default.removeObserver(self)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        title = "Connect Slack"
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

        pickerLabel.text = "Default channel"
        pickerLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        pickerLabel.textColor = .secondaryLabel

        pickerStack.axis = .vertical
        pickerStack.spacing = 6

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
            statusView.render(.needsAttention, service: "Slack",
                              detail: "Slack connection is temporarily unavailable.")
            actionButton.setTitle("Unavailable", for: .normal)
            actionButton.isEnabled = false
            pickerLabel.isHidden = true
            pickerStack.isHidden = true
            return
        }
        if !AppNetworkMonitor.shared.isOnline {
            statusView.render(.needsAttention, service: "Slack",
                              detail: "You’re offline. Reconnect to the internet and try again.", canRetry: true)
            actionButton.setTitle(auth.isSignedIn ? "Disconnect" : "Sign in to Slack", for: .normal)
            actionButton.isEnabled = auth.isSignedIn
            pickerLabel.isHidden = true
            pickerStack.isHidden = true
            return
        }
        if !auth.isSignedIn {
            statusView.render(.notConnected, service: "Slack",
                              detail: "Sign in to enable /slack in the keyboard.")
            actionButton.setTitle("Sign in to Slack", for: .normal)
            actionButton.isEnabled = true
            pickerLabel.isHidden = true
            pickerStack.isHidden = true
            return
        }
        let team = auth.teamName ?? "your workspace"
        let channel = store.string(forKey: SlackKeys.defaultChannelName, fallback: "")
        let line = channel.isEmpty ? "Pick a default channel below" : "Default channel: #\(channel)"
        statusView.render(.connected, service: "Slack", detail: "\(team) · \(line)")
        actionButton.setTitle("Disconnect", for: .normal)
        pickerLabel.isHidden = false
        pickerStack.isHidden = false
        loadChannels()
    }

    @objc private func actionTapped() {
        if !auth.isConfigured {
            showAlert(title: "Slack unavailable",
                      message: "Slack connection isn’t available right now. Please try again after updating Turtle.")
            return
        }
        if auth.isSignedIn {
            auth.signOut()
            refresh()
            return
        }
        actionButton.isEnabled = false
        actionButton.setTitle("Signing in…", for: .normal)
        statusView.render(.loading, service: "Slack", detail: "Waiting for Slack sign-in…")
        auth.signIn { [weak self] result in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.actionButton.isEnabled = true
                switch result {
                case .success:
                    HostPrivacySafeTelemetry.integrationConnected(.slack)
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    self.refresh()
                case .failure:
                    self.statusView.render(.needsAttention, service: "Slack",
                                           detail: "Couldn’t connect. Check your connection and try again.", canRetry: true)
                    self.actionButton.setTitle("Try sign-in again", for: .normal)
                }
            }
        }
    }

    private func loadChannels() {
        let token = store.string(forKey: SlackKeys.accessToken, fallback: "")
        guard !token.isEmpty else { return }
        channelTask?.cancel()
        statusView.render(.loading, service: "Slack", detail: "Loading your channels…")
        channelTask = Task { @MainActor in
            do {
                let result = try await SlackClient.listChannels(accessToken: token)
                guard !Task.isCancelled else { return }
                self.channels = result.sorted { $0.name < $1.name }
                // Persist a name → id map so the keyboard can resolve
                // #channel-name overrides.
                for c in self.channels {
                    self.store.setString(c.id, forKey: SlackKeys.channelMapPrefix + c.name.lowercased())
                }
                let detail = result.isEmpty ? "Connected, but no available channels were found." : "Connected · Choose a default channel below."
                self.statusView.render(.connected, service: "Slack", detail: detail, canRetry: result.isEmpty)
                self.renderPicker()
            } catch {
                guard !Task.isCancelled else { return }
                self.statusView.render(.needsAttention, service: "Slack",
                                       detail: "Couldn’t load channels. Check your connection and retry.", canRetry: true)
            }
        }
    }

    private func renderPicker() {
        pickerStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        let currentId = store.string(forKey: SlackKeys.defaultChannel, fallback: "")
        for c in channels {
            let row = UIButton(type: .system)
            let lock = c.isPrivate ? "🔒 " : "# "
            row.setTitle("  \(lock)\(c.name)", for: .normal)
            row.contentHorizontalAlignment = .leading
            row.titleLabel?.font = .systemFont(ofSize: 14, weight: c.id == currentId ? .bold : .regular)
            row.setTitleColor(c.id == currentId ? .white : .label, for: .normal)
            row.backgroundColor = c.id == currentId ? brandGreen : cardGreen
            row.layer.cornerRadius = 10
            row.layer.cornerCurve = .continuous
            row.contentEdgeInsets = UIEdgeInsets(top: 10, left: 12, bottom: 10, right: 12)
            row.addAction(UIAction(handler: { [weak self] _ in
                self?.store.setString(c.id, forKey: SlackKeys.defaultChannel)
                self?.store.setString(c.name, forKey: SlackKeys.defaultChannelName)
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                self?.refresh()
            }), for: .touchUpInside)
            pickerStack.addArrangedSubview(row)
        }
        if channels.isEmpty {
            let empty = UILabel()
            empty.text = "No channels found. Join one in Slack first."
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
