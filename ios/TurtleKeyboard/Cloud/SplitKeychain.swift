import Foundation
import Security

/// Tiny Keychain wrapper for OAuth tokens. Refresh tokens grant indefinite
/// access to the user's Sheets/Drive — they belong in Keychain, not
/// `UserDefaults`. Keys are namespaced under `com.turtlekeyboard.split`.
///
/// Synchronous; all reads/writes are sub-millisecond. No App Group sharing
/// because cloud sync runs only in the host app for now.
enum SplitKeychain {

    private static let service = "com.turtlekeyboard.split"

    static func set(_ value: String, forKey key: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        // Try update first; on item-not-found, add.
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
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var ref: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &ref)
        guard status == errSecSuccess,
              let data = ref as? Data,
              let s = String(data: data, encoding: .utf8)
        else { return nil }
        return s
    }

    static func delete(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - Token slots

    static let accessTokenKey = "split_access_token"
    static let refreshTokenKey = "split_refresh_token"
    static let tokenExpiresAtKey = "split_token_expires_at"
}
