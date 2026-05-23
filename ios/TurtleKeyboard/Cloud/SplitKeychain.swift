import Foundation
import Security

/// Tiny Keychain wrapper for OAuth tokens. Refresh tokens grant indefinite
/// access to the user's Sheets/Drive — they belong in Keychain, not
/// `UserDefaults`. Keys are namespaced under `com.samarth.turtlekeyboard.split`.
///
/// Synchronous; all reads/writes are sub-millisecond. Items are stored
/// under a Keychain Access Group so the host app (writer) and the
/// keyboard extension (reader) can both see the refresh token. The
/// access-group value must match the `keychain-access-groups` entry in
/// both targets' entitlements (`$(AppIdentifierPrefix)` + this string).
enum SplitKeychain {

    private static let service = "com.samarth.turtlekeyboard.split"

    /// Group both targets must declare in their entitlements. Xcode prefixes
    /// this with the team identifier at signing time.
    static let accessGroup = "com.samarth.turtlekeyboard.split"

    private static func baseQuery(forKey key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecAttrAccessGroup as String: accessGroup,
        ]
    }

    static func set(_ value: String, forKey key: String) {
        let data = Data(value.utf8)
        let query = baseQuery(forKey: key)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var add = query
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    static func get(_ key: String) -> String? {
        var query = baseQuery(forKey: key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var ref: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &ref)
        guard status == errSecSuccess,
              let data = ref as? Data,
              let s = String(data: data, encoding: .utf8)
        else { return nil }
        return s
    }

    static func delete(_ key: String) {
        let query = baseQuery(forKey: key)
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - Token slots

    static let accessTokenKey = "split_access_token"
    static let refreshTokenKey = "split_refresh_token"
    static let tokenExpiresAtKey = "split_token_expires_at"
}
