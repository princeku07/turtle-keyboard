import Foundation

// MARK: - APIClient
//
// Setup checklist:
//   1. Replace baseURL with your backend URL.
//   2. In Xcode → Signing & Capabilities, add "App Groups" to BOTH targets
//      (e.g. "group.com.turtlekeyboard") — required for RequestsOpenAccess.
//   3. Replace UserDefaults.standard with:
//        UserDefaults(suiteName: "group.com.turtlekeyboard") ?? .standard
//
final class APIClient {
    static let shared = APIClient()
    private init() {}

    private let baseURL = URL(string: "https://api.turtlekeyboard.com")!

    private let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest  = 10
        cfg.timeoutIntervalForResource = 30
        return URLSession(configuration: cfg)
    }()

    // TODO: migrate to shared App Group defaults once App Groups are wired
    private var defaults: UserDefaults { .standard }

    private var authToken: String? {
        get { defaults.string(forKey: "turtle_auth_token") }
        set { defaults.set(newValue, forKey: "turtle_auth_token") }
    }

    private var deviceId: String {
        if let id = defaults.string(forKey: "turtle_device_id") { return id }
        let id = UUID().uuidString
        defaults.set(id, forKey: "turtle_device_id")
        return id
    }

    // MARK: - Auth

    func ensureAuth() async throws -> String {
        if let token = authToken { return token }
        let body = AnonymousAuthRequest(deviceId: deviceId)
        let response: AuthResponse = try await post(path: "/v1/auth/anonymous", body: body, token: nil)
        authToken = response.token
        return response.token
    }

    // MARK: - Commands

    /// Execute a slash command. Retries once on 401 (token expiry).
    func executeCommand(
        command: String,
        prompt: String,
        context: String,
        locale: String = Locale.current.identifier
    ) async throws -> CommandResponse {
        let token = try await ensureAuth()
        let body = CommandRequest(command: command, prompt: prompt, context: context, locale: locale)
        do {
            return try await post(path: "/v1/command", body: body, token: token)
        } catch APIError.unauthorized {
            authToken = nil
            let fresh = try await ensureAuth()
            return try await post(path: "/v1/command", body: body, token: fresh)
        }
    }

    // MARK: - Image download

    func downloadImageData(from urlString: String) async throws -> Data {
        guard let url = URL(string: urlString) else { throw APIError.server(0) }
        do {
            let (data, _) = try await session.data(from: url)
            return data
        } catch let e as APIError { throw e }
        catch let e as URLError { throw APIError.network(e) }
        catch { throw APIError.unknown(error) }
    }

    // MARK: - Generic POST

    private func post<Body: Encodable, Response: Decodable>(
        path: String,
        body: Body,
        token: String?
    ) async throws -> Response {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONEncoder().encode(body)

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw APIError.server(0) }
            switch http.statusCode {
            case 200..<300: return try JSONDecoder().decode(Response.self, from: data)
            case 401:       throw APIError.unauthorized
            case 429:       throw APIError.rateLimit
            default:        throw APIError.server(http.statusCode)
            }
        } catch let e as APIError { throw e }
        catch let e as URLError { throw APIError.network(e) }
        catch { throw APIError.unknown(error) }
    }

}
