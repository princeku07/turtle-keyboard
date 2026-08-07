import UIKit

/// Host-app screen for connecting GitHub.
///
/// One job: **pin the repos you care about** so they're one tap away in the
/// keyboard. Sign in to browse your private repos; or just type any public
/// `owner/repo` to pin it. The single search field does both — filter your
/// repos, or type a full `owner/repo` to pin one that isn't listed.
final class GitHubConnectViewController: UIViewController {

    private let brandGreen = UIColor.systemGreen
    private let cardGreen  = UIColor.secondarySystemGroupedBackground

    private let store: SplitStore = UserDefaultsSplitStore(suiteName: SplitContract.storageSuiteName)
    private lazy var auth = GitHubAuth(store: store, presentationAnchor: view.window)

    private let statusView   = ConnectionStatusView()
    private let actionButton = UIButton(type: .system)
    private let capabilities = UILabel()
    private let searchField  = UITextField()
    private let listStack    = UIStackView()

    private var allRepos: [GitHubAuth.Repo] = []
    private var loadingRepos = false

    deinit { NotificationCenter.default.removeObserver(self) }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        title = "Connect GitHub"
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Done", style: .done, target: self, action: #selector(dismissTapped))

        statusView.onRetry = { [weak self] in
            guard let self = self else { return }
            self.auth.isSignedIn ? self.loadRepos() : self.render()
        }
        NotificationCenter.default.addObserver(self, selector: #selector(networkChanged),
                                               name: AppNetworkMonitor.didChange, object: nil)

        styleSolidButton(actionButton, title: "Sign in with GitHub", action: #selector(actionTapped))

        capabilities.attributedText = capabilitiesText()
        capabilities.numberOfLines = 0

        searchField.placeholder = "Search your repos, or type owner/repo to pin"
        styleField(searchField)
        searchField.returnKeyType = .done
        searchField.addTarget(self, action: #selector(searchChanged), for: .editingChanged)
        searchField.addTarget(self, action: #selector(searchSubmit), for: .editingDidEndOnExit)

        listStack.axis = .vertical
        listStack.spacing = 6

        let scroll = UIScrollView()
        scroll.keyboardDismissMode = .interactive
        scroll.translatesAutoresizingMaskIntoConstraints = false
        let stack = UIStackView(arrangedSubviews: [
            statusView, actionButton, capabilities, searchField, listStack,
        ])
        stack.axis = .vertical
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        view.addSubview(scroll)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            stack.topAnchor.constraint(equalTo: scroll.topAnchor, constant: 16),
            stack.leadingAnchor.constraint(equalTo: scroll.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: scroll.trailingAnchor, constant: -16),
            stack.bottomAnchor.constraint(equalTo: scroll.bottomAnchor, constant: -16),
            stack.widthAnchor.constraint(equalTo: scroll.widthAnchor, constant: -32),
        ])
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        render()
        if auth.isSignedIn && allRepos.isEmpty { loadRepos() }
    }

    // MARK: - Render

    private func render() {
        if !auth.isConfigured {
            statusView.render(.needsAttention, service: "GitHub",
                              detail: "Sign-in is temporarily unavailable. Public repositories can still be pinned.")
            actionButton.setTitle("Unavailable", for: .normal)
            actionButton.isEnabled = false
        } else if !AppNetworkMonitor.shared.isOnline {
            statusView.render(.needsAttention, service: "GitHub",
                              detail: "You’re offline. Reconnect to load repositories.", canRetry: true)
            actionButton.setTitle(auth.isSignedIn ? "Disconnect" : "Sign in with GitHub", for: .normal)
            actionButton.isEnabled = auth.isSignedIn
        } else if auth.isSignedIn {
            let who = auth.login.map { "@\($0)" } ?? "your account"
            statusView.render(.connected, service: "GitHub",
                              detail: "Signed in as \(who). Private repositories are available.")
            actionButton.setTitle("Disconnect", for: .normal)
            actionButton.isEnabled = true
        } else {
            statusView.render(.notConnected, service: "GitHub",
                              detail: "Sign in for private repositories. Public repositories work without an account.")
            actionButton.setTitle("Sign in with GitHub", for: .normal)
            actionButton.isEnabled = true
        }
        renderList()
    }

    private func renderList() {
        listStack.arrangedSubviews.forEach { $0.removeFromSuperview() }

        let q = (searchField.text ?? "").trimmingCharacters(in: .whitespaces)
        let pinned = pinnedRepos()
        let pinnedSet = Set(pinned.map { $0.lowercased() })

        // Offer to pin a typed owner/repo that isn't already pinned.
        if let typed = normalizeRepo(q), !pinnedSet.contains(typed.lowercased()) {
            listStack.addArrangedSubview(actionRow(title: "➕  Pin \(typed)", filled: true) { [weak self] in
                self?.pin(typed); self?.searchField.text = ""; self?.searchField.resignFirstResponder(); self?.render()
            })
        }

        // Pinned section (always shown).
        listStack.addArrangedSubview(sectionLabel("Pinned — your keyboard quick-picks"))
        let shownPinned = q.isEmpty ? pinned : pinned.filter { $0.lowercased().contains(q.lowercased()) }
        if shownPinned.isEmpty {
            listStack.addArrangedSubview(mutedLabel(pinned.isEmpty
                ? "Nothing pinned yet. Tap ☆ on a repo below, or type owner/repo above."
                : "No pinned repo matches “\(q)”."))
        } else {
            for repo in shownPinned { listStack.addArrangedSubview(repoRow(fullName: repo, isPrivate: nil, pinned: true)) }
        }

        // Your repos (signed-in), filtered, excluding already-pinned.
        guard auth.isSignedIn else { return }
        listStack.addArrangedSubview(sectionLabel(loadingRepos ? "Loading your repos…" : "Your repos"))
        let shown = allRepos
            .filter { q.isEmpty || $0.fullName.lowercased().contains(q.lowercased()) }
            .filter { !pinnedSet.contains($0.fullName.lowercased()) }
            .prefix(40)
        for repo in shown { listStack.addArrangedSubview(repoRow(fullName: repo.fullName, isPrivate: repo.isPrivate, pinned: false)) }
        if !loadingRepos && shown.isEmpty && !allRepos.isEmpty {
            listStack.addArrangedSubview(mutedLabel(q.isEmpty ? "" : "No repo matches “\(q)”."))
        }
    }

    private func loadRepos() {
        loadingRepos = true
        statusView.render(.loading, service: "GitHub", detail: "Loading your repositories…")
        renderList()
        auth.fetchRepos { [weak self] result in
            guard let self = self else { return }
            self.loadingRepos = false
            switch result {
            case .success(let repos):
                self.allRepos = repos
                let detail = repos.isEmpty ? "Connected, but no repositories were found." : "Connected · \(repos.count) repositories loaded."
                self.statusView.render(.connected, service: "GitHub", detail: detail, canRetry: repos.isEmpty)
                self.renderList()
            case .failure:
                self.statusView.render(.needsAttention, service: "GitHub",
                                       detail: "Couldn’t load repositories. Check your connection and retry.", canRetry: true)
                self.renderList()
            }
        }
    }

    // MARK: - Rows

    private func repoRow(fullName: String, isPrivate: Bool?, pinned: Bool) -> UIView {
        let lock = (isPrivate == true) ? "🔒 " : ""
        return actionRow(title: "\(pinned ? "★" : "☆")   \(lock)\(fullName)", filled: pinned) { [weak self] in
            self?.togglePin(fullName)
        }
    }

    private func actionRow(title: String, filled: Bool, _ tap: @escaping () -> Void) -> UIView {
        let row = UIControl()
        row.backgroundColor = filled ? brandGreen : cardGreen
        row.layer.cornerRadius = 12
        row.layer.cornerCurve = .continuous
        row.layer.borderWidth = filled ? 1 : 0
        row.layer.borderColor = UIColor.separator.cgColor
        row.heightAnchor.constraint(equalToConstant: 46).isActive = true
        let label = UILabel()
        label.text = title
        label.font = .systemFont(ofSize: 14, weight: filled ? .semibold : .regular)
        label.textColor = filled ? .white : .label
        label.translatesAutoresizingMaskIntoConstraints = false
        label.isUserInteractionEnabled = false
        row.addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 14),
            label.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -14),
            label.centerYAnchor.constraint(equalTo: row.centerYAnchor),
        ])
        row.addAction(UIAction { _ in tap() }, for: .touchUpInside)
        return row
    }

    // MARK: - Actions

    @objc private func actionTapped() {
        guard auth.isConfigured else { return }
        if auth.isSignedIn {
            auth.signOut(); allRepos = []; render(); return
        }
        actionButton.isEnabled = false
        actionButton.setTitle("Signing in…", for: .normal)
        statusView.render(.loading, service: "GitHub", detail: "Waiting for GitHub sign-in…")
        auth.signIn { [weak self] result in
            DispatchQueue.main.async {
                guard let self = self else { return }
                switch result {
                case .success:
                    HostPrivacySafeTelemetry.integrationConnected(.github)
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    self.render(); self.loadRepos()
                case .failure:
                    self.statusView.render(.needsAttention, service: "GitHub",
                                           detail: "Couldn’t connect. Check your connection and try again.", canRetry: true)
                    self.actionButton.setTitle("Try sign-in again", for: .normal)
                    self.actionButton.isEnabled = true
                }
            }
        }
    }

    @objc private func searchChanged() { renderList() }
    @objc private func searchSubmit() {
        if let typed = normalizeRepo(searchField.text ?? "") {
            pin(typed); searchField.text = ""; render()
        }
        searchField.resignFirstResponder()
    }
    @objc private func dismissTapped() { dismiss(animated: true) }
    @objc private func networkChanged() { render() }

    // MARK: - Pinned storage

    private func pinnedRepos() -> [String] {
        store.string(forKey: GitHubKeys.pinnedRepos, fallback: "")
            .split(separator: "\n").map(String.init).filter { !$0.isEmpty }
    }
    private func setPinned(_ list: [String]) {
        store.setString(list.joined(separator: "\n"), forKey: GitHubKeys.pinnedRepos)
    }
    private func pin(_ repo: String) {
        var list = pinnedRepos().filter { $0.lowercased() != repo.lowercased() }
        list.insert(repo, at: 0); setPinned(list)
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
    private func togglePin(_ repo: String) {
        var list = pinnedRepos()
        if let i = list.firstIndex(where: { $0.lowercased() == repo.lowercased() }) { list.remove(at: i) }
        else { list.insert(repo, at: 0) }
        setPinned(list); renderList()
    }

    /// `owner/repo`, github.com URL, or full link → `owner/repo`; nil otherwise.
    private func normalizeRepo(_ input: String) -> String? {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        if let r = s.range(of: "github.com/") { s = String(s[r.upperBound...]) }
        s = s.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "")
        let p = s.split(separator: "/").map(String.init)
        guard p.count >= 2, !p[0].isEmpty else { return nil }
        var repo = p[1]; if repo.hasSuffix(".git") { repo = String(repo.dropLast(4)) }
        guard !repo.isEmpty else { return nil }
        return "\(p[0])/\(repo)"
    }

    // MARK: - Styling

    private func capabilitiesText() -> NSAttributedString {
        let s = NSMutableAttributedString()
        let head = NSAttributedString(string: "In the keyboard: ", attributes: [
            .font: UIFont.systemFont(ofSize: 13, weight: .semibold), .foregroundColor: UIColor.label])
        let body = NSAttributedString(string: "type /github → tap a repo → choose what to fetch:\n📊 Overview · 🔨 Commit · 🐛 Issues · 🔀 PRs · 🏷️ Release. Or type owner/repo#42 for a specific issue or PR.", attributes: [
            .font: UIFont.systemFont(ofSize: 13), .foregroundColor: UIColor.secondaryLabel])
        s.append(head); s.append(body)
        return s
    }

    private func sectionLabel(_ text: String) -> UILabel {
        let l = UILabel(); l.text = text
        l.font = .systemFont(ofSize: 13, weight: .semibold); l.textColor = .label; l.numberOfLines = 0
        return l
    }
    private func mutedLabel(_ text: String) -> UILabel {
        let l = UILabel(); l.text = text
        l.font = .systemFont(ofSize: 13); l.textColor = .secondaryLabel; l.numberOfLines = 0
        return l
    }
    private func styleField(_ field: UITextField) {
        field.font = .systemFont(ofSize: 15)
        field.backgroundColor = .secondarySystemGroupedBackground
        field.textColor = .label
        field.textColor = .label
        field.autocapitalizationType = .none
        field.autocorrectionType = .no
        field.clearButtonMode = .whileEditing
        field.layer.cornerRadius = 10
        field.layer.cornerCurve = .continuous
        field.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 12, height: 0))
        field.leftViewMode = .always
        field.heightAnchor.constraint(equalToConstant: 44).isActive = true
        if let ph = field.placeholder {
            field.attributedPlaceholder = NSAttributedString(
                string: ph, attributes: [.foregroundColor: UIColor(white: 0.42, alpha: 1.0)])
        }
    }
    private func styleSolidButton(_ btn: UIButton, title: String, action: Selector) {
        btn.setTitle(title, for: .normal)
        btn.setTitleColor(.white, for: .normal)
        btn.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        btn.backgroundColor = .systemGreen
        btn.layer.cornerRadius = 10
        btn.layer.cornerCurve = .continuous
        btn.contentEdgeInsets = UIEdgeInsets(top: 10, left: 16, bottom: 10, right: 16)
        btn.addTarget(self, action: action, for: .touchUpInside)
        btn.heightAnchor.constraint(equalToConstant: 44).isActive = true
    }
    private func showAlert(title: String, message: String) {
        let a = UIAlertController(title: title, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default)); present(a, animated: true)
    }
}
