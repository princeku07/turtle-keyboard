import Foundation

/// OAuth configuration shared between the host's full `SplitOAuth` (with
/// PKCE sign-in via `ASWebAuthenticationSession`) and the keyboard
/// extension's refresh-only `SplitKeychainTokenProvider`. Pulled out into
/// this Foundation-only file so the extension target can compile it
/// without dragging UIKit / AuthenticationServices in.
enum SplitOAuthConstants {

    /// Prefer the iOS OAuth client when set in .env. Falls back to the web
    /// client only as a last resort — Google Web clients reject custom-scheme
    /// redirects, so without the iOS client this flow can't complete on iOS.
    static var clientID: String {
        let ios = Secrets.splitOauthIosClientId
        return ios.isEmpty ? Secrets.splitOauthWebClientId : ios
    }

    static let tokenEndpoint = URL(string: "https://oauth2.googleapis.com/token")!

    /// Buffer subtracted from token expiry so we refresh before it dies.
    static let refreshSkewSeconds: TimeInterval = 60

    enum AuthError: Error, LocalizedError {
        case notConfigured
        case userCancelled
        case missingCode
        case http(Int, String)
        case noRefreshToken
        case decode(String)

        var errorDescription: String? {
            switch self {
            case .notConfigured:        return "OAuth client ID not set — see OAUTH_SETUP_iOS.md"
            case .userCancelled:        return "Sign-in cancelled"
            case .missingCode:          return "No auth code in redirect"
            case .http(let code, let m):return "HTTP \(code): \(m)"
            case .noRefreshToken:       return "No refresh token stored — sign in again"
            case .decode(let m):        return "Decode error: \(m)"
            }
        }
    }
}
