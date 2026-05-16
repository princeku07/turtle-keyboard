import Foundation
#if os(iOS)
import UIKit

/// In-keyboard image history grid. Triggered by tapping `/history` in the
/// Quick Panel — shows the last entries written by `/cap` and `/org`
/// (which live in the App Group container). Tapping a thumbnail copies
/// the full-resolution PNG to the system pasteboard so the user can
/// long-press the chat field and paste, same flow as `/cap`'s success
/// banner.
final class HistoryPanelView: UIView {

    /// Notified when the panel should close.
    var onDismiss: (() -> Void)?
    /// Notified when an image is copied to the pasteboard so the keyboard
    /// can surface the "📋 Image copied — long-press field to paste" banner.
    var onCopied: (() -> Void)?

    private let scroll = UIScrollView()
    private let grid = UIStackView()
    private let emptyLabel = UILabel()
    private var entries: [ImageHistory.Entry] = []

    private let columns: Int

    init(columns: Int) {
        self.columns = columns
        super.init(frame: .zero)
        configure()
        reload()
    }

    required init?(coder: NSCoder) { fatalError() }

    private func configure() {
        backgroundColor = KeyboardPalette.bg
        translatesAutoresizingMaskIntoConstraints = false

        let header = UILabel()
        header.text = "Image history"
        header.font = .systemFont(ofSize: 12, weight: .semibold)
        header.textColor = UIColor.white.withAlphaComponent(0.7)
        header.translatesAutoresizingMaskIntoConstraints = false

        let dismiss = UIButton(type: .system)
        dismiss.setImage(UIImage(systemName: "xmark"), for: .normal)
        dismiss.tintColor = UIColor.white.withAlphaComponent(0.7)
        dismiss.addTarget(self, action: #selector(dismissTapped), for: .touchUpInside)
        dismiss.translatesAutoresizingMaskIntoConstraints = false

        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.showsVerticalScrollIndicator = false
        grid.axis = .vertical
        grid.spacing = 8
        grid.alignment = .fill
        grid.translatesAutoresizingMaskIntoConstraints = false

        emptyLabel.text = "No images yet — run /cap or /org to fill this up."
        emptyLabel.font = .systemFont(ofSize: 13)
        emptyLabel.textColor = UIColor.white.withAlphaComponent(0.55)
        emptyLabel.textAlignment = .center
        emptyLabel.numberOfLines = 0
        emptyLabel.isHidden = true
        emptyLabel.translatesAutoresizingMaskIntoConstraints = false

        addSubview(header)
        addSubview(dismiss)
        addSubview(scroll)
        addSubview(emptyLabel)
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

            emptyLabel.centerXAnchor.constraint(equalTo: centerXAnchor),
            emptyLabel.centerYAnchor.constraint(equalTo: centerYAnchor),
            emptyLabel.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 24),
            emptyLabel.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -24),
        ])
    }

    private func reload() {
        entries = ImageHistory.list()
        grid.arrangedSubviews.forEach { $0.removeFromSuperview() }

        emptyLabel.isHidden = !entries.isEmpty
        scroll.isHidden = entries.isEmpty
        guard !entries.isEmpty else { return }

        var i = 0
        while i < entries.count {
            let row = UIStackView()
            row.axis = .horizontal
            row.spacing = 8
            row.distribution = .fillEqually
            row.alignment = .fill
            for col in 0..<columns {
                let idx = i + col
                if idx < entries.count {
                    row.addArrangedSubview(buildTile(for: entries[idx]))
                } else {
                    let spacer = UIView()
                    spacer.translatesAutoresizingMaskIntoConstraints = false
                    row.addArrangedSubview(spacer)
                }
            }
            NSLayoutConstraint.activate([row.heightAnchor.constraint(equalToConstant: 72)])
            grid.addArrangedSubview(row)
            i += columns
        }
    }

    private func buildTile(for entry: ImageHistory.Entry) -> UIView {
        let tile = UIControl()
        tile.backgroundColor = KeyboardPalette.keyNormal
        tile.layer.cornerRadius = 8
        tile.layer.masksToBounds = true
        tile.translatesAutoresizingMaskIntoConstraints = false

        // Thumbnail. Decode synchronously — these are small PNGs from
        // /cap or /org and we want the grid to be snappy when /history
        // opens. If decoding fails we just show the command emoji.
        let imageView = UIImageView()
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        if let data = try? Data(contentsOf: entry.pngURL),
           let img = UIImage(data: data) {
            imageView.image = img
        } else {
            let fallback = UILabel()
            fallback.text = SlashCommand(rawValue: entry.command)?.emoji ?? "🖼️"
            fallback.font = .systemFont(ofSize: 26)
            fallback.textAlignment = .center
            fallback.translatesAutoresizingMaskIntoConstraints = false
            tile.addSubview(fallback)
            NSLayoutConstraint.activate([
                fallback.centerXAnchor.constraint(equalTo: tile.centerXAnchor),
                fallback.centerYAnchor.constraint(equalTo: tile.centerYAnchor),
            ])
        }
        tile.addSubview(imageView)
        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(equalTo: tile.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: tile.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: tile.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: tile.bottomAnchor),
        ])

        tile.addAction(UIAction { [weak self, weak tile] _ in
            tile?.alpha = 0.6
            UIView.animate(withDuration: 0.15) { tile?.alpha = 1.0 }
            self?.copyToPasteboard(entry: entry)
        }, for: .touchUpInside)
        return tile
    }

    private func copyToPasteboard(entry: ImageHistory.Entry) {
        guard let data = try? Data(contentsOf: entry.pngURL),
              let img = UIImage(data: data)
        else { return }
        // Match the existing /cap success behaviour: put the PNG on the
        // pasteboard so the user can long-press the chat field and paste.
        UIPasteboard.general.image = img
        onCopied?()
        onDismiss?()
    }

    @objc private func dismissTapped() { onDismiss?() }
}
#endif
