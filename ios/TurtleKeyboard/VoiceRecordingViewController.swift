import UIKit

/// Wispr-Flow-style "Swipe back to your app" coachmark presented when the
/// user opens the Turtle host app with a pending voice request from the
/// keyboard. Recording itself is handled by `VoiceSessionManager`; this
/// VC's only job is to:
///
///   1. Show the illustration + instructions matching the keyboard's
///      promise ("open Turtle once, swipe back, dictate").
///   2. Watch for the user actually swiping back (host enters background)
///      and kick `VoiceSessionManager` to start recording at that exact
///      moment — by then `applicationDidBecomeActive` has primed the
///      keep-alive engine, so the recorder attaches its input tap to an
///      already-running session and works even though the host is
///      backgrounded.
///   3. Let the user cancel via the X button — clears the rendezvous flag
///      so we don't accidentally record on the next activation.
///
/// AppDelegate presents this VC from `applicationDidBecomeActive` when
/// the App-Group rendezvous flag is set.
final class VoiceRecordingViewController: UIViewController {

    private static let appGroupID  = "group.com.samarth.turtlekeyboard.split"
    private static let kRequested  = "voice.requested"

    private var didTriggerRecording = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .white
        layoutCoachmark()

        // When the user swipes back to their original app, that's our
        // signal to flip the recorder on. We can't observe this from
        // VoiceSessionManager directly because it needs to fire only
        // while the coachmark is up — otherwise every background
        // transition would re-trigger recording.
        NotificationCenter.default.addObserver(
            self, selector: #selector(appDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification, object: nil)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - User swipes back

    @objc private func appDidEnterBackground() {
        guard !didTriggerRecording else { return }
        guard let d = UserDefaults(suiteName: Self.appGroupID) else { return }
        guard d.double(forKey: Self.kRequested) > 0 else { return }
        didTriggerRecording = true
        // VoiceSessionManager.startIfRequested reads the flag, validates
        // freshness, then attaches the input tap to the keep-alive engine.
        VoiceSessionManager.shared.startIfRequested()
    }

    // MARK: - Cancel (X button)

    @objc private func cancelTapped() {
        if let d = UserDefaults(suiteName: Self.appGroupID) {
            d.removeObject(forKey: Self.kRequested)
        }
        dismiss(animated: true)
    }

    // MARK: - Layout

    private func layoutCoachmark() {
        let card = UIView()
        card.backgroundColor = .white
        card.layer.cornerRadius = 28
        card.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(card)

        let title = UILabel()
        title.text = "Swipe back to your app"
        title.font = .systemFont(ofSize: 28, weight: .semibold)
        title.textColor = .black
        title.textAlignment = .center
        title.numberOfLines = 0

        let body = UILabel()
        body.text = "We wish you didn't have to open Turtle to use voice, but Apple now requires it to activate the microphone."
        body.font = .systemFont(ofSize: 15)
        body.textColor = UIColor.black.withAlphaComponent(0.65)
        body.textAlignment = .center
        body.numberOfLines = 0

        let illustration = CoachIllustrationView()
        illustration.translatesAutoresizingMaskIntoConstraints = false

        let hint = UILabel()
        hint.text = "Swipe right on the bar below"
        hint.font = .systemFont(ofSize: 14, weight: .medium)
        hint.textColor = .white
        hint.textAlignment = .center
        hint.backgroundColor = .black
        hint.layer.cornerRadius = 18
        hint.layer.masksToBounds = true
        hint.numberOfLines = 0

        let close = UIButton(type: .system)
        close.setImage(UIImage(systemName: "xmark"), for: .normal)
        close.tintColor = .black
        close.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)

        [title, body, illustration, hint, close].forEach {
            $0.translatesAutoresizingMaskIntoConstraints = false
            card.addSubview($0)
        }

        NSLayoutConstraint.activate([
            card.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            card.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            card.widthAnchor.constraint(lessThanOrEqualToConstant: 420),
            card.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            card.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24),

            close.topAnchor.constraint(equalTo: card.topAnchor, constant: 20),
            close.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -20),
            close.widthAnchor.constraint(equalToConstant: 22),
            close.heightAnchor.constraint(equalToConstant: 22),

            title.topAnchor.constraint(equalTo: card.topAnchor, constant: 60),
            title.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 28),
            title.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -28),

            illustration.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 28),
            illustration.centerXAnchor.constraint(equalTo: card.centerXAnchor),
            illustration.widthAnchor.constraint(equalToConstant: 200),
            illustration.heightAnchor.constraint(equalToConstant: 220),

            body.topAnchor.constraint(equalTo: illustration.bottomAnchor, constant: 24),
            body.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 28),
            body.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -28),

            hint.topAnchor.constraint(equalTo: body.bottomAnchor, constant: 24),
            hint.centerXAnchor.constraint(equalTo: card.centerXAnchor),
            hint.heightAnchor.constraint(equalToConstant: 56),
            hint.widthAnchor.constraint(greaterThanOrEqualToConstant: 220),
            hint.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -36),
        ])
    }
}

// MARK: - Coachmark illustration

/// Phone-with-listening-waveform + purple swipe dot, drawn with Core
/// Graphics to mirror Wispr's coachmark exactly (no asset dependency).
private final class CoachIllustrationView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(white: 0.94, alpha: 1.0)
        layer.cornerRadius = 24
    }
    required init?(coder: NSCoder) { fatalError() }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }

        let phoneRect = rect.insetBy(dx: 32, dy: 16)
        let phone = UIBezierPath(roundedRect: phoneRect, cornerRadius: 24)
        ctx.setStrokeColor(UIColor.black.cgColor)
        ctx.setLineWidth(3)
        ctx.addPath(phone.cgPath)
        ctx.strokePath()

        let pill = UIBezierPath(
            roundedRect: CGRect(x: phoneRect.minX + 12, y: phoneRect.minY + 14,
                                width: phoneRect.width - 36, height: 16),
            cornerRadius: 8)
        ctx.setFillColor(UIColor(white: 0.85, alpha: 1.0).cgColor)
        ctx.addPath(pill.cgPath); ctx.fillPath()
        let arrow = UIBezierPath(ovalIn:
            CGRect(x: phoneRect.maxX - 30, y: phoneRect.minY + 12, width: 20, height: 20))
        ctx.setFillColor(UIColor(red: 0.30, green: 0.55, blue: 1.0, alpha: 1.0).cgColor)
        ctx.addPath(arrow.cgPath); ctx.fillPath()

        let check = UIBezierPath(ovalIn:
            CGRect(x: phoneRect.maxX - 30, y: phoneRect.minY + 50, width: 22, height: 22))
        ctx.setFillColor(UIColor(white: 0.18, alpha: 1.0).cgColor)
        ctx.addPath(check.cgPath); ctx.fillPath()

        let waveY = phoneRect.midY + 4
        let bars: [CGFloat] = [10, 22, 14, 28, 16, 24, 12]
        let barW: CGFloat = 4
        let gap: CGFloat = 6
        let totalW = CGFloat(bars.count) * barW + CGFloat(bars.count - 1) * gap
        var x = phoneRect.midX - totalW / 2
        ctx.setFillColor(UIColor.black.cgColor)
        for h in bars {
            let p = UIBezierPath(roundedRect:
                CGRect(x: x, y: waveY - h/2, width: barW, height: h),
                cornerRadius: barW / 2)
            ctx.addPath(p.cgPath); ctx.fillPath()
            x += barW + gap
        }

        let listening = "Listening" as NSString
        let mic = "iPad Microphone" as NSString
        let pSmall: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 10, weight: .medium),
            .foregroundColor: UIColor.black,
        ]
        let pTiny: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 9),
            .foregroundColor: UIColor(white: 0.4, alpha: 1.0),
        ]
        let listSize = listening.size(withAttributes: pSmall)
        listening.draw(at: CGPoint(x: phoneRect.midX - listSize.width/2,
                                   y: waveY + 22),
                       withAttributes: pSmall)
        let micSize = mic.size(withAttributes: pTiny)
        mic.draw(at: CGPoint(x: phoneRect.midX - micSize.width/2,
                             y: waveY + 38),
                 withAttributes: pTiny)

        let bar = UIBezierPath(roundedRect:
            CGRect(x: phoneRect.midX - 40, y: phoneRect.maxY - 12,
                   width: 80, height: 4),
            cornerRadius: 2)
        ctx.setFillColor(UIColor.black.cgColor)
        ctx.addPath(bar.cgPath); ctx.fillPath()

        let dot = UIBezierPath(ovalIn:
            CGRect(x: phoneRect.maxX - 24, y: phoneRect.maxY - 24, width: 22, height: 22))
        ctx.setFillColor(UIColor(red: 0.69, green: 0.59, blue: 0.86, alpha: 1.0).cgColor)
        ctx.addPath(dot.cgPath); ctx.fillPath()
    }
}
