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
        // `KeyboardPalette.bg` is `.clear` on light/dark themes (floating
        // glass), so a plain backgroundColor would let key taps through.
        // Mirror the Quick Panel fix: opaque blur backdrop that adapts to
        // the system appearance regardless of theme.
        backgroundColor = .clear
        translatesAutoresizingMaskIntoConstraints = false

        let backdrop = UIVisualEffectView(effect: UIBlurEffect(style: .systemChromeMaterial))
        backdrop.translatesAutoresizingMaskIntoConstraints = false
        addSubview(backdrop)
        NSLayoutConstraint.activate([
            backdrop.topAnchor.constraint(equalTo: topAnchor),
            backdrop.leadingAnchor.constraint(equalTo: leadingAnchor),
            backdrop.trailingAnchor.constraint(equalTo: trailingAnchor),
            backdrop.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])

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
        headline.textColor = KeyboardPalette.keyText
        headline.textAlignment = .center
        stack.addArrangedSubview(headline)

        stack.addArrangedSubview(buildStepper())

        perPersonText.font = .systemFont(ofSize: 14)
        perPersonText.textColor = KeyboardPalette.keyText.withAlphaComponent(0.7)
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
        countText.textColor = KeyboardPalette.keyText
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
        let save = pillButton(title: "Save", primary: true)
        save.addTarget(self, action: #selector(saveTapped), for: .touchUpInside)

        row.addArrangedSubview(cancel)
        row.addArrangedSubview(save)
        NSLayoutConstraint.activate([
            row.heightAnchor.constraint(equalToConstant: 38),
        ])
        return row
    }

    /// Pill button — `primary == true` paints the action with the theme
    /// accent colour, otherwise it uses the `keySpecial` surface that
    /// matches the keyboard's modifier-key palette. Saves us from baking
    /// any specific green into the panel.
    private func pillButton(title: String, primary: Bool = false) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle(title, for: .normal)
        b.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        if primary {
            b.backgroundColor = KeyboardPalette.accent
            b.setTitleColor(.white, for: .normal)
        } else {
            b.backgroundColor = KeyboardPalette.keySpecial
            b.setTitleColor(KeyboardPalette.keyTextSpecial, for: .normal)
        }
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
        headline.text = "Split ₹\(SplitContract.formatAmount(amount))"
        countText.text = "\(people) \(people == 1 ? "person" : "people")"
        let per = people > 0 ? amount / Double(people) : amount
        perPersonText.text = "₹\(SplitContract.formatAmount(per)) each"
    }
}
#endif
