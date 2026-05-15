import UIKit

/// Host-app grid of past `/cap` + `/org` images. iOS counterpart to
/// Android's `HistoryActivity`. Reads `ImageHistory.list()` from the
/// shared App Group container, renders a 3-column thumbnail grid, and
/// hands the tapped image to `UIActivityViewController` so the user
/// can re-share something they generated days ago.
///
/// Long-press a thumbnail for a per-entry Delete action; a top-right
/// "Clear all" wipes the directory.
final class HistoryViewController: UIViewController,
                                    UICollectionViewDataSource,
                                    UICollectionViewDelegate {

    private let brandGreen = UIColor(red: 0.106, green: 0.369, blue: 0.125, alpha: 1.0)

    private var entries: [ImageHistory.Entry] = []
    private let emptyLabel = UILabel()
    private var collectionView: UICollectionView!

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = brandGreen
        title = "History"
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Done", style: .done, target: self, action: #selector(dismissTapped))
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: "Clear all", style: .plain, target: self, action: #selector(clearAllTapped))

        emptyLabel.text = "No images yet.\nUse /cap or /org in any chat to generate one."
        emptyLabel.numberOfLines = 0
        emptyLabel.textAlignment = .center
        emptyLabel.font = .systemFont(ofSize: 15)
        emptyLabel.textColor = UIColor.white.withAlphaComponent(0.75)
        emptyLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(emptyLabel)

        let layout = UICollectionViewFlowLayout()
        layout.minimumLineSpacing = 8
        layout.minimumInteritemSpacing = 8
        layout.sectionInset = UIEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
        collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)
        collectionView.translatesAutoresizingMaskIntoConstraints = false
        collectionView.backgroundColor = .clear
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.register(ThumbCell.self, forCellWithReuseIdentifier: ThumbCell.reuseID)
        view.addSubview(collectionView)

        NSLayoutConstraint.activate([
            collectionView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            emptyLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            emptyLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            emptyLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            emptyLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32),
        ])
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reload()
    }

    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        guard let layout = collectionView.collectionViewLayout as? UICollectionViewFlowLayout else { return }
        let columns: CGFloat = 3
        let spacing = layout.minimumInteritemSpacing
        let inset = layout.sectionInset.left + layout.sectionInset.right
        let available = view.bounds.width - inset - spacing * (columns - 1)
        let side = max(60, floor(available / columns))
        layout.itemSize = CGSize(width: side, height: side)
    }

    private func reload() {
        entries = ImageHistory.list()
        emptyLabel.isHidden = !entries.isEmpty
        collectionView.isHidden = entries.isEmpty
        collectionView.reloadData()
    }

    // MARK: - DataSource

    func collectionView(_ collectionView: UICollectionView,
                        numberOfItemsInSection section: Int) -> Int { entries.count }

    func collectionView(_ collectionView: UICollectionView,
                        cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: ThumbCell.reuseID,
                                                     for: indexPath) as! ThumbCell
        cell.configure(with: entries[indexPath.item])
        return cell
    }

    // MARK: - Delegate

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        let entry = entries[indexPath.item]
        let activity = UIActivityViewController(activityItems: [entry.pngURL],
                                                applicationActivities: nil)
        activity.popoverPresentationController?.sourceView = collectionView
        activity.popoverPresentationController?.sourceRect =
            collectionView.cellForItem(at: indexPath)?.frame ?? .zero
        present(activity, animated: true)
    }

    func collectionView(_ collectionView: UICollectionView,
                        contextMenuConfigurationForItemAt indexPath: IndexPath,
                        point: CGPoint) -> UIContextMenuConfiguration? {
        let entry = entries[indexPath.item]
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { _ in
            let share = UIAction(title: "Share", image: UIImage(systemName: "square.and.arrow.up")) { [weak self] _ in
                guard let self = self else { return }
                self.collectionView(self.collectionView, didSelectItemAt: indexPath)
            }
            let delete = UIAction(title: "Delete", image: UIImage(systemName: "trash"),
                                  attributes: .destructive) { [weak self] _ in
                try? FileManager.default.removeItem(at: entry.pngURL)
                let sidecar = entry.pngURL.deletingPathExtension().appendingPathExtension("txt")
                try? FileManager.default.removeItem(at: sidecar)
                self?.reload()
            }
            return UIMenu(title: entry.command.isEmpty ? "" : "/\(entry.command)",
                          children: [share, delete])
        }
    }

    // MARK: - Actions

    @objc private func dismissTapped() { dismiss(animated: true) }

    @objc private func clearAllTapped() {
        guard !entries.isEmpty else { return }
        let alert = UIAlertController(title: "Clear all history?",
                                       message: "This removes every saved image.",
                                       preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Clear", style: .destructive) { [weak self] _ in
            ImageHistory.clear()
            self?.reload()
        })
        present(alert, animated: true)
    }
}

// MARK: - Thumbnail cell

private final class ThumbCell: UICollectionViewCell {
    static let reuseID = "ThumbCell"

    private let imageView = UIImageView()
    private let badge = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.layer.cornerRadius = 8
        contentView.clipsToBounds = true
        contentView.backgroundColor = UIColor.white.withAlphaComponent(0.08)

        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.translatesAutoresizingMaskIntoConstraints = false

        badge.font = .monospacedSystemFont(ofSize: 10, weight: .semibold)
        badge.textColor = .white
        badge.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        badge.textAlignment = .center
        badge.layer.cornerRadius = 4
        badge.layer.masksToBounds = true
        badge.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(imageView)
        contentView.addSubview(badge)
        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(equalTo: contentView.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),

            badge.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 4),
            badge.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -4),
            badge.heightAnchor.constraint(equalToConstant: 16),
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) not used") }

    func configure(with entry: ImageHistory.Entry) {
        // Decode lazily off-main since UICollectionView calls this on
        // scroll. PNGs in the history are small (~kb), so a sync decode
        // would also be fine but this future-proofs against larger ones.
        let url = entry.pngURL
        imageView.image = nil
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let data = try? Data(contentsOf: url),
                  let image = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                guard self?.imageView.bounds != .zero else { return }
                self?.imageView.image = image
            }
        }
        badge.text = "  /\(entry.command)  "
    }
}
