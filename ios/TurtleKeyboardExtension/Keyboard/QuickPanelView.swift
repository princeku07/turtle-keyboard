import Foundation
#if os(iOS)
import UIKit

/// Tap-driven grid of every registered slash command. Triggered by
/// double-tap space inside the keyboard (PRD §6.6) — complements the
/// `/`-typed primitive with a discoverable picker. Tap a command:
///   - `needsPrompt == true`  → opens the command bar with that command
///                              active and an empty prompt for the user
///                              to type or dictate.
///   - `needsPrompt == false` → fires the command immediately.
final class QuickPanelView: UIView {

    /// Notified when the user picks a command (or dismisses the panel).
    weak var onSelect: QuickPanelDelegate?

    private let scroll = UIScrollView()
    private let grid = UIStackView()

    /// Number of tile columns. iPad gets a wider grid since there's room.
    private let columns: Int

    init(columns: Int) {
        self.columns = columns
        super.init(frame: .zero)
        configure()
    }

    required init?(coder: NSCoder) {
        self.columns = 4
        super.init(coder: coder)
        configure()
    }

    private func configure() {
        backgroundColor = KeyboardPalette.bg
        translatesAutoresizingMaskIntoConstraints = false

        let header = UILabel()
        header.text = "Pick a command"
        header.font = .systemFont(ofSize: 12, weight: .semibold)
        // Theme-aware — light theme uses dark ink, dark/turtle use white.
        header.textColor = KeyboardPalette.keyText.withAlphaComponent(0.7)
        header.translatesAutoresizingMaskIntoConstraints = false

        let dismiss = UIButton(type: .system)
        dismiss.setImage(UIImage(systemName: "xmark"), for: .normal)
        dismiss.tintColor = KeyboardPalette.keyText.withAlphaComponent(0.7)
        dismiss.addTarget(self, action: #selector(dismissTapped), for: .touchUpInside)
        dismiss.translatesAutoresizingMaskIntoConstraints = false

        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.showsVerticalScrollIndicator = false
        grid.axis = .vertical
        grid.spacing = 8
        grid.alignment = .fill
        grid.translatesAutoresizingMaskIntoConstraints = false

        addSubview(header)
        addSubview(dismiss)
        addSubview(scroll)
        scroll.addSubview(grid)

        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: topAnchor, constant: 8),
            header.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),

            dismiss.centerYAnchor.constraint(equalTo: header.centerYAnchor),
            dismiss.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -10),
            dismiss.widthAnchor.constraint(equalToConstant: 28),
            dismiss.heightAnchor.constraint(equalToConstant: 28),

            scroll.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 6),
            scroll.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 10),
            scroll.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -10),
            scroll.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -8),

            grid.topAnchor.constraint(equalTo: scroll.topAnchor),
            grid.leadingAnchor.constraint(equalTo: scroll.leadingAnchor),
            grid.trailingAnchor.constraint(equalTo: scroll.trailingAnchor),
            grid.bottomAnchor.constraint(equalTo: scroll.bottomAnchor),
            grid.widthAnchor.constraint(equalTo: scroll.widthAnchor),
        ])
    }

    /// Replace the grid with `commands`. Lays them out left-to-right,
    /// top-to-bottom in `columns`-wide rows, padding the last row with
    /// invisible spacers so tile widths stay consistent.
    func show(_ commands: [SlashCommand]) {
        grid.arrangedSubviews.forEach { $0.removeFromSuperview() }
        var i = 0
        while i < commands.count {
            let row = UIStackView()
            row.axis = .horizontal
            row.spacing = 8
            row.distribution = .fillEqually
            row.alignment = .fill
            for col in 0..<columns {
                let idx = i + col
                if idx < commands.count {
                    row.addArrangedSubview(buildTile(for: commands[idx]))
                } else {
                    let spacer = UIView()
                    spacer.translatesAutoresizingMaskIntoConstraints = false
                    row.addArrangedSubview(spacer)
                }
            }
            NSLayoutConstraint.activate([row.heightAnchor.constraint(equalToConstant: 64)])
            grid.addArrangedSubview(row)
            i += columns
        }
    }

    private func buildTile(for cmd: SlashCommand) -> UIView {
        let tile = UIControl()
        tile.backgroundColor = KeyboardPalette.keyNormal
        tile.layer.cornerRadius = 8
        tile.translatesAutoresizingMaskIntoConstraints = false

        let emoji = UILabel()
        emoji.text = cmd.emoji
        emoji.font = .systemFont(ofSize: 22)
        emoji.textAlignment = .center

        let name = UILabel()
        name.text = "/\(cmd.rawValue)"
        name.font = .monospacedSystemFont(ofSize: 11, weight: .medium)
        // Tile background is `KeyboardPalette.keyNormal` (white in the
        // Light theme), so a hardcoded white label is invisible there.
        // `keyText` is the matching glyph colour — dark ink on light
        // tiles, white on dark tiles.
        name.textColor = KeyboardPalette.keyText
        name.textAlignment = .center

        let stack = UIStackView(arrangedSubviews: [emoji, name])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 2
        stack.isUserInteractionEnabled = false
        stack.translatesAutoresizingMaskIntoConstraints = false
        tile.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: tile.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: tile.centerYAnchor),
        ])

        // Cheap visual press feedback.
        tile.addAction(UIAction { [weak self, weak tile] _ in
            tile?.alpha = 0.6
            UIView.animate(withDuration: 0.15) { tile?.alpha = 1.0 }
            self?.onSelect?.quickPanelDidSelect(cmd)
        }, for: .touchUpInside)
        return tile
    }

    @objc private func dismissTapped() {
        onSelect?.quickPanelDidDismiss()
    }
}

protocol QuickPanelDelegate: AnyObject {
    func quickPanelDidSelect(_ command: SlashCommand)
    func quickPanelDidDismiss()
}
#endif
