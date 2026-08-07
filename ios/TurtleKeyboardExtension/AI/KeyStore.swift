import Foundation
import Security

// MARK: - KeyStore
//
// Provider credentials live in the system Keychain. Non-sensitive model and
// UI preferences may continue to use App Group defaults, but API keys never
// do. The first read migrates a legacy UserDefaults value and deletes it only
// after Keychain confirms the write.

final class KeyStore {
    static let shared = KeyStore()

    private let service = "com.samarth.turtlekeyboard.provider-keys"
    private let legacyDefaults: UserDefaults

    init(legacyDefaults: UserDefaults = .standard) {
        self.legacyDefaults = legacyDefaults
    }

    subscript(provider: ProviderID) -> String? {
        get {
            if let key = read(provider), !key.isEmpty { return key }
            return migrateLegacyKey(for: provider)
        }
        set {
            guard let value = newValue?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !value.isEmpty else {
                delete(provider)
                legacyDefaults.removeObject(forKey: legacyKey(provider))
                return
            }
            if write(value, provider: provider) {
                legacyDefaults.removeObject(forKey: legacyKey(provider))
            }
        }
    }

    func requireKey(for provider: ProviderID) throws -> String {
        guard let key = self[provider], !key.isEmpty else {
            throw ProviderError.missingAPIKey(provider)
        }
        return key
    }

    func setGoogleKey(_ key: String) { self[.google] = key }

    private func baseQuery(_ provider: ProviderID) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: provider.rawValue,
        ]
    }

    private func read(_ provider: ProviderID) -> String? {
        var query = baseQuery(provider)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    private func write(_ value: String, provider: ProviderID) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }
        let query = baseQuery(provider)
        let updates: [String: Any] = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, updates as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }

        var insert = query
        insert[kSecValueData as String] = data
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(insert as CFDictionary, nil) == errSecSuccess
    }

    private func delete(_ provider: ProviderID) {
        SecItemDelete(baseQuery(provider) as CFDictionary)
    }

    private func migrateLegacyKey(for provider: ProviderID) -> String? {
        let key = legacyKey(provider)
        guard let value = legacyDefaults.string(forKey: key), !value.isEmpty else { return nil }
        guard write(value, provider: provider) else { return nil }
        legacyDefaults.removeObject(forKey: key)
        return value
    }

    private func legacyKey(_ provider: ProviderID) -> String {
        "turtle_key_\(provider.rawValue)"
    }
}
