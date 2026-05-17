import Foundation
#if os(iOS)
import UIKit

/// iOS port of Android's `CommandSuggestionStripView`. Mounts above the
/// keys while the user is composing a slash command, surfacing every
/// matching `SlashCommand` as a tappable pill. Horizontal scroll handles
/// overflow without affecting keyboard height.
///
/// Wispr-style behaviour:
///   • Black background, translucent-white pills, light glyphs.
///   • One row, scrolls horizontally — never expands the keyboard.
///   • Tap a pill → commits that command (same path as `Send` in the
///     single-match ghost mode).
///
/// Colours mirror the Android strip exactly so the two keyboards feel
/// the same when toggled between platforms.
final class CommandSuggestionStripView: UIScrollView {

    /// Called when the user taps a pill. The string is the bare command
    /// name (no leading `/`), matching Android's `OnPickListener.onPick`.
    var onPick: ((SlashCommand) -> Void)?

    private let row = UIStackView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
    }
    required init?(coder: NSCoder) { fatalError() }

    /// Restamp every styled subview from the current `KeyboardPalette`.
    /// Called from `KeyboardViewController.applyTheme()` so the strip
    /// matches the rest of the keyboard whenever the user (or system
    /// Dark Mode) flips themes.
    func applyTheme() {
        backgroundColor = KeyboardPalette.barBg
        for pill in row.arrangedSubviews {
            pill.backgroundColor = KeyboardPalette.chipBg
            for sub in pill.subviews {
                if let label = sub as? UILabel {
                    label.textColor = KeyboardPalette.chipText
                }
            }
        }
    }

    private func configure() {
        backgroundColor = KeyboardPalette.barBg
        showsHorizontalScrollIndicator = false
        showsVerticalScrollIndicator = false
        translatesAutoresizingMaskIntoConstraints = false
        isHidden = true

        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 6
        row.layoutMargins = UIEdgeInsets(top: 6, left: 8, bottom: 6, right: 8)
        row.isLayoutMarginsRelativeArrangement = true
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)

        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: contentLayoutGuide.topAnchor),
            row.leadingAnchor.constraint(equalTo: contentLayoutGuide.leadingAnchor),
            row.trailingAnchor.constraint(equalTo: contentLayoutGuide.trailingAnchor),
            row.bottomAnchor.constraint(equalTo: contentLayoutGuide.bottomAnchor),
            row.heightAnchor.constraint(equalTo: frameLayoutGuide.heightAnchor),
        ])
    }

    /// Replace the strip with `matches`. Hides the view if empty so it
    /// doesn't occupy layout space.
    func show(_ matches: [SlashCommand]) {
        row.arrangedSubviews.forEach { $0.removeFromSuperview() }
        guard !matches.isEmpty else {
            isHidden = true
            return
        }
        for cmd in matches { row.addArrangedSubview(makePill(for: cmd)) }
        contentOffset = .zero
        isHidden = false
    }

    func hide() { isHidden = true }

    private func makePill(for cmd: SlashCommand) -> UIView {
        let pill = UIControl()
        pill.backgroundColor = KeyboardPalette.chipBg
        pill.layer.cornerRadius = 14
        pill.translatesAutoresizingMaskIntoConstraints = false

        let label = UILabel()
        label.text = "\(cmd.emoji)  /\(cmd.rawValue)"
        label.textColor = KeyboardPalette.chipText
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.translatesAutoresizingMaskIntoConstraints = false
        label.isUserInteractionEnabled = false
        pill.addSubview(label)

        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: pill.leadingAnchor, constant: 12),
            label.trailingAnchor.constraint(equalTo: pill.trailingAnchor, constant: -12),
            label.topAnchor.constraint(equalTo: pill.topAnchor, constant: 6),
            label.bottomAnchor.constraint(equalTo: pill.bottomAnchor, constant: -6),
        ])

        pill.addAction(UIAction { [weak self, weak pill] _ in
            pill?.alpha = 0.6
            UIView.animate(withDuration: 0.15) { pill?.alpha = 1.0 }
            self?.onPick?(cmd)
        }, for: .touchUpInside)
        return pill
    }
}
#endif
