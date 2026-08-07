import UIKit

/// Native results sheet for `turtlekeyboard://poll/<id>`. Mirrors Android's
/// `PollSheetView` — fetches poll state from the worker, renders the
/// question + option list with vote counts, lets the user vote (one vote
/// per device, deduped server-side via `X-Turtle-Device`), then re-fetches
/// to show updated counts.
///
/// Reachable from `AppDelegate.route(url:)` when a user taps a
/// `turtlekeyboard://poll/<id>` link or the in-app share link.
final class PollSheetViewController: UIViewController {

    private let brandGreen = UIColor.systemGreen
    private let cardGreen  = UIColor.secondarySystemGroupedBackground

    private let pollId: String
    private let store: SplitStore = UserDefaultsSplitStore(suiteName: SplitContract.storageSuiteName)

    private let scroll = UIScrollView()
    private let content = UIStackView()
    private let questionLabel = UILabel()
    private let statusLabel = UILabel()
    private let optionsStack = UIStackView()
    private let footerLabel = UILabel()

    private var currentPoll: PollClient.Poll?
    private var hasVoted = false
    private var isVoting = false

    init(pollId: String) {
        self.pollId = pollId
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) not used") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground
        title = "Poll"
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Done", style: .done, target: self, action: #selector(dismissTapped))

        scroll.translatesAutoresizingMaskIntoConstraints = false
        content.axis = .vertical
        content.spacing = 12
        content.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(scroll)
        scroll.addSubview(content)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            content.topAnchor.constraint(equalTo: scroll.topAnchor, constant: 20),
            content.leadingAnchor.constraint(equalTo: scroll.leadingAnchor, constant: 20),
            content.trailingAnchor.constraint(equalTo: scroll.trailingAnchor, constant: -20),
            content.bottomAnchor.constraint(equalTo: scroll.bottomAnchor, constant: -20),
            content.widthAnchor.constraint(equalTo: scroll.widthAnchor, constant: -40),
        ])

        questionLabel.font = .boldSystemFont(ofSize: 22)
        questionLabel.textColor = .label
        questionLabel.numberOfLines = 0
        questionLabel.text = "Loading…"

        statusLabel.font = .systemFont(ofSize: 13)
        statusLabel.textColor = .secondaryLabel
        statusLabel.numberOfLines = 0

        optionsStack.axis = .vertical
        optionsStack.spacing = 10

        footerLabel.font = .systemFont(ofSize: 11)
        footerLabel.textColor = .tertiaryLabel
        footerLabel.numberOfLines = 0
        footerLabel.textAlignment = .center
        footerLabel.text = "Poll ID · \(pollId)"

        content.addArrangedSubview(questionLabel)
        content.addArrangedSubview(statusLabel)
        content.addArrangedSubview(optionsStack)
        content.addArrangedSubview(footerLabel)

        refresh()
    }

    // MARK: - Networking

    private func refresh() {
        statusLabel.text = "Fetching results…"
        Task { @MainActor in
            do {
                let poll = try await PollClient.readPoll(id: pollId)
                self.currentPoll = poll
                self.render(poll: poll)
            } catch {
                self.statusLabel.text = "Couldn't load poll: \(error.localizedDescription)"
            }
        }
    }

    private func vote(optionIndex: Int) {
        guard !isVoting, !hasVoted else { return }
        isVoting = true
        statusLabel.text = "Submitting vote…"
        let deviceId = ensureDeviceId()
        Task { @MainActor in
            do {
                try await PollClient.vote(pollId: pollId,
                                          optionIndex: optionIndex,
                                          deviceId: deviceId)
                self.hasVoted = true
                self.isVoting = false
                self.refresh()
            } catch {
                self.isVoting = false
                self.statusLabel.text = "Vote failed: \(error.localizedDescription)"
            }
        }
    }

    private func ensureDeviceId() -> String {
        let existing = store.string(forKey: SplitKeys.deviceId, fallback: "")
        if !existing.isEmpty { return existing }
        let id = UUID().uuidString
        store.setString(id, forKey: SplitKeys.deviceId)
        return id
    }

    // MARK: - Render

    private func render(poll: PollClient.Poll) {
        questionLabel.text = poll.question
        let total = poll.options.reduce(0) { $0 + $1.votes }
        statusLabel.text = total == 0
            ? "No votes yet — be the first."
            : "\(total) vote\(total == 1 ? "" : "s") · tap an option to add yours."

        optionsStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for (i, option) in poll.options.enumerated() {
            optionsStack.addArrangedSubview(buildOptionRow(index: i, option: option, total: total))
        }
    }

    private func buildOptionRow(index: Int, option: PollClient.Option, total: Int) -> UIView {
        let row = UIControl()
        row.backgroundColor = cardGreen
        row.layer.cornerRadius = 12
        row.layer.cornerCurve = .continuous
        row.translatesAutoresizingMaskIntoConstraints = false
        row.heightAnchor.constraint(greaterThanOrEqualToConstant: 56).isActive = true

        let label = UILabel()
        label.text = option.label
        label.font = .systemFont(ofSize: 15, weight: .semibold)
        label.textColor = .label
        label.numberOfLines = 0
        label.translatesAutoresizingMaskIntoConstraints = false

        let count = UILabel()
        let pct = total == 0 ? 0 : Int(round(Double(option.votes) / Double(total) * 100))
        count.text = "\(option.votes) · \(pct)%"
        count.font = .systemFont(ofSize: 13, weight: .medium)
        count.textColor = .secondaryLabel
        count.translatesAutoresizingMaskIntoConstraints = false

        row.addSubview(label)
        row.addSubview(count)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 14),
            label.topAnchor.constraint(equalTo: row.topAnchor, constant: 10),
            label.bottomAnchor.constraint(equalTo: row.bottomAnchor, constant: -10),
            label.trailingAnchor.constraint(equalTo: count.leadingAnchor, constant: -12),
            count.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -14),
            count.centerYAnchor.constraint(equalTo: row.centerYAnchor),
        ])

        row.addAction(UIAction { [weak self] _ in
            self?.vote(optionIndex: index)
        }, for: .touchUpInside)

        // Visual treatment for already-voted state
        if hasVoted { row.alpha = 0.85 }
        return row
    }

    @objc private func dismissTapped() { dismiss(animated: true) }
}
