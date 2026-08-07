import UIKit

/// Host-app deep screen for Split. The keyboard's `Report ↗` link in
/// `/splits` URL-launches `turtlekeyboard://split-detail` which AppDelegate
/// routes here. Shows totals + the full saved-history list with more space
/// than the in-keyboard panel can offer.
///
/// Reads `SplitHistory` via the shared `SplitStore`. Until the App Group
/// entitlement is wired (`group.com.samarth.turtlekeyboard.split`), the host app
/// and keyboard each have their own `UserDefaults.standard` so this screen
/// will show only entries saved from inside the host app — the keyboard's
/// saves stay invisible. Adding the App Group capability in both targets
/// flips it to a true shared store with no code changes.
final class SplitDetailViewController: UIViewController {

    private let brandGreen = UIColor.systemGreen
    private let cardGreen  = UIColor.secondarySystemGroupedBackground

    private let store: SplitStore = UserDefaultsSplitStore(suiteName: SplitContract.storageSuiteName)
    private lazy var history = SplitHistory(store: store)
    private lazy var oauth = SplitOAuth(store: store, presentationAnchor: view.window)
    private lazy var sync = SplitCloudSync(store: store, oauth: oauth)

    private let scroll = UIScrollView()
    private let content = UIStackView()
    private let totalLabel = UILabel()
    private let subtotalLabel = UILabel()
    private let emptyLabel = UILabel()
    private let cloudCard = UIView()
    private let cloudStatusLabel = UILabel()
    private let cloudActionButton = UIButton(type: .system)
    private let inviteButton = UIButton(type: .system)
    private let syncButton = UIButton(type: .system)
    private let clearMineButton = UIButton(type: .system)
    private let clearAllButton = UIButton(type: .system)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        title = "Splits"
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Done", style: .done, target: self, action: #selector(dismissTapped))

        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        content.axis = .vertical
        content.spacing = 10
        content.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(content)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            content.topAnchor.constraint(equalTo: scroll.topAnchor, constant: 16),
            content.leadingAnchor.constraint(equalTo: scroll.leadingAnchor, constant: 16),
            content.trailingAnchor.constraint(equalTo: scroll.trailingAnchor, constant: -16),
            content.bottomAnchor.constraint(equalTo: scroll.bottomAnchor, constant: -16),
            content.widthAnchor.constraint(equalTo: scroll.widthAnchor, constant: -32),
        ])

        content.addArrangedSubview(buildTotalsCard())
        content.addArrangedSubview(buildCloudCard())
        emptyLabel.text = "No splits saved yet.\nUse /split <amount> in any chat to save your first one."
        emptyLabel.font = .systemFont(ofSize: 15)
        emptyLabel.textColor = .secondaryLabel
        emptyLabel.numberOfLines = 0
        emptyLabel.textAlignment = .center
        content.addArrangedSubview(emptyLabel)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reload()
        refreshCloudUI()
    }

    private func buildTotalsCard() -> UIView {
        let card = UIView()
        card.backgroundColor = cardGreen
        card.layer.cornerRadius = 12
        card.layer.cornerCurve = .continuous
        card.translatesAutoresizingMaskIntoConstraints = false

        totalLabel.font = .systemFont(ofSize: 28, weight: .bold)
        totalLabel.textColor = .label
        subtotalLabel.font = .systemFont(ofSize: 13)
        subtotalLabel.textColor = .secondaryLabel

        let stack = UIStackView(arrangedSubviews: [totalLabel, subtotalLabel])
        stack.axis = .vertical
        stack.spacing = 4
        stack.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: card.topAnchor, constant: 14),
            stack.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -14),
        ])
        return card
    }

    private func reload() {
        let entries = history.all()
        let total = entries.reduce(0.0) { $0 + $1.amount }
        totalLabel.text = "₹\(SplitContract.formatAmount(total))"
        subtotalLabel.text = "Across \(entries.count) split\(entries.count == 1 ? "" : "s")"
        emptyLabel.isHidden = !entries.isEmpty

        // Drop existing rows (keep totals card + cloud card + empty label = first 3)
        while content.arrangedSubviews.count > 3 {
            let v = content.arrangedSubviews[3]
            content.removeArrangedSubview(v)
            v.removeFromSuperview()
        }
        for entry in entries {
            content.addArrangedSubview(buildRow(entry: entry))
        }
    }

    private func buildRow(entry: SplitHistory.Entry) -> UIView {
        let card = UIView()
        card.backgroundColor = cardGreen
        card.layer.cornerRadius = 12
        card.layer.cornerCurve = .continuous
        card.translatesAutoresizingMaskIntoConstraints = false

        let amount = UILabel()
        amount.text = "₹\(SplitContract.formatAmount(entry.amount))"
        amount.font = .systemFont(ofSize: 18, weight: .bold)
        amount.textColor = .label

        let per = entry.people > 0 ? entry.amount / Double(entry.people) : entry.amount
        let date = Date(timeIntervalSince1970: TimeInterval(entry.timestampMs) / 1000)
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short

        let meta = UILabel()
        meta.text = "\(entry.people) \(entry.people == 1 ? "person" : "people") · ₹\(SplitContract.formatAmount(per)) each\n\(formatter.string(from: date))"
        meta.numberOfLines = 0
        meta.font = .systemFont(ofSize: 13)
        meta.textColor = .secondaryLabel

        let copyBtn = UIButton(type: .system)
        styleSecondaryButton(copyBtn, title: "Copy")
        copyBtn.addAction(UIAction { [weak self] _ in
            guard let self = self else { return }
            UIPasteboard.general.string = self.summary(for: entry)
            self.flashToast("Copied")
        }, for: .touchUpInside)

        let shareBtn = UIButton(type: .system)
        styleSecondaryButton(shareBtn, title: "Share")
        shareBtn.addAction(UIAction { [weak self] _ in
            guard let self = self else { return }
            let activity = UIActivityViewController(
                activityItems: [self.summary(for: entry)],
                applicationActivities: nil)
            activity.popoverPresentationController?.sourceView = shareBtn
            self.present(activity, animated: true)
        }, for: .touchUpInside)

        let actionRow = UIStackView(arrangedSubviews: [copyBtn, shareBtn, UIView()])
        actionRow.axis = .horizontal
        actionRow.spacing = 8

        let stack = UIStackView(arrangedSubviews: [amount, meta, actionRow])
        stack.axis = .vertical
        stack.spacing = 8
        stack.setCustomSpacing(3, after: amount)
        stack.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -14),
            stack.topAnchor.constraint(equalTo: card.topAnchor, constant: 12),
            stack.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -12),
        ])
        return card
    }

    /// Plain-text summary suitable for copy/share. Mirrors Android's
    /// `SplitActivity.summary` (lines 761-766).
    private func summary(for entry: SplitHistory.Entry) -> String {
        let per = entry.people > 0 ? entry.amount / Double(entry.people) : entry.amount
        let unit = entry.people == 1 ? "person" : "people"
        return "Splitting ₹\(SplitContract.formatAmount(entry.amount)) between "
            + "\(entry.people) \(unit) — ₹\(SplitContract.formatAmount(per)) each."
    }

    /// Small transient toast for clipboard feedback. Mirrors Android's
    /// `Toast.makeText(...).show()` style.
    private func flashToast(_ text: String) {
        let container = UIView()
        container.backgroundColor = UIColor.black.withAlphaComponent(0.78)
        container.layer.cornerRadius = 14
        container.layer.masksToBounds = true
        container.alpha = 0
        container.translatesAutoresizingMaskIntoConstraints = false

        let label = UILabel()
        label.text = text
        label.font = .systemFont(ofSize: 13, weight: .semibold)
        label.textColor = .white
        label.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(label)

        view.addSubview(container)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 16),
            label.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -16),
            label.topAnchor.constraint(equalTo: container.topAnchor, constant: 8),
            label.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -8),
            container.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            container.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -40),
        ])
        UIView.animate(withDuration: 0.18, animations: { container.alpha = 1 }) { _ in
            UIView.animate(withDuration: 0.18, delay: 1.2, options: [],
                           animations: { container.alpha = 0 },
                           completion: { _ in container.removeFromSuperview() })
        }
    }

    @objc private func dismissTapped() {
        dismiss(animated: true)
    }

    // MARK: - Cloud sync UI

    private func buildCloudCard() -> UIView {
        cloudCard.backgroundColor = cardGreen
        cloudCard.layer.cornerRadius = 12
        cloudCard.layer.cornerCurve = .continuous
        cloudCard.translatesAutoresizingMaskIntoConstraints = false

        cloudStatusLabel.font = .systemFont(ofSize: 14)
        cloudStatusLabel.textColor = .label
        cloudStatusLabel.numberOfLines = 0

        cloudActionButton.setTitleColor(.white, for: .normal)
        cloudActionButton.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        cloudActionButton.backgroundColor = .systemGreen
        cloudActionButton.layer.cornerRadius = 10
        cloudActionButton.layer.cornerCurve = .continuous
        cloudActionButton.contentEdgeInsets = UIEdgeInsets(top: 8, left: 14, bottom: 8, right: 14)
        cloudActionButton.addTarget(self, action: #selector(cloudActionTapped), for: .touchUpInside)

        syncButton.setTitle("Sync now", for: .normal)
        syncButton.setTitleColor(.systemGreen, for: .normal)
        syncButton.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        syncButton.backgroundColor = .tertiarySystemFill
        syncButton.layer.cornerRadius = 10
        syncButton.layer.cornerCurve = .continuous
        syncButton.contentEdgeInsets = UIEdgeInsets(top: 8, left: 14, bottom: 8, right: 14)
        syncButton.addTarget(self, action: #selector(syncTapped), for: .touchUpInside)

        inviteButton.setTitleColor(.systemGreen, for: .normal)
        inviteButton.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        inviteButton.backgroundColor = .tertiarySystemFill
        inviteButton.layer.cornerRadius = 10
        inviteButton.layer.cornerCurve = .continuous
        inviteButton.contentEdgeInsets = UIEdgeInsets(top: 8, left: 14, bottom: 8, right: 14)
        inviteButton.addTarget(self, action: #selector(inviteTapped), for: .touchUpInside)

        let buttonRow = UIStackView(arrangedSubviews: [cloudActionButton, syncButton, inviteButton])
        buttonRow.axis = .horizontal
        buttonRow.spacing = 8
        buttonRow.distribution = .fillEqually

        // Destructive actions live on their own row so they read as a
        // separate group from sign-in / sync / invite. Mirrors Android's
        // `SplitActivity` clearRow (lines 259-296).
        styleSecondaryButton(clearMineButton, title: "Clear my rows")
        clearMineButton.addTarget(self, action: #selector(clearMineTapped), for: .touchUpInside)
        styleSecondaryButton(clearAllButton, title: "Clear all")
        clearAllButton.addTarget(self, action: #selector(clearAllTapped), for: .touchUpInside)
        let clearRow = UIStackView(arrangedSubviews: [clearMineButton, clearAllButton])
        clearRow.axis = .horizontal
        clearRow.spacing = 8
        clearRow.distribution = .fillEqually

        let stack = UIStackView(arrangedSubviews: [cloudStatusLabel, buttonRow, clearRow])
        stack.axis = .vertical
        stack.spacing = 10
        stack.translatesAutoresizingMaskIntoConstraints = false
        cloudCard.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: cloudCard.leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(equalTo: cloudCard.trailingAnchor, constant: -14),
            stack.topAnchor.constraint(equalTo: cloudCard.topAnchor, constant: 12),
            stack.bottomAnchor.constraint(equalTo: cloudCard.bottomAnchor, constant: -12),
        ])
        return cloudCard
    }

    private func refreshCloudUI() {
        if !oauth.isConfigured {
            cloudStatusLabel.text = "Cloud sync not configured.\nFill in OAuth client ID — see OAUTH_SETUP_iOS.md."
            cloudActionButton.setTitle("How to set up", for: .normal)
            syncButton.isHidden = true
            inviteButton.isHidden = true
            clearMineButton.isHidden = true
            clearAllButton.isHidden = true
            return
        }
        let hasEntries = !history.all().isEmpty
        if oauth.isSignedIn {
            let email = oauth.accountEmail ?? "signed in"
            let role = sync.isOwner ? " · owner" : (sync.isMembershipOpen ? " · sharing" : "")
            cloudStatusLabel.text = "Signed in as \(email)\(role)"
            cloudActionButton.setTitle("Sign out", for: .normal)
            syncButton.isHidden = false
            inviteButton.isHidden = !sync.isOwner
            // Always offers the share/QR modal — "Stop sharing" lives as a
            // secondary action inside that modal now, so users can re-open
            // the QR after dismissing it without accidentally toggling off.
            inviteButton.setTitle(sync.isMembershipOpen ? "Share QR" : "Invite", for: .normal)
            clearMineButton.isHidden = !hasEntries
            clearAllButton.isHidden = !(sync.isOwner && hasEntries)
        } else {
            cloudStatusLabel.text = "Sign in with Google to back up your splits to your own private spreadsheet."
            cloudActionButton.setTitle("Sign in", for: .normal)
            syncButton.isHidden = true
            inviteButton.isHidden = true
            // Local-only mode: still let the user wipe local rows.
            clearMineButton.isHidden = !hasEntries
            clearAllButton.isHidden = true
        }
    }

    /// Pill styling that matches `syncButton` / `inviteButton`.
    private func styleSecondaryButton(_ button: UIButton, title: String) {
        button.setTitle(title, for: .normal)
        button.setTitleColor(.systemGreen, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        button.backgroundColor = .tertiarySystemFill
        button.layer.cornerRadius = 10
        button.layer.cornerCurve = .continuous
        button.contentEdgeInsets = UIEdgeInsets(top: 8, left: 14, bottom: 8, right: 14)
    }

    @objc private func clearMineTapped() {
        let alert = UIAlertController(
            title: "Clear your rows?",
            message: "Removes only the splits this device added. Other members' rows stay intact. This can't be undone.",
            preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Clear", style: .destructive) { [weak self] _ in
            guard let self = self else { return }
            self.history.clear()
            self.sync.pushClear()
            self.reload()
            self.refreshCloudUI()
        })
        present(alert, animated: true)
    }

    @objc private func clearAllTapped() {
        let alert = UIAlertController(
            title: "Clear everyone's rows?",
            message: "This wipes the entire shared history for every member. Only the owner can do this. This can't be undone.",
            preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Clear all", style: .destructive) { [weak self] _ in
            guard let self = self else { return }
            self.history.clear()
            self.sync.pushClearAll()
            self.reload()
            self.refreshCloudUI()
        })
        present(alert, animated: true)
    }

    @objc private func cloudActionTapped() {
        if !oauth.isConfigured {
            let alert = UIAlertController(
                title: "Cloud sync not configured",
                message: "Open OAUTH_SETUP_iOS.md in the repo, follow the steps to create a Google Cloud OAuth client, then paste the client ID into SplitOAuth.swift and Info.plist.",
                preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            present(alert, animated: true)
            return
        }
        if oauth.isSignedIn {
            oauth.signOut()
            refreshCloudUI()
            return
        }
        cloudActionButton.isEnabled = false
        cloudActionButton.setTitle("Signing in…", for: .normal)
        oauth.signIn { [weak self] result in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.cloudActionButton.isEnabled = true
                switch result {
                case .success:
                    Task { @MainActor in
                        _ = await self.sync.ensureSheet()
                        _ = await self.sync.fetchAndMerge()
                        self.reload()
                        self.refreshCloudUI()
                    }
                case .failure(let error):
                    self.refreshCloudUI()
                    self.showAlert(title: "Sign-in failed", message: error.localizedDescription)
                }
            }
        }
    }

    @objc private func syncTapped() {
        syncButton.isEnabled = false
        syncButton.setTitle("Syncing…", for: .normal)
        Task { @MainActor in
            _ = await sync.ensureSheet()
            _ = await sync.fetchAndMerge()
            syncButton.isEnabled = true
            syncButton.setTitle("Sync now", for: .normal)
            reload()
            refreshCloudUI()
        }
    }

    @objc private func inviteTapped() {
        presentShareFlow()
    }

    /// Idempotent entry point for the share/QR flow. Handles every state:
    ///   • OAuth not configured → config alert.
    ///   • Not signed in       → kick off sign-in, retry on success.
    ///   • Signed in as joiner → "only owner can share" alert.
    ///   • Owner + closed     → openMembership → showInviteQr.
    ///   • Owner + open        → buildJoinDeepLink → showInviteQr
    ///                            (no toggle-off — that's a button inside
    ///                            the modal now).
    private func presentShareFlow() {
        if !oauth.isConfigured {
            showAlert(title: "Cloud sync not configured",
                      message: "Open OAUTH_SETUP_iOS.md, create a Google OAuth client, then paste the client ID into .env.")
            return
        }
        if !oauth.isSignedIn {
            oauth.signIn { [weak self] result in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    switch result {
                    case .success:
                        Task { @MainActor in
                            _ = await self.sync.ensureSheet()
                            self.refreshCloudUI()
                            self.presentShareFlow()
                        }
                    case .failure(let e):
                        self.showAlert(title: "Sign-in failed", message: e.localizedDescription)
                    }
                }
            }
            return
        }
        guard sync.isOwner else {
            showAlert(title: "Only the owner can share",
                      message: "This sheet belongs to someone else. Sign in with the account that originally created it to share the QR.")
            return
        }
        if sync.isMembershipOpen {
            // Already sharing — just re-show the QR, don't toggle.
            showInviteQr(deepLink: sync.buildJoinDeepLink())
            return
        }
        Task { @MainActor in
            guard let link = await sync.openMembership() else {
                showAlert(title: "Invite failed",
                          message: "Could not enable sharing. Check your connection and try again.")
                return
            }
            refreshCloudUI()
            showInviteQr(deepLink: link)
        }
    }

    /// Owner-only: shows the scan-to-join QR with Share / Copy actions.
    /// Mirrors Android's `SplitActivity.showInviteQr`.
    private func showInviteQr(deepLink: String) {
        let sheet = UIViewController()
        sheet.modalPresentationStyle = .formSheet
        sheet.view.backgroundColor = .systemBackground

        let title = UILabel()
        title.text = "Scan to join"
        title.font = .systemFont(ofSize: 18, weight: .bold)
        title.textAlignment = .center

        let sub = UILabel()
        sub.text = "Anyone scanning this QR with their phone camera will be added as a writer. Tap \"Stop sharing\" when you're done."
        sub.font = .systemFont(ofSize: 12)
        sub.textColor = .secondaryLabel
        sub.textAlignment = .center
        sub.numberOfLines = 0

        let qrView = UIImageView(image: QrRenderer.render(deepLink, size: 240))
        qrView.contentMode = .scaleAspectFit
        qrView.layer.magnificationFilter = .nearest
        qrView.translatesAutoresizingMaskIntoConstraints = false

        let link = UILabel()
        link.text = deepLink
        link.font = .systemFont(ofSize: 11)
        link.textColor = .secondaryLabel
        link.textAlignment = .center
        link.numberOfLines = 2
        link.lineBreakMode = .byTruncatingMiddle

        let shareBtn = UIButton(type: .system)
        shareBtn.setTitle("Share link", for: .normal)
        shareBtn.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        shareBtn.addAction(UIAction { [weak self, weak sheet] _ in
            guard let self = self, let sheet = sheet else { return }
            let activity = UIActivityViewController(activityItems: [deepLink], applicationActivities: nil)
            activity.popoverPresentationController?.sourceView = sheet.view
            sheet.present(activity, animated: true)
            _ = self // silence weakself unused
        }, for: .touchUpInside)

        let copyBtn = UIButton(type: .system)
        copyBtn.setTitle("Copy", for: .normal)
        copyBtn.titleLabel?.font = .systemFont(ofSize: 15)
        copyBtn.addAction(UIAction { _ in
            UIPasteboard.general.string = deepLink
        }, for: .touchUpInside)

        let doneBtn = UIButton(type: .system)
        doneBtn.setTitle("Done", for: .normal)
        doneBtn.titleLabel?.font = .systemFont(ofSize: 15)
        doneBtn.addAction(UIAction { [weak sheet] _ in
            sheet?.dismiss(animated: true)
        }, for: .touchUpInside)

        let buttonRow = UIStackView(arrangedSubviews: [shareBtn, copyBtn, doneBtn])
        buttonRow.axis = .horizontal
        buttonRow.distribution = .fillEqually

        // Secondary, destructive action — stop sharing without dismissing
        // the modal first. Visible only while membership is open.
        let stopBtn = UIButton(type: .system)
        stopBtn.setTitle("Stop sharing", for: .normal)
        stopBtn.titleLabel?.font = .systemFont(ofSize: 13)
        stopBtn.setTitleColor(.systemRed, for: .normal)
        stopBtn.addAction(UIAction { [weak self, weak sheet] _ in
            guard let self = self, let sheet = sheet else { return }
            Task { @MainActor in
                _ = await self.sync.closeMembership()
                self.refreshCloudUI()
                sheet.dismiss(animated: true)
            }
        }, for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [title, sub, qrView, link, buttonRow, stopBtn])
        stack.axis = .vertical
        stack.spacing = 12
        stack.alignment = .fill
        stack.translatesAutoresizingMaskIntoConstraints = false
        sheet.view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: sheet.view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: sheet.view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            stack.centerYAnchor.constraint(equalTo: sheet.view.safeAreaLayoutGuide.centerYAnchor),
            qrView.heightAnchor.constraint(equalToConstant: 240),
        ])

        present(sheet, animated: true)
    }

    private func showAlert(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }
}
