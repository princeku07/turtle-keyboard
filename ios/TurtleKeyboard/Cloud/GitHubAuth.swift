import Foundation
#if os(iOS)
import UIKit
import AuthenticationServices

/// GitHub OAuth 2.0 — `login/oauth/authorize` → `login/oauth/access_token`.
/// The standard "Sign in with GitHub / Authorize" web flow, mirroring
/// `SlackAuth`. Grants the `repo` scope so the keyboard's `/github` command
/// can read PRIVATE repos too (public repos work unauthenticated already);
/// `read:user` lets us show the signed-in handle.
///
/// Token persists in `SplitStore` keyed by `GitHubKeys.accessToken`, in the
/// shared App Group — the keyboard extension reads it from there and never
/// runs OAuth itself.
///
/// **Security note**: GitHub does NOT support PKCE for OAuth Apps, so the
/// token exchange sends `client_secret` in the body. For dev / personal use
/// that's fine (same posture as `SlackAuth`); move the exchange to the
/// closed-source Worker for a public release so the secret never ships in
/// the app binary.
final class GitHubAuth: NSObject {

    // MARK: - Configuration (from .env via Scripts/load-env.sh)

    static var clientID: String { Secrets.githubOauthClientId }
    static var clientSecret: String { Secrets.githubOauthClientSecret }

    /// Custom URL scheme registered as the OAuth App's "Authorization
    /// callback URL". GitHub accepts custom schemes; iOS keyboards can only
    /// catch custom-scheme callbacks via `ASWebAuthenticationSession`.
    static let redirectScheme = "turtlegithuboauth"
    static var redirectURI: String { "\(redirectScheme)://oauth-callback" }

    /// `repo` → read private + public repos; `read:user` → resolve the
    /// signed-in login for the connect screen.
    static let scopes = "repo read:user"

    private static let authorizeURL = URL(string: "https://github.com/login/oauth/authorize")!
    private static let tokenURL     = URL(string: "https://github.com/login/oauth/access_token")!
    private static let userURL      = URL(string: "https://api.github.com/user")!

    enum AuthError: Error, LocalizedError {
        case notConfigured
        case userCancelled
        case missingCode
        case stateMismatch
        case http(Int, String)
        case github(String)

        var errorDescription: String? {
            switch self {
            case .notConfigured:      return "GitHub OAuth not configured — set GITHUB_OAUTH_CLIENT_ID / _SECRET in .env"
            case .userCancelled:      return "Sign-in cancelled"
            case .missingCode:        return "No auth code in redirect"
            case .stateMismatch:      return "State mismatch — possible CSRF, sign-in aborted"
            case .http(let c, let m): return "HTTP \(c): \(m)"
            case .github(let m):      return "GitHub: \(m)"
            }
        }
    }

    private let store: SplitStore
    private weak var anchor: UIWindow?
    private var session: ASWebAuthenticationSession?
    /// Random CSRF token echoed back in the redirect; verified before the
    /// code exchange.
    private var pendingState: String?

    init(store: SplitStore, presentationAnchor: UIWindow?) {
        self.store = store
        self.anchor = presentationAnchor
    }

    var isConfigured: Bool { !Self.clientID.isEmpty && !Self.clientSecret.isEmpty }

    var isSignedIn: Bool {
        !store.string(forKey: GitHubKeys.accessToken, fallback: "").isEmpty
    }

    var login: String? {
        let n = store.string(forKey: GitHubKeys.login, fallback: "")
        return n.isEmpty ? nil : n
    }

    func signOut() {
        [GitHubKeys.accessToken, GitHubKeys.login].forEach { store.setString("", forKey: $0) }
    }

    func signIn(_ completion: @escaping (Result<String, Error>) -> Void) {
        guard isConfigured else { completion(.failure(AuthError.notConfigured)); return }

        let state = Self.randomState()
        pendingState = state

        var comps = URLComponents(url: Self.authorizeURL, resolvingAgainstBaseURL: false)!
        comps.queryItems = [
            URLQueryItem(name: "client_id", value: Self.clientID),
            URLQueryItem(name: "redirect_uri", value: Self.redirectURI),
            URLQueryItem(name: "scope", value: Self.scopes),
            URLQueryItem(name: "state", value: state),
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
                  let items = URLComponents(url: cb, resolvingAgainstBaseURL: false)?.queryItems
            else { completion(.failure(AuthError.missingCode)); return }
            // Verify CSRF state before trusting the code.
            let returnedState = items.first(where: { $0.name == "state" })?.value
            guard returnedState == self.pendingState else {
                self.pendingState = nil
                completion(.failure(AuthError.stateMismatch)); return
            }
            self.pendingState = nil
            guard let code = items.first(where: { $0.name == "code" })?.value else {
                completion(.failure(AuthError.missingCode)); return
            }
            self.exchangeCode(code: code, completion: completion)
        }
        session.presentationContextProvider = self
        // GitHub keeps you logged in across sign-ins; an ephemeral session
        // would force a fresh password every time. Match Slack's default
        // (persistent) behaviour.
        DispatchQueue.main.async {
            self.session = session
            session.start()
        }
    }

    private func exchangeCode(
        code: String,
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        let params = [
            "client_id": Self.clientID,
            "client_secret": Self.clientSecret,
            "code": code,
            "redirect_uri": Self.redirectURI,
        ]
        let body = params.map { "\(urlEncode($0.key))=\(urlEncode($0.value))" }
                          .joined(separator: "&")

        var req = URLRequest(url: Self.tokenURL)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        // Ask for JSON — GitHub defaults to a form-encoded body otherwise.
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.httpBody = Data(body.utf8)
        req.timeoutInterval = 15

        URLSession.shared.dataTask(with: req) { [weak self] data, resp, err in
            if let err = err { completion(.failure(err)); return }
            guard let http = resp as? HTTPURLResponse, let data = data else {
                completion(.failure(AuthError.http(0, "no response"))); return
            }
            guard (200..<300).contains(http.statusCode) else {
                let msg = String(data: data, encoding: .utf8) ?? ""
                completion(.failure(AuthError.http(http.statusCode, msg))); return
            }
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                completion(.failure(AuthError.github("non-JSON response"))); return
            }
            if let ghError = json["error_description"] as? String ?? json["error"] as? String {
                completion(.failure(AuthError.github(ghError))); return
            }
            guard let token = json["access_token"] as? String, !token.isEmpty else {
                completion(.failure(AuthError.github("no access token"))); return
            }
            self?.store.setString(token, forKey: GitHubKeys.accessToken)
            self?.store.setInt(1, forKey: PersonalizationKeys.githubEnabled)
            // Resolve the handle for the connect screen; non-fatal if it fails.
            self?.fetchLogin(token: token) { _ in
                completion(.success(token))
            }
        }.resume()
    }

    /// `GET /user` → store `login` so the connect screen can show "Signed in
    /// as @handle". Best-effort; the token is already valid by this point.
    private func fetchLogin(token: String, done: @escaping (String?) -> Void) {
        var req = URLRequest(url: Self.userURL)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("TurtleKeyboard", forHTTPHeaderField: "User-Agent")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 10
        URLSession.shared.dataTask(with: req) { [weak self] data, _, _ in
            let login = data
                .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
                .flatMap { $0?["login"] as? String }
            if let login = login { self?.store.setString(login, forKey: GitHubKeys.login) }
            DispatchQueue.main.async { done(login) }
        }.resume()
    }

    // MARK: - Repos

    struct Repo { let fullName: String; let isPrivate: Bool }

    /// List the repos the signed-in user can access (owned, collaborator, and
    /// org member), most-recently-updated first. Returns [] when signed out.
    func fetchRepos(completion: @escaping (Result<[Repo], Error>) -> Void) {
        let token = store.string(forKey: GitHubKeys.accessToken, fallback: "")
        guard !token.isEmpty else { completion(.success([])); return }
        var comps = URLComponents(string: "https://api.github.com/user/repos")!
        comps.queryItems = [
            URLQueryItem(name: "per_page", value: "100"),
            URLQueryItem(name: "sort", value: "updated"),
            URLQueryItem(name: "affiliation", value: "owner,collaborator,organization_member"),
        ]
        var req = URLRequest(url: comps.url!)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("TurtleKeyboard", forHTTPHeaderField: "User-Agent")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 15
        URLSession.shared.dataTask(with: req) { data, resp, err in
            let finish: (Result<[Repo], Error>) -> Void = { r in
                DispatchQueue.main.async { completion(r) }
            }
            if let err = err { finish(.failure(err)); return }
            let status = (resp as? HTTPURLResponse)?.statusCode ?? -1
            guard (200..<300).contains(status), let data = data,
                  let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
                finish(.failure(AuthError.http(status, "couldn't load repos"))); return
            }
            let repos = arr.compactMap { obj -> Repo? in
                guard let name = obj["full_name"] as? String else { return nil }
                return Repo(fullName: name, isPrivate: (obj["private"] as? Bool) ?? false)
            }
            finish(.success(repos))
        }.resume()
    }

    private func urlEncode(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }

    private static func randomState(length: Int = 32) -> String {
        var bytes = [UInt8](repeating: 0, count: length)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

extension GitHubAuth: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        anchor ?? ASPresentationAnchor()
    }
}

// MARK: - GitHubKeys
//
// Namespaced SplitStore keys for the GitHub module. Defined here for the
// HOST target; the keyboard extension carries an identical copy in
// `GitHubIntegration.swift` (the two targets are separate modules, so the
// strings — not the type — are what must stay in sync).
enum GitHubKeys {
    static let accessToken = "github.access_token"
    static let login       = "github.login"
    static let pinnedRepos = "github.pinned_repos"   // newline-separated owner/repo
    static let recentRepos = "github.recent_repos"   // newline-separated, most-recent first
}
#endif
