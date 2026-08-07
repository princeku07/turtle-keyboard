import Foundation

/// Thin REST wrapper for the two Notion endpoints the module uses:
/// - `POST /v1/search` — list top-level pages so the user can pick a parent
/// - `POST /v1/pages`  — create a child page under the chosen parent
///
/// Direct port of Android's `NotionClient`. Token is the caller's problem.
enum NotionClient {

    private static let apiVersion = "2022-06-28"
    private static let base = "https://api.notion.com"
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

    enum NotionError: Error, LocalizedError {
        case http(Int, String)
        case decode(String)

        var errorDescription: String? {
            switch self {
            case .http(let c, let m): return "HTTP \(c): \(m)"
            case .decode(let m):      return "Decode: \(m)"
            }
        }
    }

    /// Fetches every page the integration has been granted access to.
    static func searchPages(accessToken: String) async throws -> [NotionPage] {
        let body: [String: Any] = [
            "filter": ["value": "page", "property": "object"],
        ]
        let resp = try await post(path: "/v1/search", accessToken: accessToken, body: body)
        guard let arr = resp["results"] as? [[String: Any]] else { return [] }
        var out: [NotionPage] = []
        out.reserveCapacity(arr.count)
        for p in arr {
            guard let id = p["id"] as? String, !id.isEmpty else { continue }
            let title = extractTitle(page: p) ?? ""
            out.append(NotionPage(id: id, title: title.isEmpty ? "(untitled)" : title))
        }
        return out
    }

    /// Creates a child page under `parentPageId` titled `title`, with the
    /// given block array as children. `blocks` is the same shape the LLM
    /// bridge produces.
    static func createPage(
        accessToken: String,
        parentPageId: String,
        title: String,
        blocks: [[String: Any]]
    ) async throws -> (pageId: String, pageURL: String?) {
        let body: [String: Any] = [
            "parent": ["page_id": parentPageId],
            "properties": [
                "title": [richText(title.isEmpty ? "Untitled" : title)],
            ],
            "children": blocks,
        ]
        let resp = try await post(path: "/v1/pages", accessToken: accessToken, body: body)
        guard let id = resp["id"] as? String, !id.isEmpty else {
            throw NotionError.decode("no page id")
        }
        let url = resp["url"] as? String
        return (id, url)
    }

    // MARK: - Block helpers

    /// Build a Notion block JSON from a (type, text, checked) triple.
    /// `type` is one of "heading_2", "paragraph", "to_do".
    static func buildBlock(type: String, text: String, checked: Bool) -> [String: Any] {
        var inner: [String: Any] = [
            "rich_text": [richText(text)],
        ]
        if type == "to_do" {
            inner["checked"] = checked
        }
        return [
            "object": "block",
            "type": type,
            type: inner,
        ]
    }

    /// Build a Notion `rich_text` object wrapping plain text.
    static func richText(_ text: String) -> [String: Any] {
        [
            "type": "text",
            "text": ["content": text],
        ]
    }

    // MARK: - Internals

    private static func post(
        path: String,
        accessToken: String,
        body: [String: Any]
    ) async throws -> [String: Any] {
        guard let url = URL(string: base + path) else {
            throw NotionError.decode("bad URL")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        req.setValue(apiVersion, forHTTPHeaderField: "Notion-Version")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        req.timeoutInterval = 20

        let (data, resp) = try await session.data(for: req)
        guard data.count <= maxResponseBytes else { throw NotionError.decode("response too large") }
        guard let http = resp as? HTTPURLResponse else {
            throw NotionError.http(0, "no response")
        }
        guard (200..<300).contains(http.statusCode) else {
            let msg = String(data: data, encoding: .utf8) ?? ""
            throw NotionError.http(http.statusCode, msg)
        }
        guard !data.isEmpty,
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return [:] }
        return json
    }

    private static func extractTitle(page: [String: Any]) -> String? {
        guard let props = page["properties"] as? [String: Any] else { return nil }
        for (_, value) in props {
            guard let p = value as? [String: Any],
                  let type = p["type"] as? String, type == "title",
                  let rich = p["title"] as? [[String: Any]]
            else { continue }
            return rich.compactMap { $0["plain_text"] as? String }.joined()
        }
        return nil
    }
}
