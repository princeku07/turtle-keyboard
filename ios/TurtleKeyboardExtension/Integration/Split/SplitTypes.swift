import Foundation

// MARK: - SplitContract

/// Identifiers shared between the keyboard and (future) standalone Split
/// app — ports Android's `SplitContract`. Single point of agreement so
/// migrating Split out of the keyboard later is a build-system change, not
/// a data-layout change.
enum SplitContract {
    static let storageSuiteName = "group.com.samarth.turtlekeyboard.split"
    static let minPeople: Int = 1
    static let maxPeople: Int = 99
    static let defaultPeople: Int = 2
}

// MARK: - SplitKeys

/// Persistent keys owned by the Split SDK. Mirrors Android's `SplitKeys`
/// so a future cloud-sync impl can write to the same logical schema.
enum SplitKeys {
    static let history = "split_history"
    static let defaultPeople = "split_default_people"
    static let deviceId = "split_device_id"
    static let enabled = "split_enabled"

    // Cloud sync — defined now so storage layout matches Android even
    // though the iOS port doesn't ship cloud sync in the MVP.
    static let signedIn = "split_signed_in"
    static let accountEmail = "split_account_email"
    static let accessToken = "split_access_token"
    static let tokenExpiresAt = "split_token_expires_at"
    static let sheetId = "split_sheet_id"
    static let ownerEmail = "split_owner_email"
    static let migratedLocal = "split_migrated_local"
    static let anyonePermissionId = "split_anyone_permission_id"
}

// MARK: - SplitStore

/// Narrow key/value store the SDK needs to persist split state. Decouples
/// the SDK from any particular storage backend — the keyboard uses
/// `UserDefaultsSplitStore`; a future standalone Split app could implement
/// this against its own DB without the SDK changing.
protocol SplitStore: AnyObject {
    func string(forKey key: String, fallback: String) -> String
    func int(forKey key: String, fallback: Int) -> Int
    func setString(_ value: String, forKey key: String)
    func setInt(_ value: Int, forKey key: String)
}

/// Default `SplitStore` backed by `UserDefaults`. Uses an App Group suite
/// when one is configured so the keyboard extension and host app can share
/// state; falls back to standard defaults when the suite isn't available
/// (which is the MVP state — no App Group is wired yet).
final class UserDefaultsSplitStore: SplitStore {

    private let defaults: UserDefaults

    init(suiteName: String? = nil) {
        if let name = suiteName, let suite = UserDefaults(suiteName: name) {
            self.defaults = suite
        } else {
            self.defaults = .standard
        }
    }

    func string(forKey key: String, fallback: String) -> String {
        defaults.string(forKey: key) ?? fallback
    }

    func int(forKey key: String, fallback: Int) -> Int {
        // `object(forKey:)` distinguishes "key absent" from "key is 0".
        guard defaults.object(forKey: key) != nil else { return fallback }
        return defaults.integer(forKey: key)
    }

    func setString(_ value: String, forKey key: String) {
        defaults.set(value, forKey: key)
    }

    func setInt(_ value: Int, forKey key: String) {
        defaults.set(value, forKey: key)
    }
}

// MARK: - SplitHistory

/// Tiny persistent log of saved splits, kept as newline-delimited
/// `amount|people|timestampMs` entries via a caller-supplied `SplitStore`.
/// Most-recent first, capped at `max`. Single-line records keep parsing
/// trivial without pulling in a JSON dependency. Mirrors Android format
/// exactly so future cross-device sync sees identical strings.
final class SplitHistory {

    static let max = 50

    struct Entry {
        let amount: Double
        let people: Int
        let timestampMs: Int64
    }

    private let store: SplitStore

    init(store: SplitStore) {
        self.store = store
    }

    /// @return the timestamp stamped on the new entry, so callers can mirror
    /// to the cloud later.
    @discardableResult
    func add(amount: Double, people: Int) -> Int64 {
        let ts = Int64(Date().timeIntervalSince1970 * 1000)
        let line = "\(amount)|\(people)|\(ts)"
        let existing = store.string(forKey: SplitKeys.history, fallback: "")
        var next = line
        if !existing.isEmpty {
            let lines = existing.split(separator: "\n", omittingEmptySubsequences: false)
            let keep = min(lines.count, Self.max - 1)
            for i in 0..<keep {
                next += "\n"
                next += String(lines[i])
            }
        }
        store.setString(next, forKey: SplitKeys.history)
        return ts
    }

    func all() -> [Entry] {
        let s = store.string(forKey: SplitKeys.history, fallback: "")
        guard !s.isEmpty else { return [] }
        var out: [Entry] = []
        for raw in s.split(separator: "\n", omittingEmptySubsequences: true) {
            let parts = raw.split(separator: "|")
            guard parts.count == 3,
                  let amount = Double(parts[0]),
                  let people = Int(parts[1]),
                  let ts = Int64(parts[2])
            else { continue }
            out.append(Entry(amount: amount, people: people, timestampMs: ts))
        }
        return out
    }

    func clear() {
        store.setString("", forKey: SplitKeys.history)
    }
}

// MARK: - AmountWatcher

/// Pure-logic watcher for amount-shaped input. The IME feeds it the field's
/// current text (before + after cursor); the watcher emits a normalized
/// amount when the field looks like an amount and `nil` when it doesn't.
final class AmountWatcher {

    typealias Listener = (_ amount: String?) -> Void

    /// 1–7 digits, optionally followed by .[1-2 digits]. ₹9,999,999 ceiling.
    private static let amountPattern = #"^\d{1,7}(\.\d{1,2})?$"#

    private let listener: Listener
    private var armed = false
    private var lastEmitted: String?

    init(listener: @escaping Listener) {
        self.listener = listener
    }

    func arm() { armed = true }

    func disarm() {
        armed = false
        emit(nil)
    }

    func onTextChanged(before: String, after: String) {
        guard armed else { emit(nil); return }
        let raw = before + after
        let cleaned = raw.unicodeScalars
            .filter { CharacterSet(charactersIn: "0123456789.").contains($0) }
            .map { String($0) }
            .joined()
        emit(Self.isAmount(cleaned) ? cleaned : nil)
    }

    private func emit(_ value: String?) {
        if value == lastEmitted { return }
        lastEmitted = value
        listener(value)
    }

    static func isAmount(_ s: String) -> Bool {
        guard !s.isEmpty,
              s.range(of: amountPattern, options: .regularExpression) != nil,
              let v = Double(s), v > 0
        else { return false }
        return true
    }
}
