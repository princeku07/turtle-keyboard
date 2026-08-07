import UIKit

/// Handles `turtlekeyboard://join?sheetId=...&owner=...` deep links — the
/// joiner side of the QR share flow. Mirrors Android's `JoinSplitActivity`.
///
/// The owner has already opened membership and granted anyone-with-link
/// writer access on their sheet; this screen points the local Split store
/// at that sheet and pulls its rows.
final class JoinSplitViewController: UIViewController {

    private let brandGreen = UIColor.systemGreen
    private let cardGreen  = UIColor.secondarySystemGroupedBackground
    private let muted      = UIColor(red: 0.80, green: 0.91, blue: 0.78, alpha: 1.0)

    private let sheetId: String
    private let ownerEmail: String

    private let store: SplitStore = UserDefaultsSplitStore(suiteName: SplitContract.storageSuiteName)
    private lazy var oauth = SplitOAuth(store: store, presentationAnchor: view.window)
    private lazy var sync = SplitCloudSync(store: store, oauth: oauth)

    private let statusLabel = UILabel()
    private let connectButton = UIButton(type: .system)
    private let cancelButton = UIButton(type: .system)

    init(sheetId: String, ownerEmail: String) {
        self.sheetId = sheetId
        self.ownerEmail = ownerEmail
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemGroupedBackground

        let head = UILabel()
        head.text = "Connect to split book"
        head.font = .preferredFont(forTextStyle: .title1)
        head.textColor = .label
        head.textAlignment = .center
        head.numberOfLines = 0

        let who = ownerEmail.isEmpty ? "The owner" : ownerEmail
        let body = UILabel()
        body.text = "\(who) just added you as a writer.\n\nTap Connect to start syncing your splits with theirs."
        body.font = .systemFont(ofSize: 14)
        body.textColor = muted
        body.textAlignment = .center
        body.numberOfLines = 0

        statusLabel.font = .systemFont(ofSize: 13)
        statusLabel.textColor = .secondaryLabel
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0

        connectButton.setTitle("Connect", for: .normal)
        connectButton.setTitleColor(.white, for: .normal)
        connectButton.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        connectButton.backgroundColor = .systemGreen
        connectButton.layer.cornerRadius = 10
        connectButton.contentEdgeInsets = UIEdgeInsets(top: 12, left: 18, bottom: 12, right: 18)
        connectButton.addTarget(self, action: #selector(connectTapped), for: .touchUpInside)

        cancelButton.setTitle("Cancel", for: .normal)
        cancelButton.setTitleColor(.systemGreen, for: .normal)
        cancelButton.titleLabel?.font = .systemFont(ofSize: 14)
        cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [head, body, statusLabel, connectButton, cancelButton])
        stack.axis = .vertical
        stack.spacing = 16
        stack.alignment = .fill
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
        ])
    }

    @objc private func connectTapped() {
        connectButton.isEnabled = false
        connectButton.setTitle("Connecting…", for: .normal)
        statusLabel.text = ""

        // If already signed in, jump straight to the join.
        if oauth.isSignedIn, oauth.cachedAccessToken() != nil {
            performJoin()
            return
        }
        // Otherwise run sign-in first — joiner needs Sheets/Drive scope to
        // read the shared sheet.
        oauth.signIn { [weak self] result in
            DispatchQueue.main.async {
                guard let self = self else { return }
                switch result {
                case .success:
                    self.performJoin()
                case .failure(let err):
                    self.abort("Sign-in failed: \(err.localizedDescription)")
                }
            }
        }
    }

    private func performJoin() {
        statusLabel.text = "Loading shared splits…"
        Task { @MainActor in
            let ok = await sync.joinSharedSheet(sheetId: sheetId, ownerEmail: ownerEmail)
            if ok {
                let alert = UIAlertController(
                    title: "Connected",
                    message: "You're now a writer on the shared split book. Open \"View saved splits\" to see and add splits.",
                    preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
                    self?.dismiss(animated: true)
                })
                present(alert, animated: true)
            } else {
                abort("Could not load shared sheet — make sure the owner added you, then try again.")
            }
        }
    }

    @objc private func cancelTapped() {
        dismiss(animated: true)
    }

    private func abort(_ msg: String) {
        let alert = UIAlertController(title: nil, message: msg, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.dismiss(animated: true)
        })
        present(alert, animated: true)
    }
}
