import Foundation
#if os(iOS)
import UIKit
import ImageIO
import UniformTypeIdentifiers
import MobileCoreServices

// MARK: - ImageVariants
//
// Port of android/.../ai/ImageVariants. Transforms a `UIImage` (from /cap,
// /org, /edit, etc.) into one of three share-ready encodings:
//
//   .image   — PNG passthrough, no transform.
//   .sticker — 512×512 PNG on a white background, source centered + scaled
//              to fit. Matches Android's STICKER (line 49 fills white,
//              lines 51–58 compute centered scale).
//   .gif     — single-frame GIF89a via ImageIO. Static (matches Android's
//              GifEncoder single-frame output).
//
// Keyboard extensions can't reliably surface UIActivityViewController, so
// callers write `Result.data` to `UIPasteboard.general` under `Result.uti`
// — receiving apps pick up the right representation on paste.

enum ImageVariants {

    enum Variant: String {
        case image
        case sticker
        case gif

        var label: String {
            switch self {
            case .image:   return "Image"
            case .sticker: return "Sticker"
            case .gif:     return "GIF"
            }
        }
    }

    struct Result {
        /// Encoded bytes ready to hand to `UIPasteboard.setData(_:forPasteboardType:)`.
        let data: Data
        /// Uniform Type Identifier. Set this exactly so receiving apps know
        /// what they're getting (`public.png` for image/sticker,
        /// `com.compuserve.gif` for gif).
        let uti: String
        /// Human banner suffix for the success toast.
        let bannerNoun: String
    }

    /// Sticker canvas size — matches Android's `STICKER_PX = 512`.
    private static let stickerSide: CGFloat = 512

    static func make(_ source: UIImage, variant: Variant) -> Result? {
        let trace = KeyboardPerformance.begin("ImageEncode")
        defer { KeyboardPerformance.end("ImageEncode", trace) }
        switch variant {
        case .image:
            guard let data = source.pngData() else { return nil }
            return Result(data: data, uti: UTType.png.identifier, bannerNoun: "Image")

        case .sticker:
            guard let padded = padToSticker(source),
                  let data = padded.pngData() else { return nil }
            return Result(data: data, uti: UTType.png.identifier, bannerNoun: "Sticker")

        case .gif:
            guard let data = encodeGIF(source) else { return nil }
            return Result(data: data, uti: UTType.gif.identifier, bannerNoun: "GIF")
        }
    }

    // MARK: - Sticker (512×512, centered, alpha-preserving)

    private static func padToSticker(_ image: UIImage) -> UIImage? {
        let canvas = CGSize(width: stickerSide, height: stickerSide)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        // Preserve the source's alpha channel. `/sticker` produces a
        // matted transparent PNG via the two-pass difference matte; the
        // old `format.opaque = true` + white fill flattened that result
        // back onto white, silently undoing the whole pipeline. For
        // opaque sources (`/cap`, `/org`) the drawn rect is fully opaque
        // anyway — the only visible difference is the unused canvas
        // margin around the centered image, which is now transparent
        // instead of white.
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: canvas, format: format)
        return renderer.image { _ in
            let src = image.size
            guard src.width > 0, src.height > 0 else { return }
            let scale = min(stickerSide / src.width, stickerSide / src.height)
            let drawSize = CGSize(width: src.width * scale, height: src.height * scale)
            let origin = CGPoint(x: (stickerSide - drawSize.width) / 2,
                                 y: (stickerSide - drawSize.height) / 2)
            image.draw(in: CGRect(origin: origin, size: drawSize))
        }
    }

    // MARK: - GIF (single frame, GIF89a)

    private static func encodeGIF(_ image: UIImage) -> Data? {
        guard let cg = image.cgImage else { return nil }
        let buffer = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(
            buffer, UTType.gif.identifier as CFString, 1, nil
        ) else { return nil }

        // Loop count 0 = repeat forever (irrelevant for a 1-frame GIF but
        // matches what GifEncoder writes on Android).
        let gifProps: [CFString: Any] = [kCGImagePropertyGIFLoopCount: 0]
        CGImageDestinationSetProperties(dest, [kCGImagePropertyGIFDictionary: gifProps] as CFDictionary)
        CGImageDestinationAddImage(dest, cg, [
            kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: 0.0]
        ] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return buffer as Data
    }
}
#endif
