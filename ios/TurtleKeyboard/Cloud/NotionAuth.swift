import Foundation
#if os(iOS)
import UIKit
import AuthenticationServices

/// Notion OAuth 2.0. Uses `ASWebAuthenticationSession` to push the user
/// through Notion's consent screen, then exchanges the returned `code` for
/// a long-lived bearer token via `POST /v1/oauth/token`.
///
/// Token persists in `SplitStore` keyed by `NotionKeys.accessToken`. Notion
/// tokens don't expire today, so no refresh flow is needed.
///
/// **Security note**: Notion's token endpoint requires `client_secret` in
/// the Basic auth header. For dev / personal use that's fine; before any
/// wider release move the exchange to a tiny token-exchange Worker.
final class NotionAuth: NSObject {

    // MARK: - User-supplied configuration

    /// Pulled from .env via Scripts/load-env.sh.
    static var clientID: String { Secrets.notionOauthClientId }
    static var clientSecret: String { Secrets.notionOauthClientSecret }

    /// Custom URL scheme the OAuth flow finally lands on. `ASWebAuthenticationSession`
    /// can only catch a custom scheme (iOS 15), so this is what the session listens
    /// for. Must be registered as a URL scheme in the host app's Info.plist.
    static let redirectScheme = "turtleknotionoauth"
    static let callbackURL = "\(redirectScheme)://oauth-callback"

    /// The `redirect_uri` actually sent to Notion. **Notion rejects custom
    /// schemes** ("Missing or invalid redirect_uri") and forces HTTPS, so this
    /// is an HTTPS bounce page (a Worker route) that immediately redirects the
    /// auth code to `callbackURL`. This exact value must (a) be registered as a
    /// Redirect URI in the Notion integration dashboard and (b) be sent
    /// unchanged at both authorize and token-exchange time. See OAUTH_SETUP_iOS.md.
    static var redirectURI: String { Secrets.notionOauthRedirectUri }

    private static let authorizeURL = URL(string: "https://api.notion.com/v1/oauth/authorize")!
    private static let tokenURL = URL(string: "https://api.notion.com/v1/oauth/token")!

    enum AuthError: Error, LocalizedError {
        case notConfigured
        case userCancelled
        case missingCode
        case http(Int, String)
        case decode(String)

        var errorDescription: String? {
            switch self {
            case .notConfigured:        return "Notion OAuth not configured — see OAUTH_SETUP_iOS.md"
            case .userCancelled:        return "Sign-in cancelled"
            case .missingCode:          return "No auth code in redirect"
            case .http(let c, let m):   return "HTTP \(c): \(m)"
            case .decode(let m):        return "Decode: \(m)"
            }
        }
    }

    private let store: SplitStore
    private weak var anchor: UIWindow?
    private var session: ASWebAuthenticationSession?

    init(store: SplitStore, presentationAnchor: UIWindow?) {
        self.store = store
        self.anchor = presentationAnchor
    }

    var isConfigured: Bool { !Self.clientID.isEmpty && !Self.clientSecret.isEmpty }

    var isSignedIn: Bool {
        !store.string(forKey: NotionKeys.accessToken, fallback: "").isEmpty
    }

    var workspaceName: String? {
        let n = store.string(forKey: NotionKeys.workspaceName, fallback: "")
        return n.isEmpty ? nil : n
    }

    func signOut() {
        store.setString("", forKey: NotionKeys.accessToken)
        store.setString("", forKey: NotionKeys.workspaceName)
        store.setString("", forKey: NotionKeys.defaultParent)
        store.setString("", forKey: NotionKeys.defaultParentT)
    }

    func signIn(_ completion: @escaping (Result<String, Error>) -> Void) {
        guard isConfigured else { completion(.failure(AuthError.notConfigured)); return }

        var comps = URLComponents(url: Self.authorizeURL, resolvingAgainstBaseURL: false)!
        comps.queryItems = [
            URLQueryItem(name: "client_id", value: Self.clientID),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "owner", value: "user"),
            URLQueryItem(name: "redirect_uri", value: Self.redirectURI),
        ]
        guard let url = comps.url else {
            completion(.failure(AuthError.decode("bad URL"))); return
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
        let creds = "\(Self.clientID):\(Self.clientSecret)"
        let basic = Data(creds.utf8).base64EncodedString()

        let body: [String: Any] = [
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": Self.redirectURI,
        ]
        var req = URLRequest(url: Self.tokenURL)
        req.httpMethod = "POST"
        req.setValue("Basic \(basic)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
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
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let token = json["access_token"] as? String, !token.isEmpty
            else { completion(.failure(AuthError.decode("no access_token"))); return }
            store.setString(token, forKey: NotionKeys.accessToken)
            if let workspace = json["workspace_name"] as? String {
                store.setString(workspace, forKey: NotionKeys.workspaceName)
            }
            store.setInt(1, forKey: NotionKeys.enabled)
            completion(.success(token))
        }.resume()
    }
}

extension NotionAuth: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        anchor ?? ASPresentationAnchor()
    }
}
#endif
