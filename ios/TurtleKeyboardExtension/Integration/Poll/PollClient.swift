import Foundation

/// HTTP client for the `turtle-worker` `/poll` endpoints. iOS counterpart
/// to Android's `PollClient`. Async/throwing rather than blocking + IOException.
enum PollClient {

    struct CreateResult {
        let id: String
        let url: String
    }

    struct Option {
        let label: String
        let votes: Int
    }

    struct Poll {
        let id: String
        let question: String
        let options: [Option]
        let createdAt: Int64
    }

    enum ClientError: Error, LocalizedError {
        case malformedResponse
        case workerError(status: Int, code: String)
        case transport(String)

        var errorDescription: String? {
            switch self {
            case .malformedResponse: return "malformed worker response"
            case .workerError(let s, let c): return "worker \(s)\(c.isEmpty ? "" : ": \(c)")"
            case .transport(let m): return m
            }
        }
    }

    /// `POST /poll` — returns the artifact id and shareable App Link URL.
    static func createPoll(question: String, options: [String]) async throws -> CreateResult {
        let body: [String: Any] = ["question": question, "options": options]
        let resp = try await postJson(path: "/poll", body: body, deviceId: nil)
        guard let id = resp["id"] as? String, let url = resp["url"] as? String else {
            throw ClientError.malformedResponse
        }
        return CreateResult(id: id, url: url)
    }

    /// `GET /poll/<id>` — fetches public poll shape.
    static func readPoll(id: String) async throws -> Poll {
        let resp = try await getJson(path: "/poll/\(id)", deviceId: nil)
        guard let pid = resp["id"] as? String,
              let question = resp["question"] as? String,
              let optsRaw = resp["options"] as? [[String: Any]] else {
            throw ClientError.malformedResponse
        }
        let createdAt = (resp["createdAt"] as? NSNumber)?.int64Value ?? 0
        let options: [Option] = optsRaw.compactMap { o in
            guard let label = o["label"] as? String else { return nil }
            let votes = (o["votes"] as? NSNumber)?.intValue ?? 0
            return Option(label: label, votes: votes)
        }
        return Poll(id: pid, question: question, options: options, createdAt: createdAt)
    }

    /// `POST /poll/<id>/vote` — worker dedups on `X-Turtle-Device`.
    static func vote(pollId: String, optionIndex: Int, deviceId: String) async throws {
        _ = try await postJson(path: "/poll/\(pollId)/vote",
                                body: ["optionIndex": optionIndex],
                                deviceId: deviceId)
    }

    // MARK: - HTTP helpers

    private static func postJson(path: String,
                                  body: [String: Any],
                                  deviceId: String?) async throws -> [String: Any] {
        guard let url = URL(string: WorkerUrls.workerBaseURL + path) else {
            throw ClientError.transport("bad URL")
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
        let (data, resp) = try await URLSession.shared.data(for: req)
        return try parse(data: data, response: resp)
    }

    private static func getJson(path: String, deviceId: String?) async throws -> [String: Any] {
        guard let url = URL(string: WorkerUrls.workerBaseURL + path) else {
            throw ClientError.transport("bad URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let d = deviceId, !d.isEmpty {
            req.setValue(d, forHTTPHeaderField: "X-Turtle-Device")
        }
        req.timeoutInterval = 15
        let (data, resp) = try await URLSession.shared.data(for: req)
        return try parse(data: data, response: resp)
    }

    private static func parse(data: Data, response: URLResponse) throws -> [String: Any] {
        let code = (response as? HTTPURLResponse)?.statusCode ?? -1
        if !(200..<300).contains(code) {
            var workerCode = ""
            if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let err = obj["error"] as? String {
                workerCode = err
            }
            throw ClientError.workerError(status: code, code: workerCode)
        }
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ClientError.malformedResponse
        }
        return obj
    }
}
