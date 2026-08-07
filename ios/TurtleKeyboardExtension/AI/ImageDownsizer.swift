import Foundation
import ImageIO
#if os(iOS)
import UIKit

// MARK: - ImageDownsizer
//
// Port of android/.../ai/ImageDownsizer. Caps a reference image to a
// reasonable longest-side so Gemini's flash-image model doesn't reject
// it for size (and so we're not shipping a 12 MP selfie to the wire
// every /edit call).
//
// Two entry points:
//   • `downsizedPNG(_ image:)`         — slower, decodes the full UIImage
//                                        first then redraws into a target
//                                        canvas. Kept for callers that
//                                        already hold a UIImage.
//   • `downsizedPNG(fromData:)`        — preferred for keyboard-extension
//                                        use. Uses `CGImageSource` thumbnail
//                                        APIs which decode straight to the
//                                        target size — never materialises the
//                                        full-resolution decode in RAM. This
//                                        is the difference between a 50 MB
//                                        spike (extension killed by iOS) and
//                                        a 1–3 MB peak (safe).

enum ImageDownsizer {

    /// Longest side, in points. Android uses 1024 — matches Gemini's
    /// effective resolution for flash-image and keeps PNG payloads
    /// under ~1 MB for a typical photo.
    static let maxSide: CGFloat = 1024

    static func downsizedPNG(_ image: UIImage) -> Data? {
        let resized = resize(image, maxSide: maxSide)
        return resized.pngData()
    }

    /// Decode-direct-to-thumbnail path. Hands raw bytes (HEIC / JPEG /
    /// PNG / …) to ImageIO and asks for a `maxSide`-capped thumbnail; the
    /// full-res frame never has to live in memory. Returns PNG bytes
    /// ready to ship to the AI provider.
    static func downsizedPNG(fromData data: Data) -> Data? {
        let trace = KeyboardPerformance.begin("ImageDecode")
        defer { KeyboardPerformance.end("ImageDecode", trace) }
        guard !data.isEmpty,
              let src = CGImageSourceCreateWithData(data as CFData, [
                  kCGImageSourceShouldCache: false,
              ] as CFDictionary)
        else { return nil }

        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: false,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary) else {
            return nil
        }

        // Re-encode the thumbnail as PNG via ImageIO (avoids round-tripping
        // through UIImage, which costs another decode of the thumbnail).
        let mut = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(mut, "public.png" as CFString, 1, nil) else {
            return nil
        }
        CGImageDestinationAddImage(dest, cg, nil)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return mut as Data
    }

    private static func resize(_ image: UIImage, maxSide: CGFloat) -> UIImage {
        let trace = KeyboardPerformance.begin("ImageRender")
        defer { KeyboardPerformance.end("ImageRender", trace) }
        let size = image.size
        let longest = max(size.width, size.height)
        guard longest > maxSide, size.width > 0, size.height > 0 else { return image }
        let scale = maxSide / longest
        let target = CGSize(width: floor(size.width * scale),
                            height: floor(size.height * scale))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }
}
#endif
