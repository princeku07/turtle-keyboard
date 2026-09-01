import UIKit
import Network

/// Host-side writer for the same bounded, content-free event queue used by
/// the keyboard extension. Only fixed event and integration names are
/// accepted; there is no API for attaching user-entered strings.
enum HostPrivacySafeTelemetry {
    enum Integration: String { case google, notion, slack, github }

    private struct Event: Codable {
        let name: String
        let timestamp: TimeInterval
        let category: String?
        let durationMs: Int?
    }

    private static let key = "telemetry.pending.v1"
    private static let lock = NSLock()
    private static var store: UserDefaults {
        UserDefaults(suiteName: "group.com.samarth.turtlekeyboard.split") ?? .standard
    }

    static func onboardingStarted() { append("onboardingStarted") }
    static func onboardingCompleted() { append("onboardingCompleted") }
    static func settingsOpened() { append("settingsOpened") }
    static func integrationConnected(_ value: Integration) { append("integrationConnected", category: value.rawValue) }

    private static func append(_ name: String, category: String? = nil) {
        lock.lock(); defer { lock.unlock() }
        var events: [Event] = []
        if let data = store.data(forKey: key) {
            events = (try? JSONDecoder().decode([Event].self, from: data)) ?? []
        }
        events.append(Event(name: name, timestamp: Date().timeIntervalSince1970,
                            category: category, durationMs: nil))
        if let data = try? JSONEncoder().encode(Array(events.suffix(200))) {
            store.set(data, forKey: key)
        }
    }
}


/// The host app is a companion to the keyboard, so its home screen follows the
/// same structure as Apple's Settings apps: setup first, then the things a
/// person can manage. The keyboard itself is intentionally not touched here.
final class ViewController: UITableViewController {

    private enum Section: Int, CaseIterable {
        case setup
        case explore
        case keyboard
        case privacy
        case support
        case connections
    }

    private enum Destination: CaseIterable {
        case personalize
        case playground
        case splits
        case history
        case privacy
        case help
        case github
        case notion
        case slack

        var title: String {
            switch self {
            case .personalize: return "Keyboard settings"
            case .playground:  return "Try Turtle"
            case .splits:      return "Saved splits"
            case .history:     return "Image history"
            case .privacy:     return "Privacy & Data"
            case .help:        return "Help & Troubleshooting"
            case .github:      return "GitHub"
            case .notion:      return "Notion"
            case .slack:       return "Slack"
            }
        }

        var subtitle: String {
            switch self {
            case .personalize: return "Commands, voice, AI, and appearance"
            case .playground:  return "Experience slash commands in this app"
            case .splits:      return "View and share expenses"
            case .history:     return "Find images created with Turtle"
            case .privacy:     return "See what stays private and manage your data"
            case .help:        return "Setup fixes, support, and problem reporting"
            case .github:      return "Connect your GitHub account"
            case .notion:      return "Send notes to your workspace"
            case .slack:       return "Send messages from the keyboard"
            }
        }

        var symbol: String {
            switch self {
            case .personalize: return "keyboard"
            case .playground:  return "wand.and.stars"
            case .splits:      return "person.2"
            case .history:     return "photo.on.rectangle.angled"
            case .privacy:     return "hand.raised"
            case .help:        return "questionmark.circle"
            case .github:      return "chevron.left.forwardslash.chevron.right"
            case .notion:      return "doc.text"
            case .slack:       return "number"
            }
        }
    }

    private let keyboardRows: [Destination] = [.personalize, .splits, .history]
    private let connectionRows: [Destination] = [.github, .notion, .slack]

    init() {
        super.init(style: .insetGrouped)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        title = "Turtle"
        view.backgroundColor = .systemGroupedBackground
        tableView.backgroundColor = .systemGroupedBackground
        tableView.separatorInset = UIEdgeInsets(top: 0, left: 64, bottom: 0, right: 0)
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 64
        tableView.register(SetupCell.self, forCellReuseIdentifier: SetupCell.reuseIdentifier)
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "DestinationCell")

        navigationController?.navigationBar.prefersLargeTitles = true
        navigationItem.largeTitleDisplayMode = .always
        navigationController?.navigationBar.tintColor = .systemGreen
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        tableView.reloadSections(IndexSet(integer: Section.setup.rawValue), with: .none)
    }

    // MARK: - Table structure

    override func numberOfSections(in tableView: UITableView) -> Int {
        Section.allCases.count
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch Section(rawValue: section)! {
        case .setup:       return 1
        case .explore:     return 1
        case .keyboard:    return keyboardRows.count
        case .privacy:     return 1
        case .support:     return 1
        case .connections: return connectionRows.count
        }
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch Section(rawValue: section)! {
        case .setup:       return nil
        case .explore:     return "Explore"
        case .keyboard:    return "Your keyboard"
        case .privacy:     return "Privacy"
        case .support:     return "Support"
        case .connections: return "Connections"
        }
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        switch Section(rawValue: section)! {
        case .setup:
            return OnboardingState.isComplete
                ? "Touch and hold the globe key in any app whenever you want to switch keyboards."
                : "After enabling Turtle, touch and hold the globe key in any app to switch keyboards."
        case .explore:
            return "Preview commands here before using them in another app."
        case .keyboard:
            return "Changes apply the next time Turtle Keyboard appears."
        case .privacy:
            return "Turtle only uses network access when you ask it to perform a connected action."
        case .support:
            return nil
        case .connections:
            return "Connected services let slash commands work without leaving your conversation."
        }
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let section = Section(rawValue: indexPath.section)!
        if section == .setup {
            let cell = tableView.dequeueReusableCell(withIdentifier: SetupCell.reuseIdentifier,
                                                     for: indexPath) as! SetupCell
            let state = KeyboardHomeState.current()
            cell.configure(state: state)
            cell.onPrimaryAction = { [weak self] in self?.handlePrimaryAction(for: state) }
            cell.onTroubleshoot = { [weak self] in self?.showTroubleshooting() }
            return cell
        }

        let destination: Destination
        switch section {
        case .explore: destination = .playground
        case .keyboard: destination = keyboardRows[indexPath.row]
        case .privacy: destination = .privacy
        case .support: destination = .help
        case .connections: destination = connectionRows[indexPath.row]
        case .setup: fatalError("Setup is handled above")
        }
        let cell = tableView.dequeueReusableCell(withIdentifier: "DestinationCell", for: indexPath)
        var content = cell.defaultContentConfiguration()
        content.text = destination.title
        content.secondaryText = destination.subtitle
        content.textProperties.font = .preferredFont(forTextStyle: .body)
        content.secondaryTextProperties.font = .preferredFont(forTextStyle: .subheadline)
        content.secondaryTextProperties.color = .secondaryLabel
        content.image = UIImage(systemName: destination.symbol)
        content.imageProperties.tintColor = .systemGreen
        content.imageProperties.maximumSize = CGSize(width: 28, height: 28)
        content.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 10, leading: 0, bottom: 10, trailing: 0)
        cell.contentConfiguration = content
        cell.accessoryType = .disclosureIndicator
        cell.selectionStyle = .default
        cell.accessibilityHint = "Opens \(destination.title)"
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard let section = Section(rawValue: indexPath.section), section != .setup else { return }
        let destination: Destination
        switch section {
        case .explore: destination = .playground
        case .keyboard: destination = keyboardRows[indexPath.row]
        case .privacy: destination = .privacy
        case .support: destination = .help
        case .connections: destination = connectionRows[indexPath.row]
        case .setup: return
        }
        present(destination)
    }

    private func present(_ destination: Destination) {
        let controller: UIViewController
        switch destination {
        case .personalize: controller = PersonalizationViewController()
        case .playground:  controller = PlaygroundViewController()
        case .splits:      controller = SplitDetailViewController()
        case .history:     controller = HistoryViewController()
        case .privacy:     controller = PrivacyViewController()
        case .help:        controller = HelpViewController()
        case .github:      controller = GitHubConnectViewController()
        case .notion:      controller = NotionConnectViewController()
        case .slack:       controller = SlackConnectViewController()
        }

        let navigation = UINavigationController(rootViewController: controller)
        navigation.navigationBar.prefersLargeTitles = false
        navigation.navigationBar.tintColor = .systemGreen
        navigation.modalPresentationStyle = .pageSheet
        if let sheet = navigation.sheetPresentationController {
            sheet.detents = [.large()]
            sheet.prefersGrabberVisible = true
        }
        present(navigation, animated: true)
    }

    // iOS does not provide an API to enable or select a third-party keyboard.
    @objc private func openKeyboardSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        HostPrivacySafeTelemetry.settingsOpened()
        UIApplication.shared.open(url)
    }

    private func reviewSetup() {
        let onboarding = OnboardingViewController(startAt: 2)
        onboarding.modalPresentationStyle = .fullScreen
        onboarding.onComplete = { [weak self, weak onboarding] in
            onboarding?.dismiss(animated: true)
            self?.tableView.reloadData()
        }
        present(onboarding, animated: true)
    }

    private func handlePrimaryAction(for state: KeyboardHomeState) {
        switch state {
        case .notConfigured, .keyboardEnabled, .fullAccessRequired:
            reviewSetup()
        case .ready:
            present(.playground)
        case .actionNeeded:
            showTroubleshooting()
        }
    }

    private func showTroubleshooting() {
        let alert = UIAlertController(
            title: "Troubleshoot Turtle",
            message: "Open Settings to check that Turtle Keyboard and Allow Full Access are enabled, or run the setup test again.",
            preferredStyle: .actionSheet)
        alert.addAction(UIAlertAction(title: "Open Settings", style: .default) { [weak self] _ in
            self?.openKeyboardSettings()
        })
        alert.addAction(UIAlertAction(title: "Open Help", style: .default) { [weak self] _ in
            self?.present(.help)
        })
        alert.addAction(UIAlertAction(title: "Run setup test", style: .default) { [weak self] _ in
            self?.reviewSetup()
        })
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.popoverPresentationController?.sourceView = view
        alert.popoverPresentationController?.sourceRect = CGRect(
            x: view.bounds.midX, y: view.bounds.maxY - 80, width: 1, height: 1)
        present(alert, animated: true)
    }
}

private final class SetupCell: UITableViewCell {
    static let reuseIdentifier = "SetupCell"

    var onPrimaryAction: (() -> Void)?
    var onTroubleshoot: (() -> Void)?

    private let iconWrapper = UIView()
    private let iconContainer = UIView()
    private let brandMark = TurtleBrandMarkView()
    private let titleLabel = UILabel()
    private let bodyLabel = UILabel()
    private let stepsStack = UIStackView()
    private let lastCommandLabel = UILabel()
    private let settingsButton = UIButton(type: .system)
    private let troubleshootButton = UIButton(type: .system)
    private var iconWidthConstraint: NSLayoutConstraint!
    private var iconHeightConstraint: NSLayoutConstraint!
    private var iconWrapperHeightConstraint: NSLayoutConstraint!
    private var stackTopConstraint: NSLayoutConstraint!
    private var stackBottomConstraint: NSLayoutConstraint!

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        buildUI()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        buildUI()
    }

    private func buildUI() {
        selectionStyle = .none
        backgroundColor = .secondarySystemGroupedBackground

        iconContainer.backgroundColor = .systemGreen
        iconContainer.layer.cornerRadius = 18
        iconContainer.layer.cornerCurve = .continuous
        iconContainer.translatesAutoresizingMaskIntoConstraints = false

        iconWrapper.translatesAutoresizingMaskIntoConstraints = false
        brandMark.translatesAutoresizingMaskIntoConstraints = false
        iconContainer.addSubview(brandMark)
        iconWrapper.addSubview(iconContainer)

        titleLabel.text = "Set up Turtle Keyboard"
        titleLabel.font = .preferredFont(forTextStyle: .title2)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0

        bodyLabel.text = "Add Turtle once, then use it anywhere you type."
        bodyLabel.font = .preferredFont(forTextStyle: .body)
        bodyLabel.adjustsFontForContentSizeCategory = true
        bodyLabel.textColor = .secondaryLabel
        bodyLabel.textAlignment = .center
        bodyLabel.numberOfLines = 0

        stepsStack.axis = .vertical
        stepsStack.spacing = 12
        stepsStack.addArrangedSubview(makeStep(number: "1", text: "Open Keyboard settings"))
        stepsStack.addArrangedSubview(makeStep(number: "2", text: "Add Turtle Keyboard and allow full access"))

        lastCommandLabel.font = .preferredFont(forTextStyle: .subheadline)
        lastCommandLabel.adjustsFontForContentSizeCategory = true
        lastCommandLabel.textColor = .secondaryLabel
        lastCommandLabel.textAlignment = .center
        lastCommandLabel.numberOfLines = 0
        lastCommandLabel.isHidden = true

        var configuration = UIButton.Configuration.filled()
        configuration.title = "Open Settings"
        configuration.image = UIImage(systemName: "arrow.up.forward.app")
        configuration.imagePadding = 8
        configuration.cornerStyle = .large
        configuration.baseBackgroundColor = .systemGreen
        configuration.baseForegroundColor = .white
        configuration.contentInsets = NSDirectionalEdgeInsets(top: 12, leading: 20, bottom: 12, trailing: 20)
        settingsButton.configuration = configuration
        settingsButton.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        settingsButton.addTarget(self, action: #selector(openSettings), for: .touchUpInside)
        settingsButton.accessibilityHint = "Opens this app's page in Settings"

        troubleshootButton.setTitle("Troubleshoot", for: .normal)
        troubleshootButton.titleLabel?.font = .preferredFont(forTextStyle: .subheadline)
        troubleshootButton.setTitleColor(.systemGreen, for: .normal)
        troubleshootButton.addTarget(self, action: #selector(troubleshootTapped), for: .touchUpInside)
        troubleshootButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        troubleshootButton.isHidden = true

        let stack = UIStackView(arrangedSubviews: [iconWrapper, titleLabel, bodyLabel, stepsStack, lastCommandLabel, settingsButton, troubleshootButton])
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = 12
        stack.setCustomSpacing(16, after: bodyLabel)
        stack.setCustomSpacing(20, after: stepsStack)
        stack.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(stack)

        iconWidthConstraint = iconContainer.widthAnchor.constraint(equalToConstant: 72)
        iconHeightConstraint = iconContainer.heightAnchor.constraint(equalToConstant: 72)
        iconWrapperHeightConstraint = iconWrapper.heightAnchor.constraint(equalToConstant: 72)
        stackTopConstraint = stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 24)
        stackBottomConstraint = stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -24)
        NSLayoutConstraint.activate([
            iconWidthConstraint,
            iconHeightConstraint,
            iconWrapperHeightConstraint,
            iconContainer.centerXAnchor.constraint(equalTo: iconWrapper.centerXAnchor),
            iconContainer.centerYAnchor.constraint(equalTo: iconWrapper.centerYAnchor),
            brandMark.leadingAnchor.constraint(equalTo: iconContainer.leadingAnchor, constant: 8),
            brandMark.trailingAnchor.constraint(equalTo: iconContainer.trailingAnchor, constant: -8),
            brandMark.topAnchor.constraint(equalTo: iconContainer.topAnchor, constant: 8),
            brandMark.bottomAnchor.constraint(equalTo: iconContainer.bottomAnchor, constant: -8),
            settingsButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 50),
            stackTopConstraint,
            stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            stackBottomConstraint,
        ])
    }

    func configure(state: KeyboardHomeState) {
        stepsStack.isHidden = true
        lastCommandLabel.isHidden = true
        troubleshootButton.isHidden = true
        settingsButton.configuration?.baseBackgroundColor = .systemGreen

        switch state {
        case .notConfigured:
            setCompact(false)
            titleLabel.text = "Finish setting up Turtle"
            bodyLabel.text = "Add Turtle Keyboard to use AI anywhere you type."
            stepsStack.isHidden = false
            settingsButton.configuration?.title = "Continue setup"
            settingsButton.configuration?.image = UIImage(systemName: "arrow.up.forward.app")
        case .keyboardEnabled:
            setCompact(true)
            configureStatus(symbol: "keyboard", title: "Keyboard enabled",
                            body: "Turtle is installed. Finish setup to check network access.",
                            button: "Continue setup")
        case .fullAccessRequired:
            setCompact(true)
            configureStatus(symbol: "exclamationmark.shield", title: "Full Access required",
                            body: "Turn on Full Access so AI commands and connected services can work.",
                            button: "Review Full Access")
            settingsButton.configuration?.baseBackgroundColor = .systemOrange
            troubleshootButton.isHidden = false
        case .ready(let command):
            setCompact(true)
            configureStatus(symbol: "checkmark", title: "Turtle is ready",
                            body: "Use slash commands anywhere you type.",
                            button: "Try a command")
            lastCommandLabel.text = command.map { "Last successful command: /\($0)" }
                ?? "No commands completed yet. Try your first one."
            lastCommandLabel.isHidden = false
            troubleshootButton.isHidden = false
        case .actionNeeded(let message):
            setCompact(true)
            configureStatus(symbol: "exclamationmark", title: "Turtle needs attention",
                            body: message, button: "Troubleshoot")
            settingsButton.configuration?.baseBackgroundColor = .systemOrange
        }
    }

    private func configureStatus(symbol: String, title: String, body: String, button: String) {
        titleLabel.text = title
        bodyLabel.text = body
        settingsButton.configuration?.title = button
        settingsButton.configuration?.image = UIImage(systemName: symbol)
    }

    private func setCompact(_ compact: Bool) {
        iconWidthConstraint.constant = compact ? 52 : 72
        iconHeightConstraint.constant = compact ? 52 : 72
        iconWrapperHeightConstraint.constant = compact ? 52 : 72
        iconContainer.layer.cornerRadius = compact ? 14 : 18
        stackTopConstraint.constant = compact ? 16 : 24
        stackBottomConstraint.constant = compact ? -16 : -24
    }

    private func makeStep(number: String, text: String) -> UIView {
        let badge = UILabel()
        badge.text = number
        badge.font = .preferredFont(forTextStyle: .headline)
        badge.textColor = .white
        badge.textAlignment = .center
        badge.backgroundColor = .systemGreen
        badge.layer.cornerRadius = 12
        badge.layer.masksToBounds = true
        badge.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            badge.widthAnchor.constraint(equalToConstant: 24),
            badge.heightAnchor.constraint(equalToConstant: 24),
        ])

        let label = UILabel()
        label.text = text
        label.font = .preferredFont(forTextStyle: .subheadline)
        label.adjustsFontForContentSizeCategory = true
        label.numberOfLines = 0

        let row = UIStackView(arrangedSubviews: [badge, label])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 12
        return row
    }

    @objc private func openSettings() {
        onPrimaryAction?()
    }

    @objc private func troubleshootTapped() { onTroubleshoot?() }
}

/// Code-native Turtle brand mark: the 2×3 dot grid and diagonal slash used
/// by the Android app, rendered crisply at every scale without another asset.
private final class TurtleBrandMarkView: UIView {
    override class var layerClass: AnyClass { CAShapeLayer.self }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let layer = layer as? CAShapeLayer else { return }
        let side = min(bounds.width, bounds.height)
        let origin = CGPoint(x: (bounds.width - side) / 2, y: (bounds.height - side) / 2)
        let path = UIBezierPath()
        let radius = side * 0.07
        for x in [0.28, 0.45] as [CGFloat] {
            for y in [0.30, 0.50, 0.70] as [CGFloat] {
                let center = CGPoint(x: origin.x + side * x, y: origin.y + side * y)
                path.append(UIBezierPath(arcCenter: center, radius: radius,
                                         startAngle: 0, endAngle: .pi * 2, clockwise: true))
            }
        }
        layer.path = path.cgPath
        layer.fillColor = UIColor.white.cgColor

        layer.sublayers?.forEach { $0.removeFromSuperlayer() }
        let slash = CAShapeLayer()
        let slashPath = UIBezierPath()
        slashPath.move(to: CGPoint(x: origin.x + side * 0.74, y: origin.y + side * 0.20))
        slashPath.addLine(to: CGPoint(x: origin.x + side * 0.58, y: origin.y + side * 0.80))
        slash.path = slashPath.cgPath
        slash.strokeColor = UIColor.white.cgColor
        slash.fillColor = nil
        slash.lineWidth = side * 0.13
        slash.lineCap = .round
        layer.addSublayer(slash)
    }
}

// MARK: - Interactive playground

final class PlaygroundViewController: UIViewController {
    private let promptField = UITextField()
    private let commandStack = UIStackView()
    private let resultLabel = UILabel()
    private let resultCard = UIView()
    private let runButton = UIButton(type: .system)
    private let spinner = UIActivityIndicatorView(style: .medium)
    private let copyButton = UIButton(type: .system)
    private let shareButton = UIButton(type: .system)
    private var selectedCommand = "fix"
    private var pendingPreview: DispatchWorkItem?
    private var resultText: String?

    private let examples: [(command: String, prompt: String)] = [
        ("fix", "i dont think this are right"),
        ("tone", "send me the file today"),
        ("reply", "Can we move our call to tomorrow?"),
        ("cap", "a tiny turtle coding at midnight"),
    ]

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Try Turtle"
        view.backgroundColor = .systemGroupedBackground
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Done", style: .done, target: self, action: #selector(doneTapped))
        buildUI()
        select(command: "fix")
    }

    deinit { pendingPreview?.cancel() }

    private func buildUI() {
        let intro = UILabel()
        intro.text = "See how a slash command works before adding the keyboard."
        intro.font = .preferredFont(forTextStyle: .body)
        intro.adjustsFontForContentSizeCategory = true
        intro.textColor = .secondaryLabel
        intro.numberOfLines = 0

        let commandTitle = sectionLabel("Choose a command")
        commandStack.axis = .horizontal
        commandStack.spacing = 8
        commandStack.distribution = .fillEqually
        for example in examples {
            var configuration = UIButton.Configuration.tinted()
            configuration.title = "/\(example.command)"
            configuration.cornerStyle = .capsule
            configuration.baseForegroundColor = .systemGreen
            let button = UIButton(configuration: configuration)
            button.accessibilityLabel = "Try slash \(example.command)"
            button.addAction(UIAction { [weak self] _ in self?.select(command: example.command) }, for: .touchUpInside)
            commandStack.addArrangedSubview(button)
        }

        promptField.font = .preferredFont(forTextStyle: .body)
        promptField.adjustsFontForContentSizeCategory = true
        promptField.backgroundColor = .secondarySystemGroupedBackground
        promptField.layer.cornerRadius = 12
        promptField.layer.cornerCurve = .continuous
        promptField.clearButtonMode = .whileEditing
        promptField.returnKeyType = .go
        promptField.setLeftPadding(14)
        promptField.heightAnchor.constraint(greaterThanOrEqualToConstant: 52).isActive = true
        promptField.accessibilityLabel = "Command prompt"
        promptField.addTarget(self, action: #selector(runTapped), for: .editingDidEndOnExit)

        var runConfiguration = UIButton.Configuration.filled()
        runConfiguration.title = "Run preview"
        runConfiguration.image = UIImage(systemName: "play.fill")
        runConfiguration.imagePadding = 8
        runConfiguration.cornerStyle = .large
        runConfiguration.baseBackgroundColor = .systemGreen
        runConfiguration.baseForegroundColor = .white
        runButton.configuration = runConfiguration
        runButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 50).isActive = true
        runButton.addTarget(self, action: #selector(runTapped), for: .touchUpInside)

        spinner.hidesWhenStopped = true
        spinner.color = .systemGreen
        spinner.accessibilityLabel = "Creating preview"

        resultCard.backgroundColor = .secondarySystemGroupedBackground
        resultCard.layer.cornerRadius = 14
        resultCard.layer.cornerCurve = .continuous
        resultCard.translatesAutoresizingMaskIntoConstraints = false
        resultLabel.text = "Your result will appear here."
        resultLabel.font = .preferredFont(forTextStyle: .body)
        resultLabel.adjustsFontForContentSizeCategory = true
        resultLabel.textColor = .secondaryLabel
        resultLabel.numberOfLines = 0
        resultLabel.translatesAutoresizingMaskIntoConstraints = false
        resultCard.addSubview(resultLabel)
        NSLayoutConstraint.activate([
            resultLabel.leadingAnchor.constraint(equalTo: resultCard.leadingAnchor, constant: 16),
            resultLabel.trailingAnchor.constraint(equalTo: resultCard.trailingAnchor, constant: -16),
            resultLabel.topAnchor.constraint(equalTo: resultCard.topAnchor, constant: 16),
            resultLabel.bottomAnchor.constraint(equalTo: resultCard.bottomAnchor, constant: -16),
            resultCard.heightAnchor.constraint(greaterThanOrEqualToConstant: 92),
        ])

        configureResultButton(copyButton, title: "Copy", symbol: "doc.on.doc", action: #selector(copyTapped))
        configureResultButton(shareButton, title: "Share", symbol: "square.and.arrow.up", action: #selector(shareTapped))
        let resultActions = UIStackView(arrangedSubviews: [copyButton, shareButton])
        resultActions.axis = .horizontal
        resultActions.spacing = 12
        resultActions.distribution = .fillEqually
        setResultActionsVisible(false)

        let explanation = UILabel()
        explanation.text = "Inside Messages, Mail, or another app, Turtle inserts text results into the active field. Generated images are copied so you can paste them where iOS allows."
        explanation.font = .preferredFont(forTextStyle: .footnote)
        explanation.adjustsFontForContentSizeCategory = true
        explanation.textColor = .secondaryLabel
        explanation.numberOfLines = 0

        let stack = UIStackView(arrangedSubviews: [
            intro, commandTitle, commandStack, sectionLabel("Example prompt"), promptField,
            runButton, spinner, sectionLabel("Preview result"), resultCard, resultActions,
            sectionLabel("What happens in another app"), explanation,
        ])
        stack.axis = .vertical
        stack.spacing = 12
        stack.setCustomSpacing(24, after: intro)
        stack.setCustomSpacing(24, after: spinner)
        stack.setCustomSpacing(24, after: resultActions)
        stack.translatesAutoresizingMaskIntoConstraints = false

        let scroll = UIScrollView()
        scroll.keyboardDismissMode = .interactive
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        view.addSubview(scroll)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 20),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -20),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -24),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -40),
        ])
    }

    private func select(command: String) {
        selectedCommand = command
        if let example = examples.first(where: { $0.command == command }) { promptField.text = example.prompt }
        for case let button as UIButton in commandStack.arrangedSubviews {
            button.configuration?.baseBackgroundColor = button.configuration?.title == "/\(command)" ? .systemGreen : .tertiarySystemFill
            button.configuration?.baseForegroundColor = button.configuration?.title == "/\(command)" ? .white : .systemGreen
        }
        resultText = nil
        resultLabel.text = "Your result will appear here."
        resultLabel.textColor = .secondaryLabel
        setResultActionsVisible(false)
    }

    @objc private func runTapped() {
        if pendingPreview != nil { cancelPreview(); return }
        promptField.resignFirstResponder()
        let prompt = promptField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !prompt.isEmpty else {
            resultLabel.text = "Enter a prompt first."
            resultLabel.textColor = .systemOrange
            UIAccessibility.post(notification: .announcement, argument: "Enter a prompt first")
            return
        }
        setLoading(true)
        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.pendingPreview = nil
            self.resultText = self.previewResult(command: self.selectedCommand, prompt: prompt)
            self.resultLabel.text = self.resultText
            self.resultLabel.textColor = .label
            self.setLoading(false)
            self.setResultActionsVisible(true)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            UIAccessibility.post(notification: .layoutChanged, argument: self.resultLabel)
        }
        pendingPreview = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.7, execute: work)
    }

    private func previewResult(command: String, prompt: String) -> String {
        switch command {
        case "fix": return "I don’t think this is right."
        case "tone": return "Could you please send me the file today? Thank you."
        case "reply": return "Tomorrow works for me. What time would be best?"
        default: return "Image preview: A tiny turtle coding beside a glowing laptop at midnight."
        }
    }

    private func setLoading(_ loading: Bool) {
        promptField.isEnabled = !loading
        commandStack.isUserInteractionEnabled = !loading
        loading ? spinner.startAnimating() : spinner.stopAnimating()
        runButton.configuration?.title = loading ? "Cancel" : "Run preview"
        runButton.configuration?.image = UIImage(systemName: loading ? "xmark" : "play.fill")
        UIAccessibility.post(notification: .announcement, argument: loading ? "Creating preview" : "Preview ready")
    }

    private func cancelPreview() {
        pendingPreview?.cancel()
        pendingPreview = nil
        setLoading(false)
        resultLabel.text = "Preview cancelled."
        resultLabel.textColor = .secondaryLabel
    }

    private func setResultActionsVisible(_ visible: Bool) {
        copyButton.isHidden = !visible
        shareButton.isHidden = !visible
    }

    private func configureResultButton(_ button: UIButton, title: String, symbol: String, action: Selector) {
        var configuration = UIButton.Configuration.tinted()
        configuration.title = title
        configuration.image = UIImage(systemName: symbol)
        configuration.imagePadding = 8
        configuration.baseForegroundColor = .systemGreen
        button.configuration = configuration
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        button.addTarget(self, action: action, for: .touchUpInside)
    }

    private func sectionLabel(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .preferredFont(forTextStyle: .headline)
        label.adjustsFontForContentSizeCategory = true
        return label
    }

    @objc private func copyTapped() {
        guard let resultText = resultText else { return }
        UIPasteboard.general.string = resultText
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        UIAccessibility.post(notification: .announcement, argument: "Result copied")
    }

    @objc private func shareTapped() {
        guard let resultText = resultText else { return }
        let activity = UIActivityViewController(activityItems: [resultText], applicationActivities: nil)
        activity.popoverPresentationController?.sourceView = shareButton
        present(activity, animated: true)
    }

    @objc private func doneTapped() { dismiss(animated: true) }
}

// MARK: - Help & troubleshooting

final class HelpViewController: UITableViewController {
    private struct Topic {
        let title: String
        let symbol: String
        let detail: String
    }

    private let topics = [
        Topic(title: "Turtle isn’t in the globe menu", symbol: "globe",
              detail: "Open Settings → General → Keyboard → Keyboards → Add New Keyboard, then choose Turtle. Return to a text field and touch and hold the globe key."),
        Topic(title: "Full Access is disabled", symbol: "hand.raised",
              detail: "Open Settings → General → Keyboard → Keyboards → Turtle Keyboard and turn on Allow Full Access. This enables AI, connected services, voice, and shared settings."),
        Topic(title: "Commands aren’t responding", symbol: "wifi.exclamationmark",
              detail: "Check your internet connection, confirm Full Access is enabled, and try a short command such as /fix. If the issue continues, switch to Apple’s keyboard and back to Turtle."),
        Topic(title: "The keyboard switched back", symbol: "keyboard.badge.ellipsis",
              detail: "iOS always uses Apple’s keyboard in password and other secure fields. In normal fields, touch and hold the globe key and choose Turtle again."),
        Topic(title: "I can’t insert an image", symbol: "photo",
              detail: "iOS does not let third-party keyboards insert images directly in every app. Turtle copies the image; touch and hold the message field, then choose Paste."),
        Topic(title: "Voice is unavailable", symbol: "mic.slash",
              detail: "Enable Microphone and Speech Recognition for Turtle in Settings. Also confirm Full Access is enabled, then reopen Turtle before trying voice again."),
        Topic(title: "Remove Turtle Keyboard", symbol: "trash",
              detail: "Open Settings → General → Keyboard → Keyboards, swipe left on Turtle Keyboard, and tap Delete. You can also delete local Turtle data first from Privacy & Data."),
    ]

    init() { super.init(style: .insetGrouped) }
    required init?(coder: NSCoder) { super.init(coder: coder) }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Help"
        view.backgroundColor = .systemGroupedBackground
        tableView.backgroundColor = .systemGroupedBackground
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "HelpTopic")
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Done", style: .done, target: self, action: #selector(doneTapped))
    }

    override func numberOfSections(in tableView: UITableView) -> Int { 2 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        section == 0 ? topics.count : 2
    }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        section == 0 ? "Common fixes" : "Support"
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "HelpTopic", for: indexPath)
        var content = cell.defaultContentConfiguration()
        if indexPath.section == 0 {
            let topic = topics[indexPath.row]
            content.text = topic.title
            content.image = UIImage(systemName: topic.symbol)
            content.imageProperties.tintColor = .systemGreen
            cell.accessoryType = .disclosureIndicator
        } else if indexPath.row == 0 {
            content.text = "Open support website"
            content.image = UIImage(systemName: "safari")
            content.imageProperties.tintColor = .systemGreen
            cell.accessoryType = .disclosureIndicator
        } else {
            content.text = "Report a problem"
            content.image = UIImage(systemName: "exclamationmark.bubble")
            content.imageProperties.tintColor = .systemGreen
            cell.accessoryType = .disclosureIndicator
        }
        cell.contentConfiguration = content
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if indexPath.section == 0 {
            let topic = topics[indexPath.row]
            let alert = UIAlertController(title: topic.title, message: topic.detail, preferredStyle: .alert)
            if indexPath.row < 2 {
                alert.addAction(UIAlertAction(title: "Open Settings", style: .default) { _ in
                    guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                    UIApplication.shared.open(url)
                })
            }
            alert.addAction(UIAlertAction(title: "Done", style: .cancel))
            present(alert, animated: true)
        } else if indexPath.row == 0 {
            if let url = URL(string: "https://www.turtlekeyboard.com") { UIApplication.shared.open(url) }
        } else {
            let report = "Turtle Keyboard problem report\n\niOS: \(UIDevice.current.systemVersion)\nDevice: \(UIDevice.current.model)\n\nWhat happened:\n"
            let activity = UIActivityViewController(activityItems: [report], applicationActivities: nil)
            activity.popoverPresentationController?.sourceView = tableView.cellForRow(at: indexPath)
            present(activity, animated: true)
        }
    }

    @objc private func doneTapped() { dismiss(animated: true) }
}

// MARK: - Shared network/service state UI

enum ServiceConnectionState {
    case connected
    case notConnected
    case needsAttention
    case loading
}

final class AppNetworkMonitor {
    static let shared = AppNetworkMonitor()
    static let didChange = Notification.Name("AppNetworkMonitor.didChange")

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.turtlekeyboard.network-monitor")
    private(set) var isOnline = true

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            DispatchQueue.main.async {
                guard let self = self, self.isOnline != online else { return }
                self.isOnline = online
                NotificationCenter.default.post(name: Self.didChange, object: self)
            }
        }
        monitor.start(queue: queue)
    }
}

final class ConnectionStatusView: UIView {
    var onRetry: (() -> Void)?

    private let icon = UIImageView()
    private let titleLabel = UILabel()
    private let detailLabel = UILabel()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private let retryButton = UIButton(type: .system)

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .secondarySystemGroupedBackground
        layer.cornerRadius = 12
        layer.cornerCurve = .continuous

        icon.contentMode = .scaleAspectFit
        icon.widthAnchor.constraint(equalToConstant: 28).isActive = true
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.adjustsFontForContentSizeCategory = true
        detailLabel.font = .preferredFont(forTextStyle: .subheadline)
        detailLabel.adjustsFontForContentSizeCategory = true
        detailLabel.textColor = .secondaryLabel
        detailLabel.numberOfLines = 0
        spinner.hidesWhenStopped = true
        retryButton.setTitle("Retry", for: .normal)
        retryButton.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        retryButton.addTarget(self, action: #selector(retryTapped), for: .touchUpInside)
        retryButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true

        let labels = UIStackView(arrangedSubviews: [titleLabel, detailLabel])
        labels.axis = .vertical
        labels.spacing = 3
        let header = UIStackView(arrangedSubviews: [icon, labels, spinner])
        header.axis = .horizontal
        header.alignment = .center
        header.spacing = 12
        let stack = UIStackView(arrangedSubviews: [header, retryButton])
        stack.axis = .vertical
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 12),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -12),
        ])
        isAccessibilityElement = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) not used") }

    func render(_ state: ServiceConnectionState, service: String, detail: String, canRetry: Bool = false) {
        titleLabel.text = stateTitle(state, service: service)
        detailLabel.text = detail
        retryButton.isHidden = !canRetry
        switch state {
        case .connected:
            icon.image = UIImage(systemName: "checkmark.circle.fill")
            icon.tintColor = .systemGreen
            spinner.stopAnimating()
        case .notConnected:
            icon.image = UIImage(systemName: "circle")
            icon.tintColor = .secondaryLabel
            spinner.stopAnimating()
        case .needsAttention:
            icon.image = UIImage(systemName: "exclamationmark.triangle.fill")
            icon.tintColor = .systemOrange
            spinner.stopAnimating()
        case .loading:
            icon.image = UIImage(systemName: "arrow.triangle.2.circlepath")
            icon.tintColor = .systemGreen
            spinner.startAnimating()
        }
        accessibilityLabel = "\(titleLabel.text ?? service). \(detail)"
        accessibilityTraits = state == .loading ? [.updatesFrequently] : [.staticText]
    }

    private func stateTitle(_ state: ServiceConnectionState, service: String) -> String {
        switch state {
        case .connected: return "Connected"
        case .notConnected: return "Not connected"
        case .needsAttention: return "Needs attention"
        case .loading: return "Connecting to \(service)…"
        }
    }

    @objc private func retryTapped() { onRetry?() }
}

// MARK: - First-run onboarding

final class OnboardingViewController: UIViewController, UITextFieldDelegate {
    var onComplete: (() -> Void)?

    private let pageControl = UIPageControl()
    private let contentHost = UIView()
    private let backButton = UIButton(type: .system)
    private let continueButton = UIButton(type: .system)
    private var currentPage: Int
    private var heartbeatTimer: Timer?
    private var demoTimer: Timer?

    init(startAt page: Int = 0) {
        currentPage = min(max(page, 0), 4)
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        currentPage = 0
        super.init(coder: coder)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        buildChrome()
        renderPage(animated: false)
    }

    deinit {
        heartbeatTimer?.invalidate()
        demoTimer?.invalidate()
    }

    private func buildChrome() {
        pageControl.numberOfPages = 5
        pageControl.currentPage = currentPage
        pageControl.currentPageIndicatorTintColor = .systemGreen
        pageControl.pageIndicatorTintColor = .systemGray4
        pageControl.isUserInteractionEnabled = false

        backButton.setTitle("Back", for: .normal)
        backButton.titleLabel?.font = .preferredFont(forTextStyle: .body)
        backButton.addTarget(self, action: #selector(backTapped), for: .touchUpInside)

        var configuration = UIButton.Configuration.filled()
        configuration.title = "Continue"
        configuration.cornerStyle = .large
        configuration.baseBackgroundColor = .systemGreen
        configuration.baseForegroundColor = .white
        configuration.contentInsets = NSDirectionalEdgeInsets(top: 13, leading: 24, bottom: 13, trailing: 24)
        continueButton.configuration = configuration
        continueButton.addTarget(self, action: #selector(continueTapped), for: .touchUpInside)
        continueButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 50).isActive = true

        let buttons = UIStackView(arrangedSubviews: [backButton, continueButton])
        buttons.axis = .horizontal
        buttons.spacing = 12
        buttons.distribution = .fillEqually

        [pageControl, contentHost, buttons].forEach {
            $0.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview($0)
        }
        NSLayoutConstraint.activate([
            pageControl.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            pageControl.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            contentHost.topAnchor.constraint(equalTo: pageControl.bottomAnchor, constant: 8),
            contentHost.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            contentHost.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            contentHost.bottomAnchor.constraint(equalTo: buttons.topAnchor, constant: -20),
            buttons.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            buttons.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            buttons.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16),
        ])
    }

    private func renderPage(animated: Bool) {
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
        demoTimer?.invalidate()
        demoTimer = nil
        pageControl.currentPage = currentPage
        backButton.isHidden = currentPage == 0
        continueButton.configuration?.title = currentPage == 4 ? "Finish" : "Continue"
        continueButton.isEnabled = currentPage != 4

        let newContent: UIView
        switch currentPage {
        case 0: newContent = welcomePage()
        case 1: newContent = playgroundPage()
        case 2: newContent = activationPage()
        case 3: newContent = fullAccessPage()
        default: newContent = testPage()
        }
        newContent.translatesAutoresizingMaskIntoConstraints = false

        let oldViews = contentHost.subviews
        contentHost.addSubview(newContent)
        NSLayoutConstraint.activate([
            newContent.topAnchor.constraint(equalTo: contentHost.topAnchor),
            newContent.leadingAnchor.constraint(equalTo: contentHost.leadingAnchor),
            newContent.trailingAnchor.constraint(equalTo: contentHost.trailingAnchor),
            newContent.bottomAnchor.constraint(equalTo: contentHost.bottomAnchor),
        ])
        if animated && !UIAccessibility.isReduceMotionEnabled {
            newContent.alpha = 0
            UIView.animate(withDuration: 0.25, animations: { newContent.alpha = 1 }) { _ in
                oldViews.forEach { $0.removeFromSuperview() }
            }
        } else {
            oldViews.forEach { $0.removeFromSuperview() }
        }
    }

    private func welcomePage() -> UIView {
        let demo = KeyboardDemoView()
        demo.translatesAutoresizingMaskIntoConstraints = false
        demo.heightAnchor.constraint(equalToConstant: 210).isActive = true
        demo.start()
        demoTimer = Timer.scheduledTimer(withTimeInterval: 3.4, repeats: true) { [weak demo] _ in demo?.start() }
        return pageStack(symbol: "keyboard.fill", title: "AI, wherever you type",
                         body: "Turtle brings useful AI commands into Messages, Mail, social apps, and more.",
                         content: demo)
    }

    private func playgroundPage() -> UIView {
        let field = UITextField()
        field.borderStyle = .none
        field.backgroundColor = .secondarySystemGroupedBackground
        field.layer.cornerRadius = 12
        field.layer.cornerCurve = .continuous
        field.font = .preferredFont(forTextStyle: .body)
        field.placeholder = "Type a sentence to improve"
        field.text = "i dont think this are right"
        field.inputAccessoryView = keyboardDismissToolbar()
        field.setLeftPadding(14)
        field.heightAnchor.constraint(equalToConstant: 52).isActive = true

        let result = UILabel()
        result.font = .preferredFont(forTextStyle: .body)
        result.textColor = .label
        result.numberOfLines = 0
        result.backgroundColor = .secondarySystemGroupedBackground
        result.layer.cornerRadius = 12
        result.layer.cornerCurve = .continuous
        result.layer.masksToBounds = true
        result.textAlignment = .center
        result.text = "Your improved sentence will appear here."
        result.heightAnchor.constraint(greaterThanOrEqualToConstant: 72).isActive = true

        var tryConfig = UIButton.Configuration.tinted()
        tryConfig.title = "Try /fix"
        tryConfig.image = UIImage(systemName: "wand.and.stars")
        tryConfig.imagePadding = 8
        tryConfig.baseForegroundColor = .systemGreen
        let tryButton = UIButton(configuration: tryConfig)
        tryButton.addAction(UIAction { _ in
            field.resignFirstResponder()
            let source = field.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            result.text = source.isEmpty ? "Type something first." : "I don’t think this is right."
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }, for: .touchUpInside)

        let content = UIStackView(arrangedSubviews: [field, tryButton, result])
        content.axis = .vertical
        content.spacing = 12
        return pageStack(symbol: "wand.and.stars", title: "Try Turtle",
                         body: "Slash commands turn a small request into an immediate action. Here’s a quick preview.",
                         content: content)
    }

    private func activationPage() -> UIView {
        let steps = UIStackView(arrangedSubviews: [
            onboardingStep("1", "Open Settings"),
            onboardingStep("2", "Tap Keyboards, then Add New Keyboard"),
            onboardingStep("3", "Choose Turtle Keyboard"),
        ])
        steps.axis = .vertical
        steps.spacing = 16

        var config = UIButton.Configuration.tinted()
        config.title = "Open Settings"
        config.image = UIImage(systemName: "arrow.up.forward.app")
        config.imagePadding = 8
        config.baseForegroundColor = .systemGreen
        let settings = UIButton(configuration: config)
        settings.heightAnchor.constraint(greaterThanOrEqualToConstant: 50).isActive = true
        settings.addAction(UIAction { _ in
            guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
            UIApplication.shared.open(url)
        }, for: .touchUpInside)

        let content = UIStackView(arrangedSubviews: [steps, settings])
        content.axis = .vertical
        content.spacing = 24
        return pageStack(symbol: "keyboard.badge.ellipsis", title: "Add the keyboard",
                         body: "iOS requires you to add third-party keyboards from Settings. This only takes a moment.",
                         content: content)
    }

    private func fullAccessPage() -> UIView {
        let trust = UIStackView(arrangedSubviews: [
            trustRow("lock.shield", "Ordinary typing stays private", "Turtle only sends text when you deliberately run a slash command."),
            trustRow("network", "Why Full Access is needed", "It lets AI commands connect to their services and return results."),
            trustRow("eye.slash", "No background collection", "Turtle doesn’t collect passwords, payment fields, or everything you type."),
        ])
        trust.axis = .vertical
        trust.spacing = 20

        var config = UIButton.Configuration.tinted()
        config.title = "Open Settings"
        config.image = UIImage(systemName: "gear")
        config.imagePadding = 8
        config.baseForegroundColor = .systemGreen
        let settings = UIButton(configuration: config)
        settings.heightAnchor.constraint(greaterThanOrEqualToConstant: 50).isActive = true
        settings.addAction(UIAction { _ in
            guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
            UIApplication.shared.open(url)
        }, for: .touchUpInside)

        var privacyConfig = UIButton.Configuration.plain()
        privacyConfig.title = "Read Privacy & Data"
        privacyConfig.image = UIImage(systemName: "hand.raised")
        privacyConfig.imagePadding = 8
        privacyConfig.baseForegroundColor = .systemGreen
        let privacy = UIButton(configuration: privacyConfig)
        privacy.addAction(UIAction { [weak self] _ in
            guard let self = self else { return }
            let controller = PrivacyViewController()
            let navigation = UINavigationController(rootViewController: controller)
            navigation.modalPresentationStyle = .pageSheet
            self.present(navigation, animated: true)
        }, for: .touchUpInside)

        let content = UIStackView(arrangedSubviews: [trust, settings, privacy])
        content.axis = .vertical
        content.spacing = 24
        return pageStack(symbol: "hand.raised.fill", title: "You stay in control",
                         body: "Apple calls network access “Full Access.” Open Settings, choose Turtle Keyboard, then turn on Allow Full Access.",
                         content: content)
    }

    private func testPage() -> UIView {
        let field = UITextField()
        field.borderStyle = .roundedRect
        field.placeholder = "Tap here, switch to Turtle, and type"
        field.font = .preferredFont(forTextStyle: .body)
        field.delegate = self
        field.inputAccessoryView = keyboardDismissToolbar()
        field.heightAnchor.constraint(equalToConstant: 52).isActive = true

        let status = UILabel()
        status.tag = 910
        status.text = "Waiting for Turtle Keyboard…"
        status.font = .preferredFont(forTextStyle: .subheadline)
        status.textColor = .secondaryLabel
        status.textAlignment = .center
        status.numberOfLines = 0

        let hint = UILabel()
        hint.text = "Touch and hold the 🌐 key, then choose Turtle Keyboard."
        hint.font = .preferredFont(forTextStyle: .footnote)
        hint.textColor = .secondaryLabel
        hint.textAlignment = .center
        hint.numberOfLines = 0

        let content = UIStackView(arrangedSubviews: [field, status, hint])
        content.axis = .vertical
        content.spacing = 16
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self, weak status] _ in
            guard let self = self, let status = status else { return }
            self.refreshKeyboardStatus(status)
        }
        refreshKeyboardStatus(status)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { field.becomeFirstResponder() }
        return pageStack(symbol: "checkmark.circle", title: "Make sure it works",
                         body: "Try Turtle once before you leave setup.", content: content)
    }

    private func refreshKeyboardStatus(_ label: UILabel) {
        let defaults = UserDefaults(suiteName: OnboardingState.appGroupID)
        let lastSeen = defaults?.double(forKey: OnboardingState.keyboardSeenKey) ?? 0
        guard Date().timeIntervalSince1970 - lastSeen < 10 else { return }
        let fullAccess = defaults?.bool(forKey: OnboardingState.fullAccessKey) ?? false
        label.text = fullAccess ? "Turtle Keyboard is ready ✓" : "Turtle is enabled. Turn on Full Access for AI commands."
        label.textColor = fullAccess ? .systemGreen : .systemOrange
        continueButton.isEnabled = true
    }

    private func pageStack(symbol: String, title: String, body: String, content: UIView) -> UIView {
        let icon = UIImageView(image: UIImage(systemName: symbol))
        icon.tintColor = .systemGreen
        icon.contentMode = .scaleAspectFit
        icon.heightAnchor.constraint(equalToConstant: 54).isActive = true

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .preferredFont(forTextStyle: .largeTitle)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0

        let bodyLabel = UILabel()
        bodyLabel.text = body
        bodyLabel.font = .preferredFont(forTextStyle: .body)
        bodyLabel.adjustsFontForContentSizeCategory = true
        bodyLabel.textColor = .secondaryLabel
        bodyLabel.textAlignment = .center
        bodyLabel.numberOfLines = 0

        let stack = UIStackView(arrangedSubviews: [icon, titleLabel, bodyLabel, content])
        stack.axis = .vertical
        stack.spacing = 16
        stack.setCustomSpacing(8, after: titleLabel)
        stack.setCustomSpacing(28, after: bodyLabel)
        stack.translatesAutoresizingMaskIntoConstraints = false

        let scroll = UIScrollView()
        scroll.alwaysBounceVertical = false
        scroll.showsVerticalScrollIndicator = false
        scroll.keyboardDismissMode = .interactive
        scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 8),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -8),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor),
        ])
        return scroll
    }

    private func onboardingStep(_ number: String, _ text: String) -> UIView {
        let badge = UILabel()
        badge.text = number
        badge.textAlignment = .center
        badge.font = .preferredFont(forTextStyle: .headline)
        badge.textColor = .white
        badge.backgroundColor = .systemGreen
        badge.layer.cornerRadius = 14
        badge.layer.masksToBounds = true
        badge.widthAnchor.constraint(equalToConstant: 28).isActive = true
        badge.heightAnchor.constraint(equalToConstant: 28).isActive = true
        let label = UILabel()
        label.text = text
        label.font = .preferredFont(forTextStyle: .body)
        label.numberOfLines = 0
        let row = UIStackView(arrangedSubviews: [badge, label])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 12
        return row
    }

    private func trustRow(_ symbol: String, _ title: String, _ body: String) -> UIView {
        let icon = UIImageView(image: UIImage(systemName: symbol))
        icon.tintColor = .systemGreen
        icon.contentMode = .scaleAspectFit
        icon.widthAnchor.constraint(equalToConstant: 30).isActive = true
        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        let bodyLabel = UILabel()
        bodyLabel.text = body
        bodyLabel.font = .preferredFont(forTextStyle: .subheadline)
        bodyLabel.textColor = .secondaryLabel
        bodyLabel.numberOfLines = 0
        let labels = UIStackView(arrangedSubviews: [titleLabel, bodyLabel])
        labels.axis = .vertical
        labels.spacing = 3
        let row = UIStackView(arrangedSubviews: [icon, labels])
        row.axis = .horizontal
        row.alignment = .top
        row.spacing = 14
        return row
    }

    @objc private func backTapped() {
        guard currentPage > 0 else { return }
        currentPage -= 1
        renderPage(animated: true)
    }

    @objc private func continueTapped() {
        view.endEditing(true)
        if currentPage < 4 {
            currentPage += 1
            renderPage(animated: true)
        } else {
            OnboardingState.complete()
            HostPrivacySafeTelemetry.onboardingCompleted()
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            onComplete?()
        }
    }

    private func keyboardDismissToolbar() -> UIToolbar {
        let toolbar = UIToolbar()
        toolbar.sizeToFit()
        toolbar.items = [
            UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil),
            UIBarButtonItem(title: "Done", style: .done,
                            target: self, action: #selector(dismissKeyboard)),
        ]
        return toolbar
    }

    @objc private func dismissKeyboard() {
        view.endEditing(true)
    }
}

// MARK: - Privacy & data

final class PrivacyViewController: UITableViewController {
    private enum Section: Int, CaseIterable {
        case promise
        case processing
        case storage
        case control
    }

    private struct Item {
        let symbol: String
        let title: String
        let detail: String
    }

    private let store: SplitStore = UserDefaultsSplitStore(suiteName: SplitContract.storageSuiteName)

    private let promiseItems = [
        Item(symbol: "keyboard", title: "Normal typing isn’t collected",
             detail: "Turtle does not transmit ordinary keystrokes, passwords, payment fields, or everything you type."),
        Item(symbol: "command", title: "Commands are deliberate",
             detail: "Text is processed only when you run a slash command or explicitly use a connected service."),
        Item(symbol: "chart.bar.xaxis", title: "No advertising tracking",
             detail: "Turtle does not use your typing to build advertising profiles or track you across other companies’ apps."),
    ]

    private let processingItems = [
        Item(symbol: "iphone", title: "On-device commands",
             detail: "When available, offline tools and Apple Intelligence process supported text commands on your device."),
        Item(symbol: "cloud", title: "Cloud commands",
             detail: "Image commands, /search, and connected services require the internet. In Auto or Cloud mode, text commands may also use a cloud model."),
        Item(symbol: "photo", title: "What gets sent",
             detail: "Only the slash-command input, relevant selected context, and any reference image you deliberately attach are sent to complete that request."),
        Item(symbol: "network", title: "What Full Access enables",
             detail: "Full Access lets the keyboard connect to AI and linked services, share settings with the Turtle app, use voice, and copy generated media."),
    ]

    private let storageItems = [
        Item(symbol: "photo.on.rectangle", title: "Image history",
             detail: "Generated images and their command prompts are saved locally in Turtle’s shared app container, up to 100 items."),
        Item(symbol: "key", title: "Account connections",
             detail: "Google Split credentials use the shared iOS Keychain. GitHub, Slack, and Notion connection tokens are stored in iOS-protected shared app storage so the keyboard can use them."),
        Item(symbol: "slider.horizontal.3", title: "Preferences and drafts",
             detail: "Themes, enabled commands, recent selections, short-lived drafts, and saved splits stay in local shared app storage."),
    ]

    init() {
        super.init(style: .insetGrouped)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Privacy & Data"
        view.backgroundColor = .systemGroupedBackground
        tableView.backgroundColor = .systemGroupedBackground
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 80
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "PrivacyItem")
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Done", style: .done, target: self, action: #selector(doneTapped))
    }

    override func numberOfSections(in tableView: UITableView) -> Int {
        Section.allCases.count
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch Section(rawValue: section)! {
        case .promise: return promiseItems.count
        case .processing: return processingItems.count
        case .storage: return storageItems.count
        case .control: return 4
        }
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch Section(rawValue: section)! {
        case .promise: return "Our promise"
        case .processing: return "How commands work"
        case .storage: return "Stored on this device"
        case .control: return "Your controls"
        }
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        guard Section(rawValue: section) == .control else { return nil }
        return "Disconnecting removes locally stored account credentials. Service providers may retain data under their own policies."
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let section = Section(rawValue: indexPath.section)!
        let cell = tableView.dequeueReusableCell(withIdentifier: "PrivacyItem", for: indexPath)

        if section == .control {
            let controls: [(String, String, UIColor)] = [
                ("photo.badge.minus", "Clear image history", .systemGreen),
                ("person.2.slash", "Clear saved splits", .systemGreen),
                ("link.badge.minus", "Disconnect all accounts", .systemOrange),
                ("trash", "Delete all local Turtle data", .systemRed),
            ]
            let control = controls[indexPath.row]
            var content = cell.defaultContentConfiguration()
            content.text = control.1
            content.textProperties.color = control.2
            content.image = UIImage(systemName: control.0)
            content.imageProperties.tintColor = control.2
            cell.contentConfiguration = content
            cell.accessoryType = .none
            cell.selectionStyle = .default
            return cell
        }

        let item: Item
        switch section {
        case .promise: item = promiseItems[indexPath.row]
        case .processing: item = processingItems[indexPath.row]
        case .storage: item = storageItems[indexPath.row]
        case .control: fatalError("Controls handled above")
        }
        var content = cell.defaultContentConfiguration()
        content.text = item.title
        content.secondaryText = item.detail
        content.textProperties.font = .preferredFont(forTextStyle: .headline)
        content.secondaryTextProperties.font = .preferredFont(forTextStyle: .subheadline)
        content.secondaryTextProperties.color = .secondaryLabel
        content.secondaryTextProperties.numberOfLines = 0
        content.image = UIImage(systemName: item.symbol)
        content.imageProperties.tintColor = .systemGreen
        content.imageProperties.maximumSize = CGSize(width: 28, height: 28)
        content.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 12, leading: 0, bottom: 12, trailing: 0)
        cell.contentConfiguration = content
        cell.selectionStyle = .none
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard Section(rawValue: indexPath.section) == .control else { return }
        switch indexPath.row {
        case 0:
            confirm(title: "Clear image history?",
                    message: "This permanently removes generated images and their saved prompts from this device.",
                    actionTitle: "Clear History") { ImageHistory.clear() }
        case 1:
            confirm(title: "Clear saved splits?",
                    message: "This permanently removes splits saved locally on this device.",
                    actionTitle: "Clear Splits") { [store] in SplitHistory(store: store).clear() }
        case 2:
            confirm(title: "Disconnect all accounts?",
                    message: "This removes locally stored Google, GitHub, Slack, and Notion credentials. Your data in those services is not deleted.",
                    actionTitle: "Disconnect") { [weak self] in self?.disconnectAllAccounts() }
        default:
            confirm(title: "Delete all local Turtle data?",
                    message: "This removes history, saved splits, connections, settings, drafts, and onboarding status from this device. This can’t be undone.",
                    actionTitle: "Delete All Data") { [weak self] in self?.deleteAllLocalData() }
        }
    }

    private func disconnectAllAccounts() {
        SplitKeychain.delete(SplitKeychain.accessTokenKey)
        SplitKeychain.delete(SplitKeychain.refreshTokenKey)
        SplitKeychain.delete(SplitKeychain.tokenExpiresAtKey)
        let keys = [
            SplitKeys.signedIn, SplitKeys.accountEmail,
            NotionKeys.accessToken, NotionKeys.workspaceName, NotionKeys.defaultParent, NotionKeys.defaultParentT,
            SlackKeys.accessToken, SlackKeys.teamName, SlackKeys.teamDomain, SlackKeys.defaultChannel, SlackKeys.defaultChannelName,
            GitHubKeys.accessToken, GitHubKeys.login,
        ]
        keys.forEach { store.setString("", forKey: $0) }
        showCompletion("Accounts disconnected")
    }

    private func deleteAllLocalData() {
        ImageHistory.clear()
        disconnectAllAccountsSilently()
        UserDefaults(suiteName: SplitContract.storageSuiteName)?.removePersistentDomain(
            forName: SplitContract.storageSuiteName)
        if let bundleID = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: bundleID)
        }
        UserDefaults(suiteName: SplitContract.storageSuiteName)?.set(
            Date().timeIntervalSince1970, forKey: "privacy.deleteExtensionDataAt")
        showCompletion("Local Turtle data deleted")
    }

    private func disconnectAllAccountsSilently() {
        SplitKeychain.delete(SplitKeychain.accessTokenKey)
        SplitKeychain.delete(SplitKeychain.refreshTokenKey)
        SplitKeychain.delete(SplitKeychain.tokenExpiresAtKey)
    }

    private func confirm(title: String, message: String, actionTitle: String, action: @escaping () -> Void) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: actionTitle, style: .destructive) { _ in action() })
        present(alert, animated: true)
    }

    private func showCompletion(_ message: String) {
        let alert = UIAlertController(title: message, message: nil, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    @objc private func doneTapped() {
        dismiss(animated: true)
    }
}

private final class KeyboardDemoView: UIView {
    private let command = UILabel()
    private let result = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .secondarySystemGroupedBackground
        layer.cornerRadius = 18
        layer.cornerCurve = .continuous
        command.font = .monospacedSystemFont(ofSize: 16, weight: .medium)
        command.textColor = .label
        command.numberOfLines = 0
        result.font = .preferredFont(forTextStyle: .body)
        result.textColor = .secondaryLabel
        result.numberOfLines = 0
        let stack = UIStackView(arrangedSubviews: [command, result])
        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -20),
            stack.centerYAnchor.constraint(equalTo: centerYAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) not used") }

    func start() {
        command.text = "/fix i dont think this are right"
        result.text = "Turtle is working…"
        if UIAccessibility.isReduceMotionEnabled {
            result.alpha = 1
            result.text = "I don’t think this is right. ✓"
            result.textColor = .systemGreen
            return
        }
        result.alpha = 0
        UIView.animate(withDuration: 0.35, delay: 0.5, options: []) { self.result.alpha = 1 } completion: { _ in
            UIView.transition(with: self.result, duration: 0.25, options: .transitionCrossDissolve) {
                self.result.text = "I don’t think this is right. ✓"
                self.result.textColor = .systemGreen
            }
        }
    }
}

private extension UITextField {
    func setLeftPadding(_ value: CGFloat) {
        let padding = UIView(frame: CGRect(x: 0, y: 0, width: value, height: 1))
        leftView = padding
        leftViewMode = .always
    }
}
