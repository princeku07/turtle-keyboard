import Foundation
#if os(iOS)
import UIKit

// MARK: - ImageHistory
//
// Port of android/.../ai/ImageHistory. Tiny append-only log of image
// outputs from /cap and /org. Each entry is a PNG file plus a one-line
// sidecar text file holding the command name + the user's prompt.
//
// Storage lives under the App Group container so the keyboard extension
// (writer) and host app's HistoryViewController (reader) see the same
// files. Falls back to the extension's own `Library/Caches/` when the
// shared container isn't reachable — so the keyboard at least keeps a
// local history during dev before the App Group is provisioned.
//
// Capacity = 100 entries, most-recent first. Oldest is pruned on every
// new record (mirrors `MAX_ENTRIES = 100` in the Android source).

enum ImageHistory {

    static let appGroupIdentifier = "group.com.samarth.turtlekeyboard.split"
    static let maxEntries = 100

    struct Entry: Equatable {
        let timestampMs: Int64
        let command: String      // e.g. "cap", "org"
        let prompt: String       // user-typed prompt; "" for /org
        let pngURL: URL

        /// Display-formatted date string. Computed because Date is heavy
        /// when 100 entries are listed at once.
        var date: Date {
            Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000)
        }
    }

    // MARK: - Public API

    /// Append a new entry. PNG bytes are written to disk; the sidecar
    /// records the command + prompt for later display.
    @discardableResult
    static func record(image: UIImage, command: String, prompt: String) -> Entry? {
        guard let dir = historyDirectory() else { return nil }
        let ts = Int64(Date().timeIntervalSince1970 * 1000)
        let pngURL = dir.appendingPathComponent("\(ts).png")
        let txtURL = dir.appendingPathComponent("\(ts).txt")

        guard let png = image.pngData() else { return nil }
        do {
            try png.write(to: pngURL, options: .atomic)
            let sidecar = "\(command)\n\(prompt)"
            try sidecar.write(to: txtURL, atomically: true, encoding: .utf8)
        } catch {
            return nil
        }

        prune(dir: dir)
        return Entry(timestampMs: ts, command: command, prompt: prompt, pngURL: pngURL)
    }

    /// All entries, most-recent first.
    static func list() -> [Entry] {
        guard let dir = historyDirectory(),
              let files = try? FileManager.default.contentsOfDirectory(at: dir,
                                                                       includingPropertiesForKeys: nil)
        else { return [] }
        var entries: [Entry] = []
        for url in files where url.pathExtension == "png" {
            let stem = url.deletingPathExtension().lastPathComponent
            guard let ts = Int64(stem) else { continue }
            let txt = url.deletingPathExtension().appendingPathExtension("txt")
            let sidecar = (try? String(contentsOf: txt, encoding: .utf8)) ?? "\n"
            let parts = sidecar.split(separator: "\n", maxSplits: 1,
                                      omittingEmptySubsequences: false)
            let command = parts.indices.contains(0) ? String(parts[0]) : ""
            let prompt  = parts.indices.contains(1) ? String(parts[1]) : ""
            entries.append(Entry(timestampMs: ts, command: command,
                                 prompt: prompt, pngURL: url))
        }
        entries.sort { $0.timestampMs > $1.timestampMs }
        return entries
    }

    /// Delete every saved entry. The directory itself is preserved.
    static func clear() {
        guard let dir = historyDirectory(),
              let files = try? FileManager.default.contentsOfDirectory(at: dir,
                                                                       includingPropertiesForKeys: nil)
        else { return }
        for url in files {
            try? FileManager.default.removeItem(at: url)
        }
    }

    // MARK: - Internals

    private static func historyDirectory() -> URL? {
        let fm = FileManager.default
        let base: URL?
        if let group = fm.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier) {
            base = group
        } else {
            // Fall back to the keyboard extension's own Caches dir — works
            // before App Group entitlement is provisioned, but the host app
            // won't see anything saved here.
            base = try? fm.url(for: .cachesDirectory, in: .userDomainMask,
                               appropriateFor: nil, create: true)
        }
        guard let root = base else { return nil }
        let dir = root.appendingPathComponent("turtle_image_history", isDirectory: true)
        if !fm.fileExists(atPath: dir.path) {
            do { try fm.createDirectory(at: dir, withIntermediateDirectories: true) }
            catch { return nil }
        }
        return dir
    }

    private static func prune(dir: URL) {
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir,
                                                                       includingPropertiesForKeys: nil)
        else { return }
        let pngs = files.filter { $0.pathExtension == "png" }
        guard pngs.count > maxEntries else { return }
        // Sort oldest-first by parsed timestamp stem; drop the head we don't
        // want and remove both the PNG and its sidecar.
        let sorted = pngs.sorted { a, b in
            (Int64(a.deletingPathExtension().lastPathComponent) ?? 0)
                < (Int64(b.deletingPathExtension().lastPathComponent) ?? 0)
        }
        let overflow = sorted.count - maxEntries
        for url in sorted.prefix(overflow) {
            try? FileManager.default.removeItem(at: url)
            let sidecar = url.deletingPathExtension().appendingPathExtension("txt")
            try? FileManager.default.removeItem(at: sidecar)
        }
    }
}
#endif
