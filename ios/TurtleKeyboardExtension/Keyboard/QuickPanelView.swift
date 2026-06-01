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

    /// Opaque scrim that fills the entire panel. The light + dark themes
    /// set `KeyboardPalette.bg = .clear` (floating-glass keyboard look),
    /// so without this view the Quick Panel was visually transparent and
    /// — worse — taps fell through to the key rows mounted underneath
    /// the integration-panel host. The blur material guarantees an
    /// opaque, theme-adapting backdrop that always intercepts touches.
    private let backdrop = UIVisualEffectView(effect: UIBlurEffect(style: .systemChromeMaterial))
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
        backgroundColor = .clear
        isUserInteractionEnabled = true
        translatesAutoresizingMaskIntoConstraints = false

        // Mount the opaque backdrop first so every other subview sits on
        // top of it. Without this, taps fall through to the keys behind
        // the panel on themes whose `bg` is `.clear` (light/dark).
        backdrop.translatesAutoresizingMaskIntoConstraints = false
        backdrop.isUserInteractionEnabled = true
        addSubview(backdrop)
        NSLayoutConstraint.activate([
            backdrop.topAnchor.constraint(equalTo: topAnchor),
            backdrop.leadingAnchor.constraint(equalTo: leadingAnchor),
            backdrop.trailingAnchor.constraint(equalTo: trailingAnchor),
            backdrop.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])

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
            // Trailing -2 keeps the 22pt glyph optically ~10pt from the
            // edge while the 44×44 hit region satisfies §4.4's minimum
            // touch target (the glyph stays small; only the tap area grows).
            dismiss.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -2),
            dismiss.widthAnchor.constraint(equalToConstant: 44),
            dismiss.heightAnchor.constraint(equalToConstant: 44),

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
        // Tile is a transparent UIControl that owns the touch / press
        // animation. The visual fill is a sibling LiquidGlassBackingView
        // pinned to the tile's bounds — same iOS 26 Liquid Glass tile
        // shape as the keyboard keys. `interactive: true` opts into
        // UIGlassEffect's built-in tap bounce on iOS 26.
        let tile = UIControl()
        tile.backgroundColor = .clear
        tile.translatesAutoresizingMaskIntoConstraints = false

        let backing = LiquidGlassBackingView(
            cornerRadius: 10,
            tintColor: KeyboardPalette.keyNormal,
            blurStyle: .systemThinMaterial,
            translucent: true,
            interactive: true
        )
        backing.translatesAutoresizingMaskIntoConstraints = false
        tile.addSubview(backing)
        NSLayoutConstraint.activate([
            backing.topAnchor.constraint(equalTo: tile.topAnchor),
            backing.leadingAnchor.constraint(equalTo: tile.leadingAnchor),
            backing.trailingAnchor.constraint(equalTo: tile.trailingAnchor),
            backing.bottomAnchor.constraint(equalTo: tile.bottomAnchor),
        ])

        let emoji = UILabel()
        emoji.text = cmd.emoji
        emoji.font = .systemFont(ofSize: 22)
        emoji.textAlignment = .center

        let name = UILabel()
        name.text = "/\(cmd.rawValue)"
        name.font = .monospacedSystemFont(ofSize: 11, weight: .medium)
        // `keyText` is the theme's matching glyph colour — dark ink on
        // light tiles, white on dark tiles.
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

        // Cheap visual press feedback (works on both iOS 15 fallback
        // and iOS 26 native paths — UIGlassEffect's bounce supplements
        // this on 26 rather than replacing it).
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
