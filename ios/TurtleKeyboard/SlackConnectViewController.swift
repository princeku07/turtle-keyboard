import UIKit

/// Host-app screen for connecting Slack. Sign in → fetch user channels →
/// user picks a default channel → store it. The picker also writes a
/// `name → id` map so the keyboard can resolve `#channel-name` overrides.
final class SlackConnectViewController: UIViewController {

    private let brandGreen = UIColor(red: 0.106, green: 0.369, blue: 0.125, alpha: 1.0)
    private let cardGreen  = UIColor(red: 0.082, green: 0.502, blue: 0.239, alpha: 1.0)

    private let store: SplitStore = UserDefaultsSplitStore(suiteName: SplitContract.storageSuiteName)
    private lazy var auth = SlackAuth(store: store, presentationAnchor: view.window)

    private let statusLabel = UILabel()
    private let actionButton = UIButton(type: .system)
    private let pickerLabel = UILabel()
    private let pickerStack = UIStackView()
    private var channels: [SlackChannel] = []

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = brandGreen
        title = "Connect Slack"
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Done", style: .done, target: self, action: #selector(dismissTapped))

        statusLabel.font = .systemFont(ofSize: 15)
        statusLabel.textColor = .white
        statusLabel.numberOfLines = 0

        actionButton.setTitleColor(brandGreen, for: .normal)
        actionButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        actionButton.backgroundColor = .white
        actionButton.layer.cornerRadius = 8
        actionButton.contentEdgeInsets = UIEdgeInsets(top: 10, left: 16, bottom: 10, right: 16)
        actionButton.addTarget(self, action: #selector(actionTapped), for: .touchUpInside)

        pickerLabel.text = "Default channel"
        pickerLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        pickerLabel.textColor = .white

        pickerStack.axis = .vertical
        pickerStack.spacing = 6

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        let stack = UIStackView(arrangedSubviews: [statusLabel, actionButton, pickerLabel, pickerStack])
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
            statusLabel.text = "Slack OAuth not configured.\nFill in client ID + secret — see OAUTH_SETUP_iOS.md."
            actionButton.setTitle("How to set up", for: .normal)
            pickerLabel.isHidden = true
            pickerStack.isHidden = true
            return
        }
        if !auth.isSignedIn {
            statusLabel.text = "Sign in with Slack to enable /slack in the keyboard."
            actionButton.setTitle("Sign in to Slack", for: .normal)
            pickerLabel.isHidden = true
            pickerStack.isHidden = true
            return
        }
        let team = auth.teamName ?? "your workspace"
        let channel = store.string(forKey: SlackKeys.defaultChannelName, fallback: "")
        let line = channel.isEmpty ? "Pick a default channel below" : "Default channel: #\(channel)"
        statusLabel.text = "Connected to \(team).\n\(line)"
        actionButton.setTitle("Disconnect", for: .normal)
        pickerLabel.isHidden = false
        pickerStack.isHidden = false
        loadChannels()
    }

    @objc private func actionTapped() {
        if !auth.isConfigured {
            showAlert(title: "Slack OAuth not configured",
                      message: "Open OAUTH_SETUP_iOS.md, create a Slack app, paste client ID + secret into SlackAuth.swift and the redirect scheme into Info.plist.")
            return
        }
        if auth.isSignedIn {
            auth.signOut()
            refresh()
            return
        }
        actionButton.isEnabled = false
        actionButton.setTitle("Signing in…", for: .normal)
        auth.signIn { [weak self] result in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.actionButton.isEnabled = true
                switch result {
                case .success: self.refresh()
                case .failure(let err):
                    self.refresh()
                    self.showAlert(title: "Sign-in failed", message: err.localizedDescription)
                }
            }
        }
    }

    private func loadChannels() {
        let token = store.string(forKey: SlackKeys.accessToken, fallback: "")
        guard !token.isEmpty else { return }
        Task { @MainActor in
            do {
                let result = try await SlackClient.listChannels(accessToken: token)
                self.channels = result.sorted { $0.name < $1.name }
                // Persist a name → id map so the keyboard can resolve
                // #channel-name overrides.
                for c in self.channels {
                    self.store.setString(c.id, forKey: SlackKeys.channelMapPrefix + c.name.lowercased())
                }
                self.renderPicker()
            } catch {
                self.showAlert(title: "Couldn't load channels", message: error.localizedDescription)
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
            row.setTitleColor(.white, for: .normal)
            row.backgroundColor = c.id == currentId ? brandGreen : cardGreen
            row.layer.cornerRadius = 6
            row.contentEdgeInsets = UIEdgeInsets(top: 10, left: 12, bottom: 10, right: 12)
            row.addAction(UIAction(handler: { [weak self] _ in
                self?.store.setString(c.id, forKey: SlackKeys.defaultChannel)
                self?.store.setString(c.name, forKey: SlackKeys.defaultChannelName)
                self?.refresh()
            }), for: .touchUpInside)
            pickerStack.addArrangedSubview(row)
        }
        if channels.isEmpty {
            let empty = UILabel()
            empty.text = "No channels found. Join one in Slack first."
            empty.font = .systemFont(ofSize: 13)
            empty.textColor = UIColor.white.withAlphaComponent(0.75)
            empty.numberOfLines = 0
            pickerStack.addArrangedSubview(empty)
        }
    }

    @objc private func dismissTapped() { dismiss(animated: true) }

    private func showAlert(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }
}
