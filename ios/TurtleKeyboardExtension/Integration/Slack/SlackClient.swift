import Foundation

/// Thin REST wrapper for the Slack endpoints the module uses:
/// - `GET users.conversations` — list channels for the picker
/// - `GET team.info`           — workspace domain for permalinks
/// - `POST chat.postMessage`   — send the message
/// - `GET chat.getPermalink`   — deep-link the result
///
/// Slack's API always returns HTTP 200 — success requires `"ok": true` in
/// the JSON body.
enum SlackClient {

    private static let base = "https://slack.com/api"
    private static let maxResponseBytes = 2_097_152
    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 40
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.waitsForConnectivity = false
        config.httpMaximumConnectionsPerHost = 2
        return URLSession(configuration: config)
    }()

    static func cancelAllRequests() { session.getAllTasks { $0.forEach { $0.cancel() } } }

    enum SlackError: Error, LocalizedError {
        case http(Int, String)
        case api(String)

        var errorDescription: String? {
            switch self {
            case .http(let c, let m): return "HTTP \(c): \(m)"
            case .api(let m):         return m
            }
        }
    }

    // MARK: - team.info

    static func teamInfo(accessToken: String) async throws -> (id: String, domain: String) {
        let json = try await get(path: "/team.info", accessToken: accessToken)
        guard let team = json["team"] as? [String: Any] else {
            throw SlackError.api("no_team")
        }
        let id = (team["id"] as? String) ?? ""
        let domain = (team["domain"] as? String) ?? ""
        return (id, domain)
    }

    // MARK: - channel listing

    /// Lists every channel the authenticated user is a member of, paging
    /// through `response_metadata.next_cursor` until exhausted.
    static func listChannels(accessToken: String) async throws -> [SlackChannel] {
        var out: [SlackChannel] = []
        var cursor = ""
        var pages = 0
        let maxPages = 10
        while pages < maxPages {
            pages += 1
            var path = "/users.conversations"
                + "?types=public_channel,private_channel"
                + "&exclude_archived=true&limit=200"
            if !cursor.isEmpty {
                path += "&cursor=\(urlEncode(cursor))"
            }
            let json = try await get(path: path, accessToken: accessToken)
            if let arr = json["channels"] as? [[String: Any]] {
                for c in arr {
                    guard let id = c["id"] as? String, !id.isEmpty,
                          let name = c["name"] as? String
                    else { continue }
                    let isPrivate = (c["is_private"] as? Bool) ?? false
                    out.append(SlackChannel(id: id, name: name, isPrivate: isPrivate))
                }
            }
            cursor = (json["response_metadata"] as? [String: Any])?["next_cursor"] as? String ?? ""
            if cursor.isEmpty { break }
        }
        return out
    }

    // MARK: - posting

    static func postMessage(
        accessToken: String,
        channelId: String,
        text: String
    ) async throws -> (channelId: String, ts: String, permalink: String?) {
        let body: [String: Any] = ["channel": channelId, "text": text]
        let json = try await postJson(path: "/chat.postMessage",
                                       accessToken: accessToken,
                                       body: body)
        let ts = (json["ts"] as? String) ?? ""
        let channel = (json["channel"] as? String) ?? channelId
        let link = try? await fetchPermalink(accessToken: accessToken,
                                              channelId: channel,
                                              ts: ts)
        return (channel, ts, link)
    }

    private static func fetchPermalink(
        accessToken: String,
        channelId: String,
        ts: String
    ) async throws -> String? {
        let path = "/chat.getPermalink?channel=\(urlEncode(channelId))&message_ts=\(urlEncode(ts))"
        let json = try await get(path: path, accessToken: accessToken)
        return json["permalink"] as? String
    }

    // MARK: - HTTP

    private static func get(path: String, accessToken: String) async throws -> [String: Any] {
        guard let url = URL(string: base + path) else {
            throw SlackError.http(0, "bad URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 15
        return try await execute(req)
    }

    private static func postJson(
        path: String,
        accessToken: String,
        body: [String: Any]
    ) async throws -> [String: Any] {
        guard let url = URL(string: base + path) else {
            throw SlackError.http(0, "bad URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        req.timeoutInterval = 20
        return try await execute(req)
    }

    private static func execute(_ req: URLRequest) async throws -> [String: Any] {
        let (data, resp) = try await session.data(for: req)
        guard data.count <= maxResponseBytes else { throw SlackError.api("response_too_large") }
        guard let http = resp as? HTTPURLResponse else {
            throw SlackError.http(0, "no response")
        }
        guard (200..<300).contains(http.statusCode) else {
            let msg = String(data: data, encoding: .utf8) ?? ""
            throw SlackError.http(http.statusCode, msg)
        }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        // Slack returns 200 for errors; success requires ok=true.
        if (json["ok"] as? Bool) != true {
            let err = (json["error"] as? String) ?? "unknown"
            throw SlackError.api(err)
        }
        return json
    }

    private static func urlEncode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? s
    }
}
