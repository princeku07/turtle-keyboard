import UIKit
import CoreImage
import CoreImage.CIFilterBuiltins

/// Native QR generator backed by `CIFilter.qrCodeGenerator()`. iOS counterpart
/// to Android's ZXing-based `QrRenderer` — no third-party dependency since
/// Core Image ships the encoder. We only need to *render* (the OS camera
/// scans).
enum QrRenderer {

    /// Renders `text` as a square QR `UIImage` of `size` points on each side.
    /// Returns nil if encoding fails (extremely rare for short URLs).
    static func render(_ text: String, size: CGFloat) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        // M = 15% correction — same conservative default Android's ZXing uses.
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }

        // CIFilter emits ~25–50 px modules; scale up with nearest-neighbour
        // so the QR stays crisp at display size.
        let scale = max(1, size / output.extent.width)
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        let context = CIContext()
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg, scale: UIScreen.main.scale, orientation: .up)
    }
}
