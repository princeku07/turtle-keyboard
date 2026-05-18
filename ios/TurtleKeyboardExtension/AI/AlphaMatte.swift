import Foundation
import CoreGraphics
import ImageIO

/// Difference-matting math shared between `/sticker` and `/gif`. Given two
/// renders of the same scene — one on pure white, one on pure black —
/// recovers per-pixel alpha and the subject's true RGB.
///
/// Direct port of `android/.../ai/AlphaMatte.java`. Same constants, same
/// algorithm, same snap thresholds.
///
/// ```
///   pixelDist = euclidean(pixOnWhite, pixOnBlack)
///   bgDist    = euclidean(WHITE, BLACK) = sqrt(3 · 255²) ≈ 441.67
///   alpha     = 1 - (pixelDist / bgDist)
/// ```
/// Subject pixels match across renders ⇒ `alpha = 1`. Background pixels
/// differ by the full distance ⇒ `alpha = 0`. Anti-aliased edges land at
/// intermediate values. True RGB is back-solved from the black render:
/// `observed = α · true` ⇒ `true = observed / α`. Snap thresholds clean
/// up small model-side RGB drift at the two ends.
///
/// Article reference:
/// `jidefr.medium.com/generating-transparent-background-images-with-nano-banana-pro`
enum AlphaMatte {

    /// Snap-to-opaque / snap-to-transparent thresholds on recovered alpha.
    /// Tiny model-side RGB drift on subject pixels otherwise leaves them
    /// at ~0.97 alpha (faint translucency); pure-background pixels
    /// otherwise sit at ~0.02 (faint ghost). Snapping cleans both ends.
    private static let snapOpaque: Double = 0.95
    private static let snapTrans:  Double = 0.05

    /// Background euclidean distance — `sqrt(3·255²)`.
    private static let bgDist: Double = (3.0 * 255.0 * 255.0).squareRoot()

    /// Compute the matte. Returns nil when dimensions differ, the buffers
    /// can't be drawn, or the output `CGImage` can't be constructed.
    /// Caller owns no input/output lifetime beyond the returned `CGImage`.
    static func differenceMatte(onWhite: CGImage, onBlack: CGImage) -> CGImage? {
        let w = onWhite.width
        let h = onBlack.height
        guard w > 0, h > 0,
              w == onBlack.width, h == onWhite.height else {
            return nil
        }

        // Decode both inputs into known-layout RGBA8 buffers. We use
        // `premultipliedLast` for the *input* contexts because CGContext
        // requires a premultiplied alpha format — but since the inputs are
        // opaque PNGs (alpha == 255 everywhere), premultiplied == raw RGB
        // so the channel values are correct.
        guard let whiteBuf = readRGBA8(from: onWhite, width: w, height: h),
              let blackBuf = readRGBA8(from: onBlack, width: w, height: h) else {
            return nil
        }

        let count = w * h * 4
        var out = [UInt8](repeating: 0, count: count)

        // Walk pixels. Hot loop — keep math in Double; the inner branches
        // are predictable and the compiler vectorises the channel reads.
        whiteBuf.withUnsafeBufferPointer { wp in
            blackBuf.withUnsafeBufferPointer { bp in
                out.withUnsafeMutableBufferPointer { op in
                    var i = 0
                    while i < count {
                        let rw = Double(wp[i]),     gw = Double(wp[i + 1]), bw = Double(wp[i + 2])
                        let rb = Double(bp[i]),     gb = Double(bp[i + 1]), bb = Double(bp[i + 2])
                        let dr = rw - rb, dg = gw - gb, db = bw - bb
                        let pixDist = (dr * dr + dg * dg + db * db).squareRoot()
                        var alpha = 1.0 - (pixDist / bgDist)
                        if      alpha > snapOpaque { alpha = 1.0 }
                        else if alpha < snapTrans  { alpha = 0.0 }
                        else if alpha < 0.0        { alpha = 0.0 }
                        else if alpha > 1.0        { alpha = 1.0 }

                        if alpha <= 0.0 {
                            // Fully transparent — zero RGB so downstream
                            // PNG / GIF encoders don't waste palette slots
                            // on never-visible colors.
                            op[i] = 0; op[i + 1] = 0; op[i + 2] = 0; op[i + 3] = 0
                        } else {
                            // From the black render: observed = α · subject
                            //   ⇒ subject = observed / α
                            // Stored UN-premultiplied (matches Android's
                            // ARGB_8888 contract and the output CGImage's
                            // `.last` alpha layout below).
                            let r = min(255.0, (rb / alpha).rounded())
                            let g = min(255.0, (gb / alpha).rounded())
                            let b = min(255.0, (bb / alpha).rounded())
                            op[i]     = UInt8(r)
                            op[i + 1] = UInt8(g)
                            op[i + 2] = UInt8(b)
                            op[i + 3] = UInt8((alpha * 255.0).rounded())
                        }
                        i += 4
                    }
                }
            }
        }

        // Wrap the output bytes in an un-premultiplied RGBA8 CGImage. We
        // use `.last` (no premultiplication) here — CGImage initialisers
        // accept it, even though CGContext drawing wouldn't. Downstream
        // PNG / GIF encoders read the buffer as-is.
        let bytesPerRow = w * 4
        guard let provider = CGDataProvider(data: Data(out) as CFData) else {
            return nil
        }
        let bitmapInfo = CGBitmapInfo(rawValue:
            CGImageAlphaInfo.last.rawValue | CGBitmapInfo.byteOrder32Big.rawValue)
        return CGImage(
            width: w,
            height: h,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: bytesPerRow,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: bitmapInfo,
            provider: provider,
            decode: nil,
            shouldInterpolate: false,
            intent: .defaultIntent
        )
    }

    /// Decode a `CGImage` into a fresh RGBA8 buffer of size `width*height*4`.
    /// Uses a `premultipliedLast` CGContext because that's what CGContext
    /// requires; for opaque inputs the result is the same as un-premul.
    private static func readRGBA8(from image: CGImage, width w: Int, height h: Int) -> [UInt8]? {
        var buf = [UInt8](repeating: 0, count: w * h * 4)
        let bytesPerRow = w * 4
        let bitmapInfo: UInt32 = CGImageAlphaInfo.premultipliedLast.rawValue
            | CGBitmapInfo.byteOrder32Big.rawValue
        let cs = CGColorSpaceCreateDeviceRGB()
        let result: CGContext? = buf.withUnsafeMutableBytes { raw -> CGContext? in
            guard let base = raw.baseAddress else { return nil }
            return CGContext(
                data: base,
                width: w,
                height: h,
                bitsPerComponent: 8,
                bytesPerRow: bytesPerRow,
                space: cs,
                bitmapInfo: bitmapInfo
            )
        }
        guard let ctx = result else { return nil }
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        return buf
    }
}
