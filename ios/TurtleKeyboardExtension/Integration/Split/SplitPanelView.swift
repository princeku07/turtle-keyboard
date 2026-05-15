import Foundation
#if os(iOS)
import UIKit

/// In-keyboard split sheet. Shown above the keys when the user taps the
/// integration chip or runs `/split <amount>` — lets them pick a head-count
/// and save without leaving the host. Direct port of Android's
/// `SplitPanelView`.
final class SplitPanelView: UIView {

    protocol Listener: AnyObject {
        func splitPanelDidSave(amount: Double, people: Int)
        func splitPanelDidCancel()
    }

    private let headline = UILabel()
    private let countText = UILabel()
    private let perPersonText = UILabel()
    private weak var listener: Listener?

    private var amount: Double = 0
    private var people: Int = SplitContract.defaultPeople

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

        let stack = UIStackView()
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)

        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 12),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -12),
        ])

        headline.font = .systemFont(ofSize: 16, weight: .semibold)
        headline.textColor = .white
        headline.textAlignment = .center
        stack.addArrangedSubview(headline)

        stack.addArrangedSubview(buildStepper())

        perPersonText.font = .systemFont(ofSize: 14)
        perPersonText.textColor = UIColor(red: 0.80, green: 0.91, blue: 0.78, alpha: 1.0)
        perPersonText.textAlignment = .center
        stack.addArrangedSubview(perPersonText)

        stack.addArrangedSubview(buildActions())
    }

    private func buildStepper() -> UIView {
        let row = UIStackView()
        row.axis = .horizontal
        row.alignment = .center
        row.distribution = .equalSpacing
        row.spacing = 12

        let minus = pillButton(title: "−")
        minus.addTarget(self, action: #selector(decrement), for: .touchUpInside)
        countText.font = .systemFont(ofSize: 18, weight: .semibold)
        countText.textColor = .white
        countText.textAlignment = .center
        countText.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let plus = pillButton(title: "+")
        plus.addTarget(self, action: #selector(increment), for: .touchUpInside)

        row.addArrangedSubview(minus)
        row.addArrangedSubview(countText)
        row.addArrangedSubview(plus)

        NSLayoutConstraint.activate([
            minus.widthAnchor.constraint(equalToConstant: 48),
            minus.heightAnchor.constraint(equalToConstant: 36),
            plus.widthAnchor.constraint(equalToConstant: 48),
            plus.heightAnchor.constraint(equalToConstant: 36),
        ])

        return row
    }

    private func buildActions() -> UIView {
        let row = UIStackView()
        row.axis = .horizontal
        row.distribution = .fillEqually
        row.spacing = 8

        let cancel = pillButton(title: "Cancel")
        cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        let save = pillButton(title: "Save")
        save.backgroundColor = UIColor(red: 0.122, green: 0.435, blue: 0.165, alpha: 1.0)
        save.addTarget(self, action: #selector(saveTapped), for: .touchUpInside)

        row.addArrangedSubview(cancel)
        row.addArrangedSubview(save)
        NSLayoutConstraint.activate([
            row.heightAnchor.constraint(equalToConstant: 38),
        ])
        return row
    }

    private func pillButton(title: String) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle(title, for: .normal)
        b.setTitleColor(.white, for: .normal)
        b.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        b.backgroundColor = UIColor(red: 0.082, green: 0.502, blue: 0.239, alpha: 1.0)
        b.layer.cornerRadius = 8
        b.translatesAutoresizingMaskIntoConstraints = false
        return b
    }

    func show(rawAmount: String, defaultPeople: Int, listener: Listener) {
        self.amount = Double(rawAmount) ?? 0
        self.people = max(SplitContract.minPeople, min(SplitContract.maxPeople, defaultPeople))
        self.listener = listener
        refresh()
        isHidden = false
    }

    func hide() {
        listener = nil
        isHidden = true
    }

    @objc private func decrement() {
        guard people > SplitContract.minPeople else { return }
        people -= 1
        refresh()
    }

    @objc private func increment() {
        guard people < SplitContract.maxPeople else { return }
        people += 1
        refresh()
    }

    @objc private func cancelTapped() {
        listener?.splitPanelDidCancel()
    }

    @objc private func saveTapped() {
        listener?.splitPanelDidSave(amount: amount, people: people)
    }

    private func refresh() {
        headline.text = "Split ₹\(Self.formatAmount(amount))"
        countText.text = "\(people) \(people == 1 ? "person" : "people")"
        let per = people > 0 ? amount / Double(people) : amount
        perPersonText.text = "₹\(Self.formatAmount(per)) each"
    }

    static func formatAmount(_ v: Double) -> String {
        if v == v.rounded(), v.isFinite { return String(Int64(v)) }
        return String(format: "%.2f", v)
    }
}
#endif
