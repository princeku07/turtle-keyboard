import Foundation
import UIKit
import AuthenticationServices

/// Google OAuth 2.0 with PKCE for iOS. Mirrors the auth slice of Android's
/// `SplitAuth` but uses the iOS-native flow (`ASWebAuthenticationSession`)
/// instead of Play Services.
///
/// Tokens live in `SplitKeychain`; sheet/owner pointers live in `SplitStore`.
/// Access tokens last ~1 hour; we use the refresh token to mint fresh ones
/// silently.
final class SplitOAuth: NSObject, SplitFreshTokenProvider {

    // MARK: - User-supplied configuration
    //
    // Replace these with the values from your Google Cloud Console iOS
    // OAuth client (see OAUTH_SETUP_iOS.md). Until they're filled in, all
    // network methods short-circuit with `.notConfigured`.

    /// Prefer the iOS OAuth client when set in .env. Falls back to the
    /// web client only as a last resort — Google Web clients reject
    /// custom-scheme redirects, so without the iOS client this flow
    /// can't complete on iOS.
    static var clientID: String { SplitOAuthConstants.clientID }

    /// True when an iOS OAuth client is configured. Drives which redirect
    /// scheme we use.
    static var hasIOSClient: Bool { !Secrets.splitOauthIosClientId.isEmpty }

    /// Reverse-client-ID URL scheme. Google's iOS OAuth clients auto-accept
    /// `<reverse>:/oauth2redirect` — no dashboard registration needed.
    /// Example: `123456-abc.apps.googleusercontent.com` reverses to
    /// `com.googleusercontent.apps.123456-abc`.
    static var reverseClientID: String {
        let id = Secrets.splitOauthIosClientId
        guard !id.isEmpty else { return "" }
        let withoutSuffix = id.replacingOccurrences(of: ".apps.googleusercontent.com", with: "")
        return "com.googleusercontent.apps.\(withoutSuffix)"
    }

    /// `<reverseClientID>:/oauth2redirect` for the iOS client (Google's
    /// documented format — note single slash). Falls back to the
    /// custom-scheme form for the web-client path (which won't actually
    /// work but at least produces a sensible error message).
    static var redirectScheme: String {
        hasIOSClient ? reverseClientID : "turtlegoogleoauth"
    }
    static var redirectURI: String {
        hasIOSClient ? "\(reverseClientID):/oauth2redirect"
                     : "turtlegoogleoauth://oauth-callback"
    }

    static let scopes: [String] = [
        "https://www.googleapis.com/auth/spreadsheets",
        "https://www.googleapis.com/auth/drive.file",
        "email",
    ]

    static let authEndpoint = URL(string: "https://accounts.google.com/o/oauth2/v2/auth")!
    static var tokenEndpoint: URL { SplitOAuthConstants.tokenEndpoint }
    static let userinfoEndpoint = URL(string: "https://www.googleapis.com/oauth2/v3/userinfo")!

    /// Buffer subtracted from token expiry so we refresh before it dies.
    static var refreshSkewSeconds: TimeInterval { SplitOAuthConstants.refreshSkewSeconds }

    typealias AuthError = SplitOAuthConstants.AuthError

    private let store: SplitStore
    private weak var anchor: UIWindow?
    private var activeSession: ASWebAuthenticationSession?

    init(store: SplitStore, presentationAnchor: UIWindow?) {
        self.store = store
        self.anchor = presentationAnchor
    }

    var isConfigured: Bool { !Self.clientID.isEmpty }

    var isSignedIn: Bool {
        store.string(forKey: SplitKeys.signedIn, fallback: "") == "1"
    }

    var accountEmail: String? {
        let e = store.string(forKey: SplitKeys.accountEmail, fallback: "")
        return e.isEmpty ? nil : e
    }

    func signOut() {
        SplitKeychain.delete(SplitKeychain.accessTokenKey)
        SplitKeychain.delete(SplitKeychain.refreshTokenKey)
        SplitKeychain.delete(SplitKeychain.tokenExpiresAtKey)
        store.setString("", forKey: SplitKeys.signedIn)
        store.setString("", forKey: SplitKeys.accountEmail)
        // Sheet pointers / migrated flag deliberately left intact so the
        // user can sign in again on the same install without losing splits.
    }

    // MARK: - Token access

    /// Returns a non-expired access token if one is cached, else nil.
    func cachedAccessToken() -> String? {
        guard let token = SplitKeychain.get(SplitKeychain.accessTokenKey),
              !token.isEmpty,
              let expStr = SplitKeychain.get(SplitKeychain.tokenExpiresAtKey),
              let expires = TimeInterval(expStr)
        else { return nil }
        if Date().timeIntervalSince1970 + Self.refreshSkewSeconds >= expires {
            return nil
        }
        return token
    }

    /// Hands back a valid access token. Uses cache if fresh, otherwise
    /// refreshes silently using the stored refresh token.
    func freshAccessToken(_ completion: @escaping (Result<String, Error>) -> Void) {
        if let cached = cachedAccessToken() {
            completion(.success(cached))
            return
        }
        guard isConfigured else { completion(.failure(AuthError.notConfigured)); return }
        guard let refresh = SplitKeychain.get(SplitKeychain.refreshTokenKey),
              !refresh.isEmpty
        else { completion(.failure(AuthError.noRefreshToken)); return }

        var req = URLRequest(url: Self.tokenEndpoint)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let body = formEncode([
            "client_id": Self.clientID,
            "refresh_token": refresh,
            "grant_type": "refresh_token",
        ])
        req.httpBody = Data(body.utf8)

        URLSession.shared.dataTask(with: req) { [weak self] data, resp, err in
            guard let self = self else { return }
            if let err = err { completion(.failure(err)); return }
            guard let http = resp as? HTTPURLResponse, let data = data else {
                completion(.failure(AuthError.http(0, "no response"))); return
            }
            if http.statusCode == 400 || http.statusCode == 401 {
                // Refresh token has been revoked — force a full re-auth.
                self.signOut()
            }
            self.handleTokenResponse(data: data, http: http, completion: completion)
        }.resume()
    }

    // MARK: - Sign-in flow (PKCE)

    func signIn(_ completion: @escaping (Result<String, Error>) -> Void) {
        guard isConfigured else { completion(.failure(AuthError.notConfigured)); return }

        // PKCE: hash a high-entropy random string with SHA-256, base64url-
        // encode → that's the challenge sent in the auth URL. We exchange
        // the verifier (raw random) for a token at the token endpoint.
        let verifier = Self.randomVerifier()
        let challenge = Self.codeChallenge(for: verifier)

        var components = URLComponents(url: Self.authEndpoint, resolvingAgainstBaseURL: false)!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: Self.clientID),
            URLQueryItem(name: "redirect_uri", value: Self.redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: Self.scopes.joined(separator: " ")),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "prompt", value: "consent"),
        ]
        guard let url = components.url else {
            completion(.failure(AuthError.decode("bad auth URL"))); return
        }

        let session = ASWebAuthenticationSession(
            url: url,
            callbackURLScheme: Self.redirectScheme
        ) { [weak self] callbackURL, error in
            guard let self = self else { return }
            if let error = error {
                let nsError = error as NSError
                if nsError.code == ASWebAuthenticationSessionError.canceledLogin.rawValue {
                    completion(.failure(AuthError.userCancelled))
                } else {
                    completion(.failure(error))
                }
                return
            }
            guard let callback = callbackURL,
                  let code = URLComponents(url: callback, resolvingAgainstBaseURL: false)?
                    .queryItems?.first(where: { $0.name == "code" })?.value
            else { completion(.failure(AuthError.missingCode)); return }
            self.exchangeCodeForToken(code: code, verifier: verifier, completion: completion)
        }
        session.presentationContextProvider = self
        session.prefersEphemeralWebBrowserSession = false
        DispatchQueue.main.async {
            self.activeSession = session
            session.start()
        }
    }

    // MARK: - Internals

    private func exchangeCodeForToken(
        code: String,
        verifier: String,
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        var req = URLRequest(url: Self.tokenEndpoint)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let body = formEncode([
            "client_id": Self.clientID,
            "code": code,
            "code_verifier": verifier,
            "grant_type": "authorization_code",
            "redirect_uri": Self.redirectURI,
        ])
        req.httpBody = Data(body.utf8)

        URLSession.shared.dataTask(with: req) { [weak self] data, resp, err in
            guard let self = self else { return }
            if let err = err { completion(.failure(err)); return }
            guard let http = resp as? HTTPURLResponse, let data = data else {
                completion(.failure(AuthError.http(0, "no response"))); return
            }
            self.handleTokenResponse(data: data, http: http, completion: completion)
        }.resume()
    }

    private func handleTokenResponse(
        data: Data,
        http: HTTPURLResponse,
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        guard (200..<300).contains(http.statusCode) else {
            let msg = String(data: data, encoding: .utf8) ?? ""
            completion(.failure(AuthError.http(http.statusCode, msg))); return
        }
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            completion(.failure(AuthError.decode("non-JSON token response"))); return
        }
        guard let access = json["access_token"] as? String else {
            completion(.failure(AuthError.decode("no access_token in response"))); return
        }
        // expires_in is seconds; subtract skew so we refresh early.
        let expiresIn = (json["expires_in"] as? TimeInterval)
            ?? (json["expires_in"] as? Int).map(TimeInterval.init)
            ?? 3600
        let expiresAt = Date().timeIntervalSince1970 + expiresIn

        SplitKeychain.set(access, forKey: SplitKeychain.accessTokenKey)
        SplitKeychain.set(String(expiresAt), forKey: SplitKeychain.tokenExpiresAtKey)
        if let refresh = json["refresh_token"] as? String, !refresh.isEmpty {
            // Refresh tokens come back only on first auth (or when prompt=consent
            // re-issues one). Persist whenever present, never clear on later refresh.
            SplitKeychain.set(refresh, forKey: SplitKeychain.refreshTokenKey)
        }
        store.setString("1", forKey: SplitKeys.signedIn)

        // Best-effort email lookup for the UI label.
        if accountEmail == nil {
            fetchEmail(accessToken: access)
        }
        completion(.success(access))
    }

    private func fetchEmail(accessToken: String) {
        var req = URLRequest(url: Self.userinfoEndpoint)
        req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        URLSession.shared.dataTask(with: req) { [store] data, _, _ in
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let email = json["email"] as? String
            else { return }
            store.setString(email, forKey: SplitKeys.accountEmail)
            // Owner backfill for legacy installs.
            if !store.string(forKey: SplitKeys.sheetId, fallback: "").isEmpty,
               store.string(forKey: SplitKeys.ownerEmail, fallback: "").isEmpty {
                store.setString(email, forKey: SplitKeys.ownerEmail)
            }
        }.resume()
    }

    private func formEncode(_ params: [String: String]) -> String {
        params.map { k, v in
            "\(urlEncode(k))=\(urlEncode(v))"
        }.joined(separator: "&")
    }

    private func urlEncode(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }

    // MARK: - PKCE helpers

    private static func randomVerifier(length: Int = 64) -> String {
        var bytes = [UInt8](repeating: 0, count: length)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return base64URL(bytes)
    }

    private static func codeChallenge(for verifier: String) -> String {
        let data = Data(verifier.utf8)
        let hash = sha256(data)
        return base64URL([UInt8](hash))
    }

    private static func sha256(_ data: Data) -> Data {
        // Use CommonCrypto via raw compute since CryptoKit pulls Foundation
        // compatibility we don't need. Falls back to CryptoKit when available.
        if #available(iOS 13.0, *) {
            return Data(_CryptoKitSHA256.hash(data))
        } else {
            return Data() // iOS 13+ deployment target — unreachable.
        }
    }

    private static func base64URL(_ bytes: [UInt8]) -> String {
        var s = Data(bytes).base64EncodedString()
        s = s.replacingOccurrences(of: "+", with: "-")
        s = s.replacingOccurrences(of: "/", with: "_")
        s = s.replacingOccurrences(of: "=", with: "")
        return s
    }
}

extension SplitOAuth: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        anchor ?? ASPresentationAnchor()
    }
}

// MARK: - SHA-256 shim
//
// CryptoKit lives at iOS 13+; we wrap to avoid bumping every call site to
// `import CryptoKit` (which conflicts with availability checks elsewhere).

import CryptoKit
private enum _CryptoKitSHA256 {
    static func hash(_ data: Data) -> [UInt8] {
        Array(SHA256.hash(data: data))
    }
}
