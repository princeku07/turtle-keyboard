import Foundation

/// Refresh-only `SplitFreshTokenProvider` for the keyboard extension. Reads
/// the refresh token the host wrote to the shared Keychain Access Group,
/// exchanges it at Google's token endpoint via plain `URLSession`. No
/// `ASWebAuthenticationSession`, no UIKit — safe to instantiate from any
/// extension that has the Keychain group entitlement.
///
/// If Google returns 400/401 (refresh token revoked), this impl does NOT
/// sign the user out — it just returns the error. The host app will see
/// the revoked state on its next launch and prompt re-auth there.
final class SplitKeychainTokenProvider: SplitFreshTokenProvider {

    private let store: SplitStore

    init(store: SplitStore) {
        self.store = store
    }

    // MARK: - SplitFreshTokenProvider

    var isSignedIn: Bool {
        let signedIn = store.string(forKey: SplitKeys.signedIn, fallback: "") == "1"
        let haveRefresh = (SplitKeychain.get(SplitKeychain.refreshTokenKey) ?? "").isEmpty == false
        return signedIn && haveRefresh
    }

    var accountEmail: String? {
        let e = store.string(forKey: SplitKeys.accountEmail, fallback: "")
        return e.isEmpty ? nil : e
    }

    func freshAccessToken(_ completion: @escaping (Result<String, Error>) -> Void) {
        if let cached = cachedAccessToken() {
            completion(.success(cached))
            return
        }
        let clientID = SplitOAuthConstants.clientID
        guard !clientID.isEmpty else {
            completion(.failure(SplitOAuthConstants.AuthError.notConfigured))
            return
        }
        guard let refresh = SplitKeychain.get(SplitKeychain.refreshTokenKey),
              !refresh.isEmpty
        else {
            completion(.failure(SplitOAuthConstants.AuthError.noRefreshToken))
            return
        }

        var req = URLRequest(url: SplitOAuthConstants.tokenEndpoint)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let body = Self.formEncode([
            "client_id": clientID,
            "refresh_token": refresh,
            "grant_type": "refresh_token",
        ])
        req.httpBody = Data(body.utf8)

        URLSession.shared.dataTask(with: req) { data, resp, err in
            if let err = err { completion(.failure(err)); return }
            guard let http = resp as? HTTPURLResponse, let data = data else {
                completion(.failure(SplitOAuthConstants.AuthError.http(0, "no response")))
                return
            }
            guard (200..<300).contains(http.statusCode) else {
                let body = String(data: data, encoding: .utf8) ?? ""
                completion(.failure(SplitOAuthConstants.AuthError.http(http.statusCode, body)))
                return
            }
            do {
                guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let token = json["access_token"] as? String
                else {
                    completion(.failure(SplitOAuthConstants.AuthError.decode("missing access_token")))
                    return
                }
                let expiresIn = (json["expires_in"] as? Double) ?? 3600
                let expiresAt = Date().timeIntervalSince1970 + expiresIn
                SplitKeychain.set(token, forKey: SplitKeychain.accessTokenKey)
                SplitKeychain.set(String(expiresAt), forKey: SplitKeychain.tokenExpiresAtKey)
                completion(.success(token))
            } catch {
                completion(.failure(error))
            }
        }.resume()
    }

    // MARK: - Helpers

    private func cachedAccessToken() -> String? {
        guard let token = SplitKeychain.get(SplitKeychain.accessTokenKey),
              !token.isEmpty,
              let expStr = SplitKeychain.get(SplitKeychain.tokenExpiresAtKey),
              let expires = TimeInterval(expStr)
        else { return nil }
        if Date().timeIntervalSince1970 + SplitOAuthConstants.refreshSkewSeconds >= expires {
            return nil
        }
        return token
    }

    private static func formEncode(_ params: [String: String]) -> String {
        params.map { k, v in
            let ek = k.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? k
            let ev = v.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? v
            return "\(ek)=\(ev)"
        }.joined(separator: "&")
    }
}
