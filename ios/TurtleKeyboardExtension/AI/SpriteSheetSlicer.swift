import Foundation
import CoreGraphics

/// Slices a sprite-sheet `CGImage` into `cols × rows` equal cells, returned
/// row-major (left-to-right, top-to-bottom). Companion to `/gif`, which
/// asks Gemini for a single sheet of frames laid out in a fixed grid and
/// then needs them as discrete images for animated-GIF encoding.
///
/// Direct port of `android/.../integration/gif/SpriteSheetSlicer.java` with
/// the same flooring behaviour: if the sheet's dimensions aren't exact
/// multiples of `cols × rows`, cells take the floored size and trailing
/// rows / columns of pixels are dropped. Nano Banana sometimes returns a
/// sheet 1–2 px off the requested size; flooring keeps every cell the
/// same size rather than mixing a fat row at the edge.
///
/// Grid shape is detected at runtime from the sheet's aspect ratio — see
/// `gridRows(forAspect:)` for the thresholds. Common layouts:
///   • `4×4` (16 frames) — sheet 1024×1024 ⇒ aspect ≈ 1.0
///   • `4×2`  (8 frames) — sheet 1024× 512 ⇒ aspect ≈ 2.0
///   • `4×1`  (4 frames) — sheet 1024× 256 ⇒ aspect ≈ 4.0
enum SpriteSheetSlicer {

    /// Column count is locked to 4 by the /gif system prompt (mirrors
    /// Android's `GifIntegration.COLS`). Exposed so callers don't have to
    /// hard-code it.
    static let cols: Int = 4

    /// Aspect midpoints between the nominal layouts (1.0, 2.0, 4.0) so
    /// noise on either side doesn't flip the detection.
    static let grid4x4MaxAspect:  Double = 1.5
    static let strip4x1MinAspect: Double = 3.0

    /// Per-frame delay in **centiseconds** (matches GIF spec graphics-control
    /// extension). Each layout targets ≈ 1-second loop:
    ///   • 16 frames ×  6 cs = 0.96 s
    ///   •  8 frames × 12 cs = 0.96 s
    ///   •  4 frames × 25 cs = 1.00 s
    static func frameDelayCentiseconds(forRows rows: Int) -> Int {
        switch rows {
        case 1: return 25
        case 2: return 12
        default: return 6  // 4 rows (16-frame 4×4 grid)
        }
    }

    /// Row count for a sheet aspect ratio (sheet width ÷ height). Below
    /// `grid4x4MaxAspect` is the 4×4 square; up to `strip4x1MinAspect` is
    /// the 4×2 wide grid; above is the 4×1 strip.
    static func gridRows(forAspect aspect: Double) -> Int {
        if aspect > strip4x1MinAspect { return 1 }
        if aspect > grid4x4MaxAspect  { return 2 }
        return 4
    }

    /// Slice `sheet` into `cols × rows` equal cells in row-major order.
    /// Returns nil for non-positive dimensions or a sheet too small to
    /// produce at least 1×1 cells.
    static func slice(_ sheet: CGImage, cols: Int, rows: Int) -> [CGImage]? {
        guard cols > 0, rows > 0 else { return nil }
        let cellW = sheet.width / cols
        let cellH = sheet.height / rows
        guard cellW > 0, cellH > 0 else { return nil }

        var frames: [CGImage] = []
        frames.reserveCapacity(cols * rows)
        for r in 0..<rows {
            for c in 0..<cols {
                let rect = CGRect(x: c * cellW, y: r * cellH, width: cellW, height: cellH)
                guard let frame = sheet.cropping(to: rect) else { return nil }
                frames.append(frame)
            }
        }
        return frames
    }
}
