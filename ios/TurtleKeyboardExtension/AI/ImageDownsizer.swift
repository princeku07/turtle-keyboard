import Foundation
#if os(iOS)
import UIKit

// MARK: - ImageDownsizer
//
// Port of android/.../ai/ImageDownsizer. Caps a reference image to a
// reasonable longest-side so Gemini's flash-image model doesn't reject
// it for size (and so we're not shipping a 12 MP selfie to the wire
// every /edit call).
//
// Returns the PNG bytes of the downsized image. Caller is responsible
// for keeping the source `UIImage` alive until the call returns.

enum ImageDownsizer {

    /// Longest side, in points. Android uses 1024 — matches Gemini's
    /// effective resolution for flash-image and keeps PNG payloads
    /// under ~1 MB for a typical photo.
    static let maxSide: CGFloat = 1024

    static func downsizedPNG(_ image: UIImage) -> Data? {
        let resized = resize(image, maxSide: maxSide)
        return resized.pngData()
    }

    private static func resize(_ image: UIImage, maxSide: CGFloat) -> UIImage {
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
