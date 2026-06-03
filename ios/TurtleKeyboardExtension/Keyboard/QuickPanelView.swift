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
///
/// Long-press a tile and drag to reorder the grid; the new order is
/// reported via `onReorder` so the controller can persist it.
final class QuickPanelView: UIView,
                            UICollectionViewDataSource,
                            UICollectionViewDelegateFlowLayout {

    /// Notified when the user picks a command (or dismisses the panel).
    weak var onSelect: QuickPanelDelegate?

    /// Fired after a drag-to-reorder settles, with the full command list
    /// in its new order. The controller persists this to the App Group.
    var onReorder: (([SlashCommand]) -> Void)?

    /// Opaque scrim that fills the entire panel. The light + dark themes
    /// set `KeyboardPalette.bg = .clear` (floating-glass keyboard look),
    /// so without this view the Quick Panel was visually transparent and
    /// — worse — taps fell through to the key rows mounted underneath
    /// the integration-panel host. The blur material guarantees an
    /// opaque, theme-adapting backdrop that always intercepts touches.
    private let backdrop = UIVisualEffectView(effect: UIBlurEffect(style: .systemChromeMaterial))
    private let collectionView: UICollectionView
    private let header = UILabel()

    /// Number of tile columns. iPad gets a wider grid since there's room.
    private let columns: Int
    /// Backing order, mutated live during drag-to-reorder.
    private var commands: [SlashCommand] = []
    /// Tracks the collection-view width the flow layout was sized for, so
    /// `layoutSubviews` only invalidates when it actually changes.
    private var lastLayoutWidth: CGFloat = 0

    private static let interitem: CGFloat = 8
    private static let tileHeight: CGFloat = 64

    init(columns: Int) {
        self.columns = columns
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = Self.interitem
        layout.minimumLineSpacing = Self.interitem
        self.collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)
        super.init(frame: .zero)
        configure()
    }

    required init?(coder: NSCoder) {
        self.columns = 4
        let layout = UICollectionViewFlowLayout()
        self.collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)
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

        // "Hold & drag to reorder" doubles as the affordance hint for the
        // new gesture — the panel was previously labelled "Pick a command".
        header.text = "Pick a command · hold & drag to reorder"
        header.font = .systemFont(ofSize: 12, weight: .semibold)
        // Theme-aware — light theme uses dark ink, dark/turtle use white.
        header.textColor = KeyboardPalette.keyText.withAlphaComponent(0.7)
        header.translatesAutoresizingMaskIntoConstraints = false
        header.adjustsFontSizeToFitWidth = true
        header.minimumScaleFactor = 0.8

        let dismiss = UIButton(type: .system)
        dismiss.setImage(UIImage(systemName: "xmark"), for: .normal)
        dismiss.tintColor = KeyboardPalette.keyText.withAlphaComponent(0.7)
        dismiss.addTarget(self, action: #selector(dismissTapped), for: .touchUpInside)
        dismiss.translatesAutoresizingMaskIntoConstraints = false

        collectionView.translatesAutoresizingMaskIntoConstraints = false
        collectionView.backgroundColor = .clear
        collectionView.showsVerticalScrollIndicator = false
        collectionView.alwaysBounceVertical = true
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.register(QuickPanelCell.self,
                                forCellWithReuseIdentifier: QuickPanelCell.reuseID)

        // Long-press drives interactive reordering. 0.35s is snappy enough
        // that the gesture feels intentional but doesn't fight a quick tap
        // (which routes through `didSelectItemAt` instead).
        let reorder = UILongPressGestureRecognizer(
            target: self, action: #selector(handleReorderGesture(_:)))
        reorder.minimumPressDuration = 0.35
        collectionView.addGestureRecognizer(reorder)

        addSubview(header)
        addSubview(dismiss)
        addSubview(collectionView)

        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: topAnchor, constant: 8),
            header.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            header.trailingAnchor.constraint(equalTo: dismiss.leadingAnchor, constant: -8),

            dismiss.centerYAnchor.constraint(equalTo: header.centerYAnchor),
            // Trailing -2 keeps the 22pt glyph optically ~10pt from the
            // edge while the 44×44 hit region satisfies §4.4's minimum
            // touch target (the glyph stays small; only the tap area grows).
            dismiss.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -2),
            dismiss.widthAnchor.constraint(equalToConstant: 44),
            dismiss.heightAnchor.constraint(equalToConstant: 44),

            collectionView.topAnchor.constraint(equalTo: header.bottomAnchor, constant: 6),
            collectionView.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 10),
            collectionView.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -10),
            collectionView.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -8),
        ])
    }

    /// Replace the grid with `commands`, laid out left-to-right,
    /// top-to-bottom in `columns`-wide rows.
    func show(_ commands: [SlashCommand]) {
        self.commands = commands
        collectionView.reloadData()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // Tile width is derived from the collection view's width; recompute
        // the flow layout whenever that changes (first layout, rotation).
        let w = collectionView.bounds.width
        if abs(w - lastLayoutWidth) > 0.5 {
            lastLayoutWidth = w
            collectionView.collectionViewLayout.invalidateLayout()
        }
    }

    // MARK: - Reorder gesture

    @objc private func handleReorderGesture(_ g: UILongPressGestureRecognizer) {
        switch g.state {
        case .began:
            guard let ip = collectionView.indexPathForItem(at: g.location(in: collectionView))
            else { return }
            KeyboardHaptics.lightImpact()
            collectionView.beginInteractiveMovementForItem(at: ip)
        case .changed:
            collectionView.updateInteractiveMovementTargetPosition(g.location(in: collectionView))
        case .ended:
            collectionView.endInteractiveMovement()
        default:
            collectionView.cancelInteractiveMovement()
        }
    }

    // MARK: - UICollectionViewDataSource

    func collectionView(_ collectionView: UICollectionView,
                        numberOfItemsInSection section: Int) -> Int {
        commands.count
    }

    func collectionView(_ collectionView: UICollectionView,
                        cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(
            withReuseIdentifier: QuickPanelCell.reuseID, for: indexPath) as! QuickPanelCell
        cell.configure(commands[indexPath.item])
        return cell
    }

    func collectionView(_ collectionView: UICollectionView,
                        moveItemAt sourceIndexPath: IndexPath,
                        to destinationIndexPath: IndexPath) {
        let moved = commands.remove(at: sourceIndexPath.item)
        commands.insert(moved, at: destinationIndexPath.item)
        KeyboardHaptics.selectionChanged()
        onReorder?(commands)
    }

    // MARK: - UICollectionViewDelegateFlowLayout

    func collectionView(_ collectionView: UICollectionView,
                        layout collectionViewLayout: UICollectionViewLayout,
                        sizeForItemAt indexPath: IndexPath) -> CGSize {
        let available = collectionView.bounds.width
        guard available > 0 else { return CGSize(width: 60, height: Self.tileHeight) }
        let totalGap = Self.interitem * CGFloat(columns - 1)
        let w = ((available - totalGap) / CGFloat(columns)).rounded(.down)
        return CGSize(width: max(w, 1), height: Self.tileHeight)
    }

    func collectionView(_ collectionView: UICollectionView,
                        didSelectItemAt indexPath: IndexPath) {
        guard indexPath.item < commands.count else { return }
        let cmd = commands[indexPath.item]
        // Cheap press feedback mirroring the old tile bounce.
        if let cell = collectionView.cellForItem(at: indexPath) {
            cell.alpha = 0.6
            UIView.animate(withDuration: 0.15) { cell.alpha = 1.0 }
        }
        onSelect?.quickPanelDidSelect(cmd)
    }

    @objc private func dismissTapped() {
        onSelect?.quickPanelDidDismiss()
    }
}

// MARK: - Cell

/// One Quick Panel tile: the same iOS 26 Liquid Glass tile shape as the
/// keyboard keys, with a centred emoji + `/command` label.
private final class QuickPanelCell: UICollectionViewCell {
    static let reuseID = "quickPanel.cell"

    private let emoji = UILabel()
    private let name = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)

        // The visual fill is a LiquidGlassBackingView pinned to the cell
        // bounds. `interactive: true` opts into UIGlassEffect's built-in
        // tap bounce on iOS 26.
        let backing = LiquidGlassBackingView(
            cornerRadius: 10,
            tintColor: KeyboardPalette.keyNormal,
            blurStyle: .systemThinMaterial,
            translucent: true,
            interactive: true
        )
        backing.translatesAutoresizingMaskIntoConstraints = false
        backing.isUserInteractionEnabled = false
        contentView.addSubview(backing)
        NSLayoutConstraint.activate([
            backing.topAnchor.constraint(equalTo: contentView.topAnchor),
            backing.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            backing.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            backing.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
        ])

        emoji.font = .systemFont(ofSize: 22)
        emoji.textAlignment = .center
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
        contentView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(_ cmd: SlashCommand) {
        emoji.text = cmd.emoji
        name.text = "/\(cmd.rawValue)"
    }
}

protocol QuickPanelDelegate: AnyObject {
    func quickPanelDidSelect(_ command: SlashCommand)
    func quickPanelDidDismiss()
}
#endif
