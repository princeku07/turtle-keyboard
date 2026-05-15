import Foundation

/// Orchestrates cloud sync for `SplitHistory` against the user's own Google
/// Sheet. Local store remains the source of truth for the keyboard panel;
/// this class mirrors saves to the cloud and pulls remote rows on demand.
///
/// All cloud calls are no-ops unless the user is signed in via `SplitOAuth`
/// and a sheet has been provisioned via `ensureSheet`.
final class SplitCloudSync {

    private let store: SplitStore
    private let oauth: SplitOAuth

    init(store: SplitStore, oauth: SplitOAuth) {
        self.store = store
        self.oauth = oauth
    }

    // MARK: - Provisioning

    /// Ensures the user has a "Turtle Splits" spreadsheet in their Drive,
    /// creating one if needed and migrating any pre-existing local rows on
    /// first run. Safe to call on every app launch — short-circuits when
    /// already provisioned.
    @discardableResult
    func ensureSheet() async -> Bool {
        guard oauth.isSignedIn else { return false }

        // Backfill: pre-invite installs created sheets without stamping
        // OWNER_EMAIL. Anyone with a SHEET_ID but no OWNER_EMAIL must own
        // it (joining didn't exist when those sheets were made).
        if !store.string(forKey: SplitKeys.sheetId, fallback: "").isEmpty,
           store.string(forKey: SplitKeys.ownerEmail, fallback: "").isEmpty,
           let me = oauth.accountEmail {
            store.setString(me, forKey: SplitKeys.ownerEmail)
        }

        if !store.string(forKey: SplitKeys.sheetId, fallback: "").isEmpty,
           store.string(forKey: SplitKeys.migratedLocal, fallback: "") == "1" {
            return false
        }

        do {
            let token = try await freshToken()
            var sheetId = store.string(forKey: SplitKeys.sheetId, fallback: "")
            if sheetId.isEmpty {
                sheetId = try await SplitSheetsClient.createSpreadsheet(accessToken: token)
                store.setString(sheetId, forKey: SplitKeys.sheetId)
                if let me = oauth.accountEmail {
                    store.setString(me, forKey: SplitKeys.ownerEmail)
                }
            }
            if store.string(forKey: SplitKeys.migratedLocal, fallback: "") != "1" {
                try await migrateLocalRows(token: token, sheetId: sheetId)
                store.setString("1", forKey: SplitKeys.migratedLocal)
            }
            return true
        } catch {
            return false
        }
    }

    // MARK: - Push

    /// Mirrors a save to the user's sheet. Fire-and-forget — local write
    /// already happened, cloud is best-effort.
    func pushSave(amount: Double, people: Int, timestampMs: Int64) {
        guard oauth.isSignedIn else { return }
        let sheetId = store.string(forKey: SplitKeys.sheetId, fallback: "")
        guard !sheetId.isEmpty else { return }
        let deviceId = ensureDeviceId()
        Task.detached { [oauth = self.oauth] in
            do {
                let token = try await Self.freshToken(oauth: oauth)
                try await SplitSheetsClient.appendRows(
                    accessToken: token,
                    spreadsheetId: sheetId,
                    rows: [Self.buildRow(amount: amount, people: people,
                                         timestampMs: timestampMs, deviceId: deviceId)]
                )
            } catch { /* silent */ }
        }
    }

    /// Mirrors a clear — removes only this device's rows from the sheet.
    func pushClear() {
        pushClearInternal(wipeAll: false)
    }

    /// Owner-only: nukes every data row across all devices. No-op if the
    /// current user isn't the sheet owner.
    func pushClearAll() {
        guard isOwner else { return }
        pushClearInternal(wipeAll: true)
    }

    private func pushClearInternal(wipeAll: Bool) {
        guard oauth.isSignedIn else { return }
        let sheetId = store.string(forKey: SplitKeys.sheetId, fallback: "")
        guard !sheetId.isEmpty else { return }
        let deviceId = ensureDeviceId()
        Task.detached { [oauth = self.oauth] in
            do {
                let token = try await Self.freshToken(oauth: oauth)
                if wipeAll {
                    try await SplitSheetsClient.deleteAllDataRows(
                        accessToken: token, spreadsheetId: sheetId)
                } else {
                    try await SplitSheetsClient.deleteRowsForDevice(
                        accessToken: token, spreadsheetId: sheetId, deviceId: deviceId)
                }
            } catch { /* silent */ }
        }
    }

    // MARK: - Pull

    /// Pulls all rows from the sheet, dedupes against local by
    /// `(timestampMs, amount, people)`, writes anything new.
    /// @return whether local history was modified.
    @discardableResult
    func fetchAndMerge() async -> Bool {
        guard oauth.isSignedIn else { return false }
        let sheetId = store.string(forKey: SplitKeys.sheetId, fallback: "")
        guard !sheetId.isEmpty else { return false }
        do {
            let token = try await freshToken()
            let remote = try await SplitSheetsClient.listRows(
                accessToken: token, spreadsheetId: sheetId)
            return mergeIntoLocal(remote: remote)
        } catch {
            return false
        }
    }

    // MARK: - Membership (owner-side invite link)

    static let deepLinkJoin = "turtlekeyboard://join"

    var isMembershipOpen: Bool {
        !store.string(forKey: SplitKeys.anyonePermissionId, fallback: "").isEmpty
    }

    var isOwner: Bool {
        let me = store.string(forKey: SplitKeys.accountEmail, fallback: "")
        let owner = store.string(forKey: SplitKeys.ownerEmail, fallback: "")
        return !me.isEmpty && me.caseInsensitiveCompare(owner) == .orderedSame
    }

    /// Owner-only: enables anyone-with-link writer sharing on the sheet,
    /// persists the Drive permissionId, and returns a join deep link the
    /// owner can share / render as a QR.
    func openMembership() async -> String? {
        guard isOwner, oauth.isSignedIn else { return nil }
        let sheetId = store.string(forKey: SplitKeys.sheetId, fallback: "")
        guard !sheetId.isEmpty else { return nil }
        do {
            let token = try await freshToken()
            var permId = store.string(forKey: SplitKeys.anyonePermissionId, fallback: "")
            if permId.isEmpty {
                permId = try await SplitDriveClient.grantAnyoneWriter(
                    accessToken: token, fileId: sheetId)
                store.setString(permId, forKey: SplitKeys.anyonePermissionId)
            }
            return buildJoinDeepLink()
        } catch {
            return nil
        }
    }

    /// Owner-only: revokes the anyone-with-link permission.
    @discardableResult
    func closeMembership() async -> Bool {
        guard isOwner, oauth.isSignedIn else { return false }
        let sheetId = store.string(forKey: SplitKeys.sheetId, fallback: "")
        let permId = store.string(forKey: SplitKeys.anyonePermissionId, fallback: "")
        if sheetId.isEmpty || permId.isEmpty {
            store.setString("", forKey: SplitKeys.anyonePermissionId)
            return true
        }
        do {
            let token = try await freshToken()
            try await SplitDriveClient.revokePermission(
                accessToken: token, fileId: sheetId, permissionId: permId)
            store.setString("", forKey: SplitKeys.anyonePermissionId)
            return true
        } catch {
            return false
        }
    }

    func buildJoinDeepLink() -> String {
        let sheetId = store.string(forKey: SplitKeys.sheetId, fallback: "")
        let owner = store.string(forKey: SplitKeys.ownerEmail, fallback: "")
        return "\(Self.deepLinkJoin)?sheetId=\(urlEncode(sheetId))&owner=\(urlEncode(owner))"
    }

    /// Joiner-side: switches the local store onto someone else's sheet
    /// and refreshes from it. Owner must have membership open for the
    /// fetch to succeed.
    @discardableResult
    func joinSharedSheet(sheetId: String, ownerEmail: String) async -> Bool {
        store.setString(sheetId, forKey: SplitKeys.sheetId)
        store.setString(ownerEmail, forKey: SplitKeys.ownerEmail)
        // Joiner doesn't migrate local rows to someone else's sheet —
        // subsequent saves will be mirrored on append.
        store.setString("1", forKey: SplitKeys.migratedLocal)
        store.setString("", forKey: SplitKeys.anyonePermissionId)
        return await fetchAndMerge()
    }

    // MARK: - Helpers

    private func freshToken() async throws -> String {
        try await Self.freshToken(oauth: oauth)
    }

    private static func freshToken(oauth: SplitOAuth) async throws -> String {
        try await withCheckedThrowingContinuation { cont in
            oauth.freshAccessToken { result in
                cont.resume(with: result)
            }
        }
    }

    private func migrateLocalRows(token: String, sheetId: String) async throws {
        let existing = store.string(forKey: SplitKeys.history, fallback: "")
        guard !existing.isEmpty else { return }
        let deviceId = ensureDeviceId()
        var rows: [[Any]] = []
        for line in existing.split(separator: "\n") {
            let parts = line.split(separator: "|")
            guard parts.count == 3,
                  let amount = Double(parts[0]),
                  let people = Int(parts[1]),
                  let ts = Int64(parts[2])
            else { continue }
            rows.append(Self.buildRow(amount: amount, people: people,
                                       timestampMs: ts, deviceId: deviceId))
        }
        guard !rows.isEmpty else { return }
        try await SplitSheetsClient.appendRows(accessToken: token,
                                               spreadsheetId: sheetId,
                                               rows: rows)
    }

    private static func buildRow(amount: Double, people: Int,
                                 timestampMs: Int64, deviceId: String) -> [Any] {
        let per = people > 0 ? amount / Double(people) : amount
        let date = Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000)
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return [
            formatter.string(from: date),
            timestampMs,
            deviceId,
            amount,
            people,
            per,
        ]
    }

    private func mergeIntoLocal(remote: [SplitSheetsClient.Row]) -> Bool {
        let existing = store.string(forKey: SplitKeys.history, fallback: "")
        var seen = Set<String>()
        var merged: [(amount: Double, people: Int, ts: Int64)] = []
        if !existing.isEmpty {
            for line in existing.split(separator: "\n") {
                let parts = line.split(separator: "|")
                guard parts.count == 3,
                      let amount = Double(parts[0]),
                      let people = Int(parts[1]),
                      let ts = Int64(parts[2])
                else { continue }
                let key = "\(ts)|\(amount)|\(people)"
                if seen.insert(key).inserted {
                    merged.append((amount, people, ts))
                }
            }
        }
        var changed = false
        for r in remote {
            let key = "\(r.timestampMs)|\(r.amount)|\(r.people)"
            if seen.insert(key).inserted {
                merged.append((r.amount, r.people, r.timestampMs))
                changed = true
            }
        }
        guard changed else { return false }
        merged.sort { $0.ts > $1.ts }
        if merged.count > SplitHistory.max {
            merged = Array(merged.prefix(SplitHistory.max))
        }
        let out = merged.map { "\($0.amount)|\($0.people)|\($0.ts)" }.joined(separator: "\n")
        store.setString(out, forKey: SplitKeys.history)
        return true
    }

    private func ensureDeviceId() -> String {
        let existing = store.string(forKey: SplitKeys.deviceId, fallback: "")
        if !existing.isEmpty { return existing }
        let id = UUID().uuidString
        store.setString(id, forKey: SplitKeys.deviceId)
        return id
    }

    private func urlEncode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? s
    }
}
