import Foundation
import ImageIO
import UIKit

// MARK: - HistoryThumbnails
//
// Memory-safe reader for the shared `/cap` + `/org` image history.
//
// Widget extensions get a far tighter memory ceiling than the host app
// (roughly 30 MB). `ImageHistory` stores full-resolution PNGs — a single
// 1024x1024 entry decodes to ~4 MB of bitmap, so naively loading the nine
// images a large widget shows would blow the budget and iOS would render a
// blank placeholder instead of the widget.
//
// Everything here therefore goes through ImageIO's thumbnail path:
// `CGImageSourceCreateWithURL` never reads the whole file into memory (the
// bytes stay memory-mapped), and `CGImageSourceCreateThumbnailAtIndex`
// decodes *directly* to the requested size rather than decoding full-res and
// scaling down. Peak cost per image is the thumbnail bitmap alone.
//
// Same technique `AI/ImageDownsizer.downsizedPNG(fromData:)` uses to keep
// PHPicker imports from killing the keyboard extension.

enum HistoryThumbnails {

    /// One history entry paired with an already-downsampled image.
    struct Item: Identifiable {
        let id: Int64          // timestampMs — stable across reloads
        let command: String    // "cap", "org", …
        let prompt: String
        let image: UIImage

        /// Deep link into the host app's History screen. The timestamp
        /// rides along so the app can scroll to this entry later; the
        /// current route ignores it and just opens the grid.
        var deepLink: URL? {
            URL(string: "turtlekeyboard://history?ts=\(id)")
        }
    }

    /// Newest `limit` entries, downsampled to `maxPixel` on the long edge.
    ///
    /// Entries whose PNG can't be decoded are skipped rather than rendered
    /// as a gap — a half-written file from a keyboard that was suspended
    /// mid-save shouldn't punch a hole in the grid.
    static func recent(limit: Int, maxPixel: CGFloat) -> [Item] {
        guard limit > 0 else { return [] }
        var items: [Item] = []
        items.reserveCapacity(limit)

        for entry in ImageHistory.list().prefix(limit) {
            // Each iteration drains its own pool so the CGImage backing the
            // previous thumbnail is gone before the next decode starts.
            autoreleasepool {
                guard let image = downsample(url: entry.pngURL, maxPixel: maxPixel) else { return }
                items.append(Item(id: entry.timestampMs,
                                  command: entry.command,
                                  prompt: entry.prompt,
                                  image: image))
            }
        }
        return items
    }

    // MARK: - Internals

    /// Decode-direct-to-thumbnail. Returns nil for unreadable files.
    private static func downsample(url: URL, maxPixel: CGFloat) -> UIImage? {
        let sourceOptions: [CFString: Any] = [
            // Defer decode until the thumbnail request below.
            kCGImageSourceShouldCache: false
        ]
        guard let source = CGImageSourceCreateWithURL(url as CFURL,
                                                      sourceOptions as CFDictionary)
        else { return nil }

        let thumbOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0,
                                                                thumbOptions as CFDictionary)
        else { return nil }

        return UIImage(cgImage: cgImage)
    }
}
