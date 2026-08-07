import Foundation
#if os(iOS)
import UIKit
import AuthenticationServices
import CryptoKit

/// Slack OAuth 2.0 — `oauth/v2/authorize` → `oauth.v2.access`.
/// Requests **user-token** scopes (not bot scopes) so messages post as the
/// signed-in user without inviting a bot to every channel.
///
/// Token persists in `SplitStore` keyed by `SlackKeys.accessToken`.
///
/// **Security note**: Slack's token endpoint accepts `client_secret` in the
/// form body. For dev / personal use that's fine; move to a Worker for
/// public release.
final class SlackAuth: NSObject {

    // MARK: - User-supplied configuration

    /// Pulled from .env via Scripts/load-env.sh.
    static var clientID: String { Secrets.slackOauthClientId }
    static var clientSecret: String { Secrets.slackOauthClientSecret }

    /// Custom URL scheme the OAuth flow finally lands on. `ASWebAuthenticationSession`
    /// can only catch a custom scheme (iOS 15), so this is what the session listens
    /// for. Must be registered as a URL scheme in the host app's Info.plist.
    static let redirectScheme = "turtleslackoauth"
    static let callbackURL = "\(redirectScheme)://oauth-callback"

    /// The `redirect_uri` actually sent to Slack. **Slack rejects custom schemes**
    /// (Redirect URLs must be HTTPS → "redirect_uri did not match any configured
    /// URIs"), so this is an HTTPS bounce page (a Worker route) that immediately
    /// redirects the auth code to `callbackURL`. This exact value must (a) be
    /// registered as a Redirect URL in the Slack app dashboard and (b) be sent
    /// unchanged at both authorize and token-exchange time. See OAUTH_SETUP_iOS.md.
    static var redirectURI: String { Secrets.slackOauthRedirectUri }

    /// Per-user scopes — `chat:write` to post; `channels:read` +
    /// `groups:read` so the channel picker can list rooms the user is in.
    static let userScopes = "chat:write,channels:read,groups:read"

    private static let authorizeURL = URL(string: "https://slack.com/oauth/v2/authorize")!
    private static let tokenURL = URL(string: "https://slack.com/api/oauth.v2.access")!

    enum AuthError: Error, LocalizedError {
        case notConfigured
        case userCancelled
        case missingCode
        case http(Int, String)
        case slack(String)

        var errorDescription: String? {
            switch self {
            case .notConfigured:    return "Slack connection is temporarily unavailable"
            case .userCancelled:    return "Sign-in cancelled"
            case .missingCode:      return "No auth code in redirect"
            case .http(let c, let m): return "HTTP \(c): \(m)"
            case .slack(let m):     return "Slack: \(m)"
            }
        }
    }

    private let store: SplitStore
    private weak var anchor: UIWindow?
    private var session: ASWebAuthenticationSession?
    /// PKCE verifier kept across the authorize → token-exchange round trip.
    /// Slack requires PKCE when the redirect uses a custom URL scheme.
    private var pendingVerifier: String?

    init(store: SplitStore, presentationAnchor: UIWindow?) {
        self.store = store
        self.anchor = presentationAnchor
    }

    var isConfigured: Bool { !Self.clientID.isEmpty && !Self.clientSecret.isEmpty }

    var isSignedIn: Bool {
        !store.string(forKey: SlackKeys.accessToken, fallback: "").isEmpty
    }

    var teamName: String? {
        let n = store.string(forKey: SlackKeys.teamName, fallback: "")
        return n.isEmpty ? nil : n
    }

    func signOut() {
        [SlackKeys.accessToken, SlackKeys.teamName, SlackKeys.teamDomain,
         SlackKeys.defaultChannel, SlackKeys.defaultChannelName].forEach {
            store.setString("", forKey: $0)
        }
    }

    func signIn(_ completion: @escaping (Result<String, Error>) -> Void) {
        guard isConfigured else { completion(.failure(AuthError.notConfigured)); return }

        // PKCE — required when Slack's redirect URL is a custom scheme.
        let verifier = Self.randomVerifier()
        let challenge = Self.codeChallenge(for: verifier)
        self.pendingVerifier = verifier

        var comps = URLComponents(url: Self.authorizeURL, resolvingAgainstBaseURL: false)!
        comps.queryItems = [
            URLQueryItem(name: "client_id", value: Self.clientID),
            URLQueryItem(name: "user_scope", value: Self.userScopes),
            URLQueryItem(name: "redirect_uri", value: Self.redirectURI),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
        ]
        guard let url = comps.url else {
            completion(.failure(AuthError.http(0, "bad URL"))); return
        }

        let session = ASWebAuthenticationSession(
            url: url, callbackURLScheme: Self.redirectScheme
        ) { [weak self] callback, error in
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
            guard let cb = callback,
                  let code = URLComponents(url: cb, resolvingAgainstBaseURL: false)?
                    .queryItems?.first(where: { $0.name == "code" })?.value
            else { completion(.failure(AuthError.missingCode)); return }
            self.exchangeCode(code: code, completion: completion)
        }
        session.presentationContextProvider = self
        DispatchQueue.main.async {
            self.session = session
            session.start()
        }
    }

    private func exchangeCode(
        code: String,
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        var params = [
            "code": code,
            "client_id": Self.clientID,
            "client_secret": Self.clientSecret,
            "redirect_uri": Self.redirectURI,
        ]
        // PKCE proof — paired with the challenge sent at authorize time.
        if let verifier = pendingVerifier {
            params["code_verifier"] = verifier
        }
        pendingVerifier = nil
        let body = params.map { "\(urlEncode($0.key))=\(urlEncode($0.value))" }
                          .joined(separator: "&")

        var req = URLRequest(url: Self.tokenURL)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.httpBody = Data(body.utf8)
        req.timeoutInterval = 15

        URLSession.shared.dataTask(with: req) { [store] data, resp, err in
            if let err = err { completion(.failure(err)); return }
            guard let http = resp as? HTTPURLResponse, let data = data else {
                completion(.failure(AuthError.http(0, "no response"))); return
            }
            guard (200..<300).contains(http.statusCode) else {
                let msg = String(data: data, encoding: .utf8) ?? ""
                completion(.failure(AuthError.http(http.statusCode, msg))); return
            }
            // Slack always returns 200 — success requires "ok": true.
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                completion(.failure(AuthError.slack("non-JSON response"))); return
            }
            if (json["ok"] as? Bool) != true {
                completion(.failure(AuthError.slack(
                    (json["error"] as? String) ?? "unknown"))); return
            }
            guard let authedUser = json["authed_user"] as? [String: Any],
                  let token = authedUser["access_token"] as? String, !token.isEmpty
            else { completion(.failure(AuthError.slack("no user access token"))); return }

            store.setString(token, forKey: SlackKeys.accessToken)
            if let team = json["team"] as? [String: Any],
               let name = team["name"] as? String {
                store.setString(name, forKey: SlackKeys.teamName)
            }
            store.setInt(1, forKey: SlackKeys.enabled)
            completion(.success(token))
        }.resume()
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
        return base64URL(Data(bytes))
    }

    private static func codeChallenge(for verifier: String) -> String {
        let hash = SHA256.hash(data: Data(verifier.utf8))
        return base64URL(Data(hash))
    }

    private static func base64URL(_ data: Data) -> String {
        var s = data.base64EncodedString()
        s = s.replacingOccurrences(of: "+", with: "-")
        s = s.replacingOccurrences(of: "/", with: "_")
        s = s.replacingOccurrences(of: "=", with: "")
        return s
    }
}

extension SlackAuth: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        anchor ?? ASPresentationAnchor()
    }
}
#endif
