import Foundation
#if os(iOS)
import UIKit

// MARK: - LiquidGlass
//
// Apple's iOS 26 keyboard renders every key as Liquid Glass — a real
// translucent material that refracts the content underneath, picks up
// an interactive specular highlight, and adapts its tint to the
// background. This file is our single source of truth for that look.
//
// Two render paths:
//
//   1. iOS 26+ — `UIGlassEffect` (Apple's first-party Liquid Glass).
//      The effect renders the refraction, the specular highlight, and
//      the edge rim itself; our only job is to clip it to a fixed
//      corner radius via `UICornerConfiguration` so it doesn't render
//      as its default capsule shape. No manual specular / rim / shadow
//      overlays on this path — they'd double up what the glass is
//      already drawing.
//
//   2. iOS 15–25 fallback — `UIBlurEffect` + tint overlay +
//      `CAGradientLayer` specular + `CALayer` rim + manual drop
//      shadow. Approximates the glass look using primitives that
//      have shipped since iOS 8. This path is what you saw before
//      iOS 26 lit up the real material.
//
// The same primitive is reused for keys, Quick Panel tiles, and any
// future glass surface — the call site picks translucent vs solid.

enum LiquidGlass {

    /// True when running on iOS 26+ where the real `UIGlassEffect` is
    /// available. Anything older falls through to the manual
    /// approximation below.
    static var supportsNativeGlass: Bool {
        if #available(iOS 26.0, *) { return true }
        return false
    }

    // MARK: Fallback-path layer factories
    //
    // These are only used on the iOS 15–25 render path. The iOS 26
    // path lets `UIGlassEffect` paint its own specular + rim.

    static let specularLayerName = "LiquidGlass.specular"
    static let rimLayerName      = "LiquidGlass.rim"

    static func makeSpecularLayer(cornerRadius: CGFloat) -> CAGradientLayer {
        let l = CAGradientLayer()
        l.name = specularLayerName
        l.colors = [
            UIColor.white.withAlphaComponent(0.10).cgColor,
            UIColor.white.withAlphaComponent(0.02).cgColor,
            UIColor.clear.cgColor,
        ]
        l.locations = [0.0, 0.25, 0.55]
        l.startPoint = CGPoint(x: 0.5, y: 0)
        l.endPoint   = CGPoint(x: 0.5, y: 1)
        l.cornerRadius  = cornerRadius
        l.masksToBounds = true
        return l
    }

    static func makeRimLayer(cornerRadius: CGFloat) -> CALayer {
        let l = CALayer()
        l.name = rimLayerName
        l.borderColor = UIColor.white.withAlphaComponent(0.06).cgColor
        l.borderWidth = 0.5
        l.cornerRadius = cornerRadius
        return l
    }

    // MARK: Public API

    /// Build a frame-positioned glass backing. Designed to be inserted
    /// as a sibling BEHIND a UIButton (the button's title/icon then
    /// renders on top of the glass).
    static func makeBacking(frame: CGRect,
                            cornerRadius: CGFloat,
                            tintColor: UIColor,
                            blurStyle: UIBlurEffect.Style,
                            translucent: Bool,
                            interactive: Bool = false) -> UIView {
        let v = LiquidGlassBackingView(
            cornerRadius: cornerRadius,
            tintColor: tintColor,
            blurStyle: blurStyle,
            translucent: translucent,
            interactive: interactive
        )
        v.frame = frame
        return v
    }
}

// MARK: - LiquidGlassBackingView

/// Renders one glass tile. Sized via `frame` by the caller; layout is
/// frame-driven so it slots straight into KeyboardViewController's
/// frame-based key rows without forcing Auto Layout on the parent.
final class LiquidGlassBackingView: UIView {

    private let blurView: UIVisualEffectView?
    private let solidFill: UIView?
    private let specular: CAGradientLayer?
    private let rim: CALayer?
    /// True when the iOS 26 `UIGlassEffect` is the render. Determines
    /// whether we paint our manual specular/rim/shadow on top
    /// (false → fallback path needs the overlays; true → the glass
    /// effect already draws them).
    private let usesNativeGlass: Bool
    /// Cached so `layoutSubviews` can size the shadow path without
    /// re-deriving from the (possibly absent) sub-host layers.
    private let cornerRadius: CGFloat

    init(cornerRadius: CGFloat,
         tintColor: UIColor,
         blurStyle: UIBlurEffect.Style,
         translucent: Bool,
         interactive: Bool = false) {

        self.cornerRadius = cornerRadius

        var native = false
        var blur: UIVisualEffectView? = nil
        var fill: UIView? = nil

        if translucent {
            if #available(iOS 26.0, *) {
                // Real Liquid Glass — Apple paints refraction, lensing,
                // specular, and rim inside the effect itself.
                let glass = UIGlassEffect(style: .regular)
                glass.tintColor = tintColor
                glass.isInteractive = interactive
                blur = UIVisualEffectView(effect: glass)
                native = true
            } else {
                // iOS 15–25 fallback — flat blur + manual overlays.
                blur = UIVisualEffectView(effect: UIBlurEffect(style: blurStyle))
            }
        } else {
            // Solid (brand) path — opaque tinted surface with glass
            // FINISH (specular + rim + shadow) on top so it still
            // reads as a raised tile.
            let f = UIView()
            f.backgroundColor = tintColor
            fill = f
        }

        self.blurView = blur
        self.solidFill = fill
        self.usesNativeGlass = native

        // Manual specular + rim only live on the fallback / solid
        // paths. UIGlassEffect on iOS 26 paints its own.
        if native {
            self.specular = nil
            self.rim = nil
        } else {
            self.specular = LiquidGlass.makeSpecularLayer(cornerRadius: cornerRadius)
            self.rim      = LiquidGlass.makeRimLayer(cornerRadius: cornerRadius)
        }

        super.init(frame: .zero)

        isUserInteractionEnabled = false
        layer.masksToBounds = false

        // Manual drop shadow only on the non-native paths. UIGlassEffect
        // renders its own subtle depth — adding ours on top reads as
        // Material Design elevation rather than glass.
        if !native {
            layer.shadowColor   = UIColor.black.cgColor
            layer.shadowOpacity = 0.12
            layer.shadowOffset  = CGSize(width: 0, height: 1)
            layer.shadowRadius  = 0.5
        }

        if let blur {
            blur.frame = bounds
            blur.autoresizingMask = [.flexibleWidth, .flexibleHeight]

            if #available(iOS 26.0, *), native {
                // UIGlassEffect's default shape is a capsule — it
                // ignores `layer.cornerRadius` + `masksToBounds`
                // because the Metal layer underneath renders outside
                // the standard clipping pipeline. `cornerConfiguration`
                // is the official API for forcing a fixed shape on
                // the glass effect.
                blur.cornerConfiguration = .uniformCorners(
                    radius: .fixed(cornerRadius)
                )
            } else {
                blur.layer.cornerRadius  = cornerRadius
                blur.layer.cornerCurve   = .continuous
                blur.layer.masksToBounds = true
            }

            addSubview(blur)

            if !native {
                // Fallback path: paint the tint, specular, and rim
                // inside the blur's contentView so they inherit the
                // rounded clip.
                let tint = UIView(frame: blur.bounds)
                tint.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                tint.backgroundColor = tintColor
                tint.isUserInteractionEnabled = false
                blur.contentView.addSubview(tint)

                if let specular {
                    blur.contentView.layer.addSublayer(specular)
                }
                if let rim {
                    blur.contentView.layer.addSublayer(rim)
                }
            }
        } else if let solidFill {
            solidFill.frame = bounds
            solidFill.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            solidFill.layer.cornerRadius  = cornerRadius
            solidFill.layer.cornerCurve   = .continuous
            solidFill.layer.masksToBounds = true
            addSubview(solidFill)

            if let specular { solidFill.layer.addSublayer(specular) }
            if let rim      { solidFill.layer.addSublayer(rim) }
        }
    }

    required init?(coder: NSCoder) {
        fatalError("LiquidGlassBackingView does not support init(coder:)")
    }

    override func layoutSubviews() {
        super.layoutSubviews()

        // Re-sync the manual specular + rim layers (they don't honour
        // autoresizingMask) and the shadow path. Both are no-ops on
        // the iOS 26 native path.
        if !usesNativeGlass {
            let host = blurView?.contentView.layer ?? solidFill?.layer ?? layer
            specular?.frame = host.bounds
            rim?.frame      = host.bounds
            layer.shadowPath = UIBezierPath(
                roundedRect: bounds, cornerRadius: cornerRadius
            ).cgPath
        }
    }
}

#endif
