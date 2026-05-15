import Foundation

/// Narrow interface SplitCloudSync needs from the auth layer. Decouples the
/// sync engine from `ASWebAuthenticationSession`-bound `SplitOAuth` so the
/// keyboard extension (which can't run web auth UI) can plug in a
/// refresh-only token provider that reads from the shared Keychain.
///
/// Two implementations:
///   • `SplitOAuth` (host app) — full sign-in/sign-out + refresh, owns UI.
///   • `SplitKeychainTokenProvider` (keyboard extension) — refresh-only,
///     reads tokens written by the host out of the shared Keychain group.
protocol SplitFreshTokenProvider: AnyObject {
    /// `true` when a refresh token is on file. No network call.
    var isSignedIn: Bool { get }

    /// Email of the account that signed in, if known.
    var accountEmail: String? { get }

    /// Returns a non-expired access token, refreshing via the stored refresh
    /// token if needed. On 400/401 from the token endpoint the host impl
    /// signs the user out; the extension impl just returns an error and
    /// lets the host clean up on its next launch.
    func freshAccessToken(_ completion: @escaping (Result<String, Error>) -> Void)
}
