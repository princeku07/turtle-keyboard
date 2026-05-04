import Foundation
#if os(iOS)
import UIKit

/// In-keyboard saved-splits list. Mirrors the Android `SplitHistoryView`
/// flow but stays inside the panel slot so the user never leaves the host
/// app. Each row is tap-to-copy; footer offers Clear / Done / Report.
final class SplitHistoryView: UIView {

    protocol Listener: AnyObject {
        func splitHistoryDidCopy(_ entry: SplitHistory.Entry)
        func splitHistoryDidClear()
        func splitHistoryDidDismiss()
        func splitHistoryDidOpenReport()
    }

    private let headline = UILabel()
    private let listColumn = UIStackView()
    private let emptyLabel = UILabel()
    private let clearButton = UIButton(type: .system)
    private weak var listener: Listener?

    override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    private func configure() {
        backgroundColor = UIColor(red: 0.051, green: 0.247, blue: 0.071, alpha: 1.0)
        translatesAutoresizingMaskIntoConstraints = false

        let root = UIStackView()
        root.axis = .vertical
        root.spacing = 8
        root.translatesAutoresizingMaskIntoConstraints = false
        addSubview(root)
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            root.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
            root.topAnchor.constraint(equalTo: topAnchor, constant: 12),
            root.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -12),
        ])

        root.addArrangedSubview(buildHeader())

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        listColumn.axis = .vertical
        listColumn.spacing = 6
        listColumn.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(listColumn)
        NSLayoutConstraint.activate([
            listColumn.leadingAnchor.constraint(equalTo: scroll.leadingAnchor),
            listColumn.trailingAnchor.constraint(equalTo: scroll.trailingAnchor),
            listColumn.topAnchor.constraint(equalTo: scroll.topAnchor),
            listColumn.bottomAnchor.constraint(equalTo: scroll.bottomAnchor),
            listColumn.widthAnchor.constraint(equalTo: scroll.widthAnchor),
            scroll.heightAnchor.constraint(lessThanOrEqualToConstant: 200),
        ])
        root.addArrangedSubview(scroll)

        emptyLabel.text = "No splits yet — tap a payment-app chip and save one."
        emptyLabel.font = .systemFont(ofSize: 13)
        emptyLabel.textColor = UIColor(red: 0.80, green: 0.91, blue: 0.78, alpha: 1.0)
        emptyLabel.textAlignment = .center
        emptyLabel.numberOfLines = 0
        emptyLabel.isHidden = true
        root.addArrangedSubview(emptyLabel)

        root.addArrangedSubview(buildActions())
    }

    private func buildHeader() -> UIView {
        let row = UIStackView()
        row.axis = .horizontal
        row.alignment = .center

        headline.text = "Saved splits"
        headline.font = .systemFont(ofSize: 16, weight: .bold)
        headline.textColor = .white
        row.addArrangedSubview(headline)

        let spacer = UIView()
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        row.addArrangedSubview(spacer)

        let report = UIButton(type: .system)
        report.setTitle("Report ↗", for: .normal)
        report.titleLabel?.font = .systemFont(ofSize: 13)
        report.setTitleColor(UIColor(red: 0.72, green: 0.88, blue: 0.74, alpha: 1.0), for: .normal)
        report.addTarget(self, action: #selector(reportTapped), for: .touchUpInside)
        row.addArrangedSubview(report)

        return row
    }

    private func buildActions() -> UIView {
        let row = UIStackView()
        row.axis = .horizontal
        row.distribution = .fillEqually
        row.spacing = 8

        clearButton.setTitle("Clear", for: .normal)
        clearButton.setTitleColor(.white, for: .normal)
        clearButton.backgroundColor = UIColor(red: 0.047, green: 0.047, blue: 0.047, alpha: 1.0)
        clearButton.layer.cornerRadius = 8
        clearButton.addTarget(self, action: #selector(clearTapped), for: .touchUpInside)

        let done = UIButton(type: .system)
        done.setTitle("Done", for: .normal)
        done.setTitleColor(.white, for: .normal)
        done.backgroundColor = UIColor(red: 0.122, green: 0.435, blue: 0.165, alpha: 1.0)
        done.layer.cornerRadius = 8
        done.addTarget(self, action: #selector(dismissTapped), for: .touchUpInside)

        row.addArrangedSubview(clearButton)
        row.addArrangedSubview(done)
        NSLayoutConstraint.activate([row.heightAnchor.constraint(equalToConstant: 38)])
        return row
    }

    func show(entries: [SplitHistory.Entry], listener: Listener) {
        self.listener = listener
        listColumn.arrangedSubviews.forEach { $0.removeFromSuperview() }

        if entries.isEmpty {
            emptyLabel.isHidden = false
            clearButton.isEnabled = false
            clearButton.alpha = 0.5
            headline.text = "Saved splits"
            return
        }

        emptyLabel.isHidden = true
        clearButton.isEnabled = true
        clearButton.alpha = 1.0
        headline.text = "Saved splits · \(entries.count)"

        let now = Date()
        for entry in entries {
            listColumn.addArrangedSubview(buildRow(entry: entry, now: now))
        }
    }

    private func buildRow(entry: SplitHistory.Entry, now: Date) -> UIView {
        let card = UIControl()
        card.backgroundColor = UIColor(red: 0.082, green: 0.502, blue: 0.239, alpha: 1.0)
        card.layer.cornerRadius = 8
        card.translatesAutoresizingMaskIntoConstraints = false
        card.addTarget(self, action: #selector(rowTapped(_:)), for: .touchUpInside)
        card.tag = listColumn.arrangedSubviews.count // index into the visible list

        let amount = UILabel()
        amount.text = "₹\(SplitPanelView.formatAmount(entry.amount))"
        amount.font = .systemFont(ofSize: 16, weight: .bold)
        amount.textColor = .white

        let per = entry.people > 0 ? entry.amount / Double(entry.people) : entry.amount
        let when = Self.relativeTime(timestampMs: entry.timestampMs, now: now)
        let meta = UILabel()
        meta.text = "\(entry.people) \(entry.people == 1 ? "person" : "people") · ₹\(SplitPanelView.formatAmount(per)) each · \(when)"
        meta.font = .systemFont(ofSize: 12)
        meta.textColor = UIColor(red: 0.80, green: 0.91, blue: 0.78, alpha: 1.0)
        meta.numberOfLines = 1

        let stack = UIStackView(arrangedSubviews: [amount, meta])
        stack.axis = .vertical
        stack.spacing = 2
        stack.isUserInteractionEnabled = false
        stack.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 10),
            stack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -10),
            stack.topAnchor.constraint(equalTo: card.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -8),
        ])

        return card
    }

    @objc private func rowTapped(_ sender: UIControl) {
        // Re-pull entries from the live store via the listener pattern would
        // be cleaner, but we already snapshotted into the visible rows. Map
        // the tag to the on-screen index and ask the host to copy.
        let index = sender.tag
        guard let entries = currentEntries, index < entries.count else { return }
        listener?.splitHistoryDidCopy(entries[index])
    }

    @objc private func clearTapped() { listener?.splitHistoryDidClear() }
    @objc private func dismissTapped() { listener?.splitHistoryDidDismiss() }
    @objc private func reportTapped() { listener?.splitHistoryDidOpenReport() }

    /// Snapshot of the entries shown, kept so row taps can resolve back to
    /// an entry without re-querying the store.
    private var currentEntries: [SplitHistory.Entry]?

    func setSnapshot(_ entries: [SplitHistory.Entry]) {
        self.currentEntries = entries
    }

    private static func relativeTime(timestampMs: Int64, now: Date) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: now)
    }
}
#endif
