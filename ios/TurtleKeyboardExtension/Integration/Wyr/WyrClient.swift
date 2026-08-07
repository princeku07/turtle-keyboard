import Foundation

/// HTTP client for `turtle-worker` `/wyr` endpoints. Mirrors Android's
/// `WyrClient`. Same shape as `PollClient` — kept separate so each
/// artifact type owns its decoding.
enum WyrClient {

    private static let maxResponseBytes = 1_048_576
    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 30
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.waitsForConnectivity = false
        config.httpMaximumConnectionsPerHost = 2
        return URLSession(configuration: config)
    }()

    static func cancelAllRequests() { session.getAllTasks { $0.forEach { $0.cancel() } } }

    struct Question {
        let a: String
        let b: String
    }

    struct CreateResult {
        let id: String
        let url: String
    }

    /// `POST /wyr` — returns artifact id + shareable App Link URL.
    static func create(questions: [Question]) async throws -> CreateResult {
        let qs: [[String: String]] = questions.map { ["a": $0.a, "b": $0.b] }
        let body: [String: Any] = ["questions": qs]
        let resp = try await postJson(path: "/wyr", body: body, deviceId: nil)
        guard let id = resp["id"] as? String, let url = resp["url"] as? String else {
            throw PollClient.ClientError.malformedResponse
        }
        return CreateResult(id: id, url: url)
    }

    // MARK: - HTTP helpers (mirror PollClient — kept private to this enum)

    private static func postJson(path: String,
                                  body: [String: Any],
                                  deviceId: String?) async throws -> [String: Any] {
        guard let url = URL(string: WorkerUrls.workerBaseURL + path) else {
            throw PollClient.ClientError.transport("bad URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let d = deviceId, !d.isEmpty {
            req.setValue(d, forHTTPHeaderField: "X-Turtle-Device")
        }
        req.timeoutInterval = 15
        req.httpBody = try JSONSerialization.data(withJSONObject: body, options: [])
        let (data, resp) = try await session.data(for: req)
        guard data.count <= maxResponseBytes else {
            throw PollClient.ClientError.malformedResponse
        }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
        if !(200..<300).contains(code) {
            var workerCode = ""
            if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let err = obj["error"] as? String {
                workerCode = err
            }
            throw PollClient.ClientError.workerError(status: code, code: workerCode)
        }
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw PollClient.ClientError.malformedResponse
        }
        return obj
    }
}
