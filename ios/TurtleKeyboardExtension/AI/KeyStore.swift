import Foundation

// MARK: - KeyStore
//
// Stores provider API keys.
//
// Current: UserDefaults.standard (isolated to extension sandbox).
// TODO: migrate to Keychain with kSecAttrAccessGroup = "group.com.samarth.turtlekeyboard"
//       once App Groups is configured, so the host app can write keys that the
//       keyboard extension can read.

final class KeyStore {
    static let shared = KeyStore()
    private init() {}

    // TODO: replace with UserDefaults(suiteName: "group.com.samarth.turtlekeyboard")
    private let defaults: UserDefaults = .standard

    subscript(provider: ProviderID) -> String? {
        get { defaults.string(forKey: "turtle_key_\(provider.rawValue)") }
        set { defaults.set(newValue, forKey: "turtle_key_\(provider.rawValue)") }
    }

    /// Throws ProviderError.missingAPIKey if no key is stored for this provider.
    func requireKey(for provider: ProviderID) throws -> String {
        guard let key = self[provider], !key.isEmpty else {
            throw ProviderError.missingAPIKey(provider)
        }
        return key
    }

    /// Convenience setter called from host-app onboarding and from
    /// `CommandRouter.init` (which bootstraps the key from the
    /// build-time `.env` → `Secrets.geminiApiKey`).
    func setGoogleKey(_ key: String) { self[.google] = key }
}
