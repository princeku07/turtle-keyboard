import Foundation
#if os(iOS)
import UIKit

// MARK: - PresetChipStripView
//
// Port of android/.../ime/view/PresetChipStripView. Horizontal scroll of
// short string chips. Tapping a chip fires `onTap` with the chip's value
// — the host wires this so the command bar can offer per-command
// shortcuts (e.g. `/tone` → "professional / casual / formal / friendly"
// without forcing the user to type the preset).
//
// Used by `KeyboardViewController` from within the command bar while a
// command with `needsPrompt = true` is awaiting its prompt — the chip
// strip slides in above the typed pill, the user taps a preset and the
// command fires immediately with that value.

final class PresetChipStripView: UIView {

    typealias OnTap = (String) -> Void

    private let scroll = UIScrollView()
    private let stack = UIStackView()
    private var onTap: OnTap?

    override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    private func configure() {
        backgroundColor = .clear

        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.showsHorizontalScrollIndicator = false
        scroll.showsVerticalScrollIndicator = false
        scroll.alwaysBounceHorizontal = true
        addSubview(scroll)

        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .horizontal
        stack.spacing = 6
        stack.alignment = .center
        scroll.addSubview(stack)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: topAnchor),
            scroll.leadingAnchor.constraint(equalTo: leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: bottomAnchor),

            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 4),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -4),
            stack.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor),
        ])
    }

    /// Replace every chip. Empty `presets` collapses the strip (caller
    /// should hide the parent slot in that case).
    func setPresets(_ presets: [String], onTap: @escaping OnTap) {
        setChips(presets.map { Chip(label: $0, value: $0) }, onTap: onTap)
    }

    /// Richer chip representation — display label can differ from the
    /// tap payload. Used by slash-command autocomplete to show
    /// `🎨 /cap` while returning just `"cap"` on tap.
    struct Chip {
        let label: String
        let value: String
    }

    func setChips(_ chips: [Chip], onTap: @escaping OnTap) {
        self.onTap = onTap
        stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for chip in chips {
            stack.addArrangedSubview(buildChip(chip: chip))
        }
        scroll.setContentOffset(.zero, animated: false)
    }

    private func buildChip(chip: Chip) -> UIButton {
        let btn = ChipButton(value: chip.value)
        btn.setTitle(chip.label, for: .normal)
        btn.titleLabel?.font = .systemFont(ofSize: 12, weight: .medium)
        btn.setTitleColor(.white, for: .normal)
        btn.backgroundColor = UIColor.white.withAlphaComponent(0.16)
        btn.layer.cornerRadius = 13
        btn.contentEdgeInsets = UIEdgeInsets(top: 0, left: 10, bottom: 0, right: 10)
        btn.heightAnchor.constraint(equalToConstant: 26).isActive = true
        btn.addAction(UIAction { [weak self, weak btn] _ in
            guard let cb = btn else { return }
            cb.alpha = 0.6
            UIView.animate(withDuration: 0.15) { cb.alpha = 1.0 }
            self?.onTap?(cb.payloadValue)
        }, for: .touchUpInside)
        return btn
    }

    /// Subclass that carries the tap payload separately from the display
    /// title so chips like `🎨 /cap` can return the bare `"cap"`.
    private final class ChipButton: UIButton {
        let payloadValue: String
        init(value: String) {
            self.payloadValue = value
            super.init(frame: .zero)
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) not used") }
    }
}

// MARK: - PresetCatalog
//
// Per-command preset lists. Mirrors the Android "style preset" map but
// generalized across the iOS slash commands that benefit from a quick
// tap-to-prefill — `/tone`, `/tl`, and `/cap`. Empty result means the
// strip stays hidden for that command.

enum PresetCatalog {
    static func presets(for command: String) -> [String] {
        switch command {
        case "tone":
            return ["professional", "casual", "formal", "friendly", "concise", "playful"]
        case "tl":
            return ["English", "Hindi", "Spanish", "French", "German", "Japanese"]
        case "cap":
            return ["photorealistic", "anime", "watercolor", "3D render", "sketch", "pixel art"]
        case "style":
            // Surface the curated style keys defined in `AI/StylePresets.swift`.
            // Tapping a chip fires `/style <preset>` and CommandRouter's
            // system-prompt expands the key to the full description.
            return StylePresets.orderedKeys
        default:
            return []
        }
    }
}
#endif
