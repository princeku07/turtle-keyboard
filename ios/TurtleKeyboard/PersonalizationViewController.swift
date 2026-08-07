import UIKit

/// Per-integration personalization. iOS keyboard extensions can't detect
/// the host app, so Android's per-package enroll/suppress model doesn't
/// translate. Instead we surface the knobs that *do* apply on iOS:
///
///   - Each integration (Split / Notion / Slack) can be toggled off so its
///     commands disappear from the keyboard's slash-command vocabulary
///     and Quick Panel grid.
///   - Connect status + a deep link into the per-integration Connect
///     screen for OAuth and default-channel/parent picking.
///   - Keyboard-wide toggles: Quick Panel double-tap-space, voice mic key.
///
/// Storage uses the same shared `UserDefaultsSplitStore` everything else
/// reads from. Keys are namespaced under `personalization.*` and read by
/// the keyboard extension at command-registration time.
final class PersonalizationViewController: UIViewController {

    private let brandGreen = UIColor.systemGreen
    private let cardGreen  = UIColor.secondarySystemGroupedBackground

    private let store: SplitStore = UserDefaultsSplitStore(suiteName: SplitContract.storageSuiteName)
    private lazy var notionAuth = NotionAuth(store: store, presentationAnchor: view.window)
    private lazy var slackAuth  = SlackAuth(store: store, presentationAnchor: view.window)

    private let stack = UIStackView()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        title = "Personalization"
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Done", style: .done, target: self, action: #selector(dismissTapped))

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .vertical
        stack.spacing = 16
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
        rebuild()
    }

    private func rebuild() {
        stack.arrangedSubviews.forEach { $0.removeFromSuperview() }

        stack.addArrangedSubview(subtitle(
            "iOS keyboards can't detect the app you're typing in, so Turtle's personalization works per-integration rather than per-app. Toggles take effect the next time you switch to Turtle Keyboard."))

        stack.addArrangedSubview(sectionHeader("AI"))
        stack.addArrangedSubview(inferenceModeCard())

        stack.addArrangedSubview(sectionHeader("Integrations"))
        stack.addArrangedSubview(integrationCard(
            title: "💸 Split",
            subtitle: splitSubtitle(),
            enabledKey: PersonalizationKeys.splitEnabled,
            connectScreen: nil,
            connectTitle: nil))
        stack.addArrangedSubview(integrationCard(
            title: "📓 Notion",
            subtitle: notionAuth.isSignedIn ? "Connected to \(notionAuth.workspaceName ?? "Notion")" : "Not signed in",
            enabledKey: PersonalizationKeys.notionEnabled,
            connectScreen: NotionConnectViewController(),
            connectTitle: notionAuth.isSignedIn ? "Manage" : "Connect"))
        stack.addArrangedSubview(integrationCard(
            title: "💬 Slack",
            subtitle: slackAuth.isSignedIn ? "Connected to \(slackAuth.teamName ?? "Slack")" : "Not signed in",
            enabledKey: PersonalizationKeys.slackEnabled,
            connectScreen: SlackConnectViewController(),
            connectTitle: slackAuth.isSignedIn ? "Manage" : "Connect"))

        stack.addArrangedSubview(sectionHeader("Keyboard"))
        stack.addArrangedSubview(toggleCard(
            title: "Quick Panel",
            subtitle: "Double-tap space inside the keyboard to open a tap-driven grid of every slash command.",
            enabledKey: PersonalizationKeys.quickPanelEnabled))
        stack.addArrangedSubview(toggleCard(
            title: "Voice mic key",
            subtitle: "Show the 🎙 button in the slash-command bar so you can dictate prompts.",
            enabledKey: PersonalizationKeys.voiceEnabled))
        stack.addArrangedSubview(themePickerCard())
    }

    // MARK: - Inference mode picker

    /// Where AI commands are allowed to run. Written straight into the shared
    /// store the keyboard's `CommandRouter` reads before every command, so a
    /// change here takes effect on the next command — no keyboard remount.
    private func inferenceModeCard() -> UIView {
        let card = makeCard()

        let title = UILabel()
        title.text = "Where AI runs"
        title.font = .systemFont(ofSize: 16, weight: .semibold)
        title.textColor = .label

        let order: [InferenceMode] = [.auto, .onDeviceOnly, .cloudOnly]
        let current = InferenceMode.current(store: store)

        let blurb = subtitle(current.blurb)

        let picker = UISegmentedControl(items: order.map(\.title))
        picker.selectedSegmentTintColor = .secondarySystemGroupedBackground
        picker.selectedSegmentIndex = order.firstIndex(of: current) ?? 0
        picker.addAction(UIAction { [weak self] _ in
            guard let self = self else { return }
            let pick = order[picker.selectedSegmentIndex]
            InferenceMode.set(pick, store: self.store)
            blurb.text = pick.blurb
        }, for: .valueChanged)

        let status = UILabel()
        status.font = .systemFont(ofSize: 12, weight: .medium)
        status.numberOfLines = 0
        switch OnDeviceModel.availability {
        case .available:
            status.text = "✅ Apple Intelligence is ready — text commands run free on-device."
            status.textColor = .systemGreen
        case .unavailable(let why):
            status.text = "⚠️ \(why). Text commands will use the cloud."
            status.textColor = .systemOrange
        }

        let inner = UIStackView(arrangedSubviews: [title, blurb, picker, status])
        inner.axis = .vertical
        inner.spacing = 8
        inner.translatesAutoresizingMaskIntoConstraints = false
        embed(inner, in: card)
        return card
    }

    // MARK: - Theme picker

    private func themePickerCard() -> UIView {
        let card = makeCard()

        let title = UILabel()
        title.text = "Theme"
        title.font = .systemFont(ofSize: 16, weight: .semibold)
        title.textColor = .label

        let sub = subtitle("Auto follows the system Dark Mode setting. Turtle is the brand green look.")

        let order: [ThemePreference] = [.auto, .turtle, .light, .dark]
        let labels = order.map { pref -> String in
            switch pref {
            case .auto:   return "Auto"
            case .turtle: return "Turtle"
            case .light:  return "Light"
            case .dark:   return "Dark"
            }
        }
        let picker = UISegmentedControl(items: labels)
        picker.selectedSegmentTintColor = .secondarySystemGroupedBackground

        let current = KeyboardThemeManager.shared.preference(store: store)
        picker.selectedSegmentIndex = order.firstIndex(of: current) ?? 1

        picker.addAction(UIAction { [weak self] _ in
            guard let self = self else { return }
            let pick = order[picker.selectedSegmentIndex]
            KeyboardThemeManager.shared.setPreference(pick, store: self.store)
        }, for: .valueChanged)

        let inner = UIStackView(arrangedSubviews: [title, sub, picker])
        inner.axis = .vertical
        inner.spacing = 8
        inner.translatesAutoresizingMaskIntoConstraints = false
        embed(inner, in: card)
        return card
    }

    // MARK: - Builders

    private func integrationCard(
        title: String,
        subtitle: String,
        enabledKey: String,
        connectScreen: UIViewController?,
        connectTitle: String?
    ) -> UIView {
        let card = makeCard()

        let header = UIStackView()
        header.axis = .horizontal
        header.alignment = .center

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .systemFont(ofSize: 16, weight: .semibold)
        titleLabel.textColor = .label
        titleLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)

        let toggle = UISwitch()
        toggle.isOn = isEnabled(enabledKey)
        toggle.onTintColor = .systemGreen
        toggle.addAction(UIAction { [weak self] _ in
            self?.store.setInt(toggle.isOn ? 1 : 0, forKey: enabledKey)
        }, for: .valueChanged)

        header.addArrangedSubview(titleLabel)
        header.addArrangedSubview(toggle)

        let sub = UILabel()
        sub.text = subtitle
        sub.font = .systemFont(ofSize: 13)
        sub.textColor = .secondaryLabel
        sub.numberOfLines = 0

        let inner = UIStackView(arrangedSubviews: [header, sub])
        inner.axis = .vertical
        inner.spacing = 6
        inner.translatesAutoresizingMaskIntoConstraints = false

        if let screen = connectScreen, let connectTitle = connectTitle {
            let connect = UIButton(type: .system)
            connect.setTitle(connectTitle, for: .normal)
            connect.setTitleColor(.white, for: .normal)
            connect.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
            connect.backgroundColor = .systemGreen
            connect.layer.cornerRadius = 10
            connect.layer.cornerCurve = .continuous
            connect.contentEdgeInsets = UIEdgeInsets(top: 8, left: 14, bottom: 8, right: 14)
            connect.addAction(UIAction { [weak self] _ in
                guard let nav = self?.navigationController else { return }
                nav.pushViewController(screen, animated: true)
            }, for: .touchUpInside)
            inner.addArrangedSubview(connect)
        }

        embed(inner, in: card)
        return card
    }

    private func toggleCard(title: String, subtitle: String, enabledKey: String) -> UIView {
        let card = makeCard()

        let header = UIStackView()
        header.axis = .horizontal
        header.alignment = .center

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .systemFont(ofSize: 16, weight: .semibold)
        titleLabel.textColor = .label
        titleLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)

        let toggle = UISwitch()
        toggle.isOn = isEnabled(enabledKey)
        toggle.onTintColor = .systemGreen
        toggle.addAction(UIAction { [weak self] _ in
            self?.store.setInt(toggle.isOn ? 1 : 0, forKey: enabledKey)
        }, for: .valueChanged)

        header.addArrangedSubview(titleLabel)
        header.addArrangedSubview(toggle)

        let sub = UILabel()
        sub.text = subtitle
        sub.font = .systemFont(ofSize: 13)
        sub.textColor = .secondaryLabel
        sub.numberOfLines = 0

        let inner = UIStackView(arrangedSubviews: [header, sub])
        inner.axis = .vertical
        inner.spacing = 6
        inner.translatesAutoresizingMaskIntoConstraints = false

        embed(inner, in: card)
        return card
    }

    private func makeCard() -> UIView {
        let card = UIView()
        card.backgroundColor = cardGreen
        card.layer.cornerRadius = 12
        card.layer.cornerCurve = .continuous
        card.translatesAutoresizingMaskIntoConstraints = false
        return card
    }

    private func embed(_ inner: UIView, in card: UIView) {
        card.addSubview(inner)
        NSLayoutConstraint.activate([
            inner.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 14),
            inner.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -14),
            inner.topAnchor.constraint(equalTo: card.topAnchor, constant: 12),
            inner.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -12),
        ])
    }

    private func subtitle(_ text: String) -> UILabel {
        let l = UILabel()
        l.text = text
        l.font = .systemFont(ofSize: 13)
        l.textColor = .secondaryLabel
        l.numberOfLines = 0
        return l
    }

    private func sectionHeader(_ text: String) -> UILabel {
        let l = UILabel()
        l.text = text
        l.font = .systemFont(ofSize: 12, weight: .semibold)
        l.textColor = .secondaryLabel
        return l
    }

    private func splitSubtitle() -> String {
        let savedSplits = SplitHistory(store: store).all().count
        return savedSplits == 0
            ? "Use /split <amount> in any chat to save splits."
            : "\(savedSplits) saved split\(savedSplits == 1 ? "" : "s")."
    }

    private func isEnabled(_ key: String) -> Bool {
        store.int(forKey: key, fallback: 1) != 0
    }

    @objc private func dismissTapped() { dismiss(animated: true) }
}
