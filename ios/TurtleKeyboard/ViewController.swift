import UIKit

/// The host app is a companion to the keyboard, so its home screen follows the
/// same structure as Apple's Settings apps: setup first, then the things a
/// person can manage. The keyboard itself is intentionally not touched here.
final class ViewController: UITableViewController {

    private enum Section: Int, CaseIterable {
        case setup
        case keyboard
        case connections
    }

    private enum Destination: CaseIterable {
        case personalize
        case splits
        case history
        case github
        case notion
        case slack

        var title: String {
            switch self {
            case .personalize: return "Keyboard settings"
            case .splits:      return "Saved splits"
            case .history:     return "Image history"
            case .github:      return "GitHub"
            case .notion:      return "Notion"
            case .slack:       return "Slack"
            }
        }

        var subtitle: String {
            switch self {
            case .personalize: return "Commands, voice, AI, and appearance"
            case .splits:      return "View and share expenses"
            case .history:     return "Find images created with Turtle"
            case .github:      return "Connect your GitHub account"
            case .notion:      return "Send notes to your workspace"
            case .slack:       return "Send messages from the keyboard"
            }
        }

        var symbol: String {
            switch self {
            case .personalize: return "keyboard"
            case .splits:      return "person.2"
            case .history:     return "photo.on.rectangle.angled"
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

    // MARK: - Table structure

    override func numberOfSections(in tableView: UITableView) -> Int {
        Section.allCases.count
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch Section(rawValue: section)! {
        case .setup:       return 1
        case .keyboard:    return keyboardRows.count
        case .connections: return connectionRows.count
        }
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch Section(rawValue: section)! {
        case .setup:       return nil
        case .keyboard:    return "Your keyboard"
        case .connections: return "Connections"
        }
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        switch Section(rawValue: section)! {
        case .setup:
            return "After enabling Turtle, touch and hold the globe key in any app to switch keyboards."
        case .keyboard:
            return "Changes apply the next time Turtle Keyboard appears."
        case .connections:
            return "Connected services let slash commands work without leaving your conversation."
        }
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let section = Section(rawValue: indexPath.section)!
        if section == .setup {
            let cell = tableView.dequeueReusableCell(withIdentifier: SetupCell.reuseIdentifier,
                                                     for: indexPath) as! SetupCell
            cell.onOpenSettings = { [weak self] in self?.openKeyboardSettings() }
            return cell
        }

        let destination = section == .keyboard
            ? keyboardRows[indexPath.row]
            : connectionRows[indexPath.row]
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
        cell.accessibilityHint = "Opens (destination.title)"
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard let section = Section(rawValue: indexPath.section), section != .setup else { return }
        let destination = section == .keyboard
            ? keyboardRows[indexPath.row]
            : connectionRows[indexPath.row]
        present(destination)
    }

    private func present(_ destination: Destination) {
        let controller: UIViewController
        switch destination {
        case .personalize: controller = PersonalizationViewController()
        case .splits:      controller = SplitDetailViewController()
        case .history:     controller = HistoryViewController()
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
        UIApplication.shared.open(url)
    }
}

private final class SetupCell: UITableViewCell {
    static let reuseIdentifier = "SetupCell"

    var onOpenSettings: (() -> Void)?

    private let iconContainer = UIView()
    private let iconLabel = UILabel()
    private let titleLabel = UILabel()
    private let bodyLabel = UILabel()
    private let stepsStack = UIStackView()
    private let settingsButton = UIButton(type: .system)

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

        iconLabel.text = "🐢"
        iconLabel.font = .systemFont(ofSize: 34)
        iconLabel.textAlignment = .center
        iconLabel.translatesAutoresizingMaskIntoConstraints = false
        iconContainer.addSubview(iconLabel)

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

        let stack = UIStackView(arrangedSubviews: [iconContainer, titleLabel, bodyLabel, stepsStack, settingsButton])
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = 12
        stack.setCustomSpacing(16, after: bodyLabel)
        stack.setCustomSpacing(20, after: stepsStack)
        stack.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(stack)

        NSLayoutConstraint.activate([
            iconContainer.widthAnchor.constraint(equalToConstant: 72),
            iconContainer.heightAnchor.constraint(equalToConstant: 72),
            iconContainer.centerXAnchor.constraint(equalTo: stack.centerXAnchor),
            iconLabel.centerXAnchor.constraint(equalTo: iconContainer.centerXAnchor),
            iconLabel.centerYAnchor.constraint(equalTo: iconContainer.centerYAnchor),
            settingsButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 50),
            stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 24),
            stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -24),
        ])
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
        onOpenSettings?()
    }
}
