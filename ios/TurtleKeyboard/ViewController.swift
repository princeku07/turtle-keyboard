import UIKit

class ViewController: UIViewController {

    // Dark-green brand colour matching the keyboard extension
    private let brandGreen = UIColor(red: 0.106, green: 0.369, blue: 0.125, alpha: 1.0)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = brandGreen
        setupUI()
        primeLocalNetworkPermission()
    }

    /// Trigger iOS's Local Network privacy prompt. Required before the
    /// keyboard extension can reach the LAN AI server — extensions can't
    /// show system permission prompts during text input, so the host app
    /// has to do it. iOS shows this prompt the first time *any* code in
    /// the app's bundle attempts to reach a LAN IP.
    ///
    /// Failure mode without this: extension requests fail with
    /// URLError -1009 and `_NSURLErrorNWPathKey=unsatisfied (Local network
    /// prohibited)` even though ATS / NSAllowsLocalNetworking are set —
    /// because Local Network privacy is a separate permission layer.
    ///
    /// After the user taps "Allow" once, the grant persists across app
    /// launches and the keyboard extension can also reach LAN devices.
    private func primeLocalNetworkPermission() {
        guard let url = URL(string: "http://192.168.0.106:1234/api/v1/chat") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "HEAD"
        req.timeoutInterval = 2
        // Fire-and-forget — we don't care about the response, only about
        // the side effect of iOS evaluating local-network access and
        // surfacing the permission prompt.
        URLSession.shared.dataTask(with: req) { _, _, _ in }.resume()
    }

    private func setupUI() {
        let titleLabel = UILabel()
        titleLabel.text = "🐢 Turtle Keyboard"
        titleLabel.font = .boldSystemFont(ofSize: 22)
        titleLabel.textColor = .white
        titleLabel.textAlignment = .center

        let subtitleLabel = UILabel()
        subtitleLabel.text = "Enable in two steps below."
        subtitleLabel.font = .systemFont(ofSize: 16)
        subtitleLabel.textColor = UIColor.white.withAlphaComponent(0.8)
        subtitleLabel.textAlignment = .center

        let enableBtn  = makeButton(title: "1. Enable Turtle Keyboard",  action: #selector(openKeyboardSettings))
        let chooseBtn  = makeButton(title: "2. Switch to Turtle Keyboard", action: #selector(openKeyboardSettings))
        let personalizeBtn = makeButton(title: "Personalize",        action: #selector(openPersonalize))
        let splitsBtn      = makeButton(title: "Saved splits",       action: #selector(openSplits))
        let historyBtn     = makeButton(title: "Image history",      action: #selector(openHistory))

        let stack = UIStackView(arrangedSubviews: [titleLabel, subtitleLabel, enableBtn, chooseBtn, personalizeBtn, splitsBtn, historyBtn])
        stack.axis = .vertical
        stack.spacing = 16
        stack.alignment = .fill
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            enableBtn.heightAnchor.constraint(equalToConstant: 50),
            chooseBtn.heightAnchor.constraint(equalToConstant: 50),
            personalizeBtn.heightAnchor.constraint(equalToConstant: 50),
            splitsBtn.heightAnchor.constraint(equalToConstant: 50),
            historyBtn.heightAnchor.constraint(equalToConstant: 50),
        ])
    }

    @objc private func openPersonalize() {
        let nav = UINavigationController(rootViewController: PersonalizationViewController())
        present(nav, animated: true)
    }

    @objc private func openSplits() {
        let nav = UINavigationController(rootViewController: SplitDetailViewController())
        present(nav, animated: true)
    }

    @objc private func openHistory() {
        let nav = UINavigationController(rootViewController: HistoryViewController())
        present(nav, animated: true)
    }

    private func makeButton(title: String, action: Selector) -> UIButton {
        let btn = UIButton(type: .system)
        btn.setTitle(title, for: .normal)
        btn.setTitleColor(brandGreen, for: .normal)
        btn.backgroundColor = .white
        btn.layer.cornerRadius = 8
        btn.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        btn.addTarget(self, action: action, for: .touchUpInside)
        btn.translatesAutoresizingMaskIntoConstraints = false
        return btn
    }

    // Both buttons open Settings — iOS has no programmatic keyboard-picker API.
    // The user navigates Settings > General > Keyboard > Keyboards > Add New Keyboard.
    @objc private func openKeyboardSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}
