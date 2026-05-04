import Foundation

/// Minimal Drive v3 client — the `permissions` subset needed to share the
/// owner's "Turtle Splits" sheet via an anyone-with-link writer permission,
/// and revoke it later. Mirrors Android's `SplitDriveClient`.
enum SplitDriveClient {

    private static let base = "https://www.googleapis.com/drive/v3/files/"

    /// Adds an anyone-with-link writer permission. Returns the new
    /// permissionId so the caller can revoke it later. Does not send
    /// notification emails.
    static func grantAnyoneWriter(
        accessToken: String,
        fileId: String
    ) async throws -> String {
        let url = "\(base)\(fileId)/permissions?sendNotificationEmail=false"
        let body: [String: Any] = [
            "role": "writer",
            "type": "anyone",
            "allowFileDiscovery": false,
        ]
        let resp = try await request("POST", url: url, accessToken: accessToken,
                                     body: body, ignore404: false)
        guard let id = resp["id"] as? String, !id.isEmpty else {
            throw SplitSheetsClient.SheetsError.decode("no permissionId")
        }
        return id
    }

    /// Removes a specific permission by ID. Idempotent — 404s are swallowed.
    static func revokePermission(
        accessToken: String,
        fileId: String,
        permissionId: String
    ) async throws {
        let url = "\(base)\(fileId)/permissions/\(encode(permissionId))"
        _ = try await request("DELETE", url: url, accessToken: accessToken,
                              body: nil, ignore404: true)
    }

    // MARK: - Internals

    private static func request(
        _ method: String,
        url: String,
        accessToken: String,
        body: Any?,
        ignore404: Bool
    ) async throws -> [String: Any] {
        guard let u = URL(string: url) else {
            throw SplitSheetsClient.SheetsError.decode("bad URL")
        }
        var req = URLRequest(url: u)
        req.httpMethod = method
        req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        if let body = body {
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else {
            throw SplitSheetsClient.SheetsError.http(0, "no response")
        }
        if http.statusCode == 401 || http.statusCode == 403 {
            let msg = String(data: data, encoding: .utf8) ?? ""
            throw SplitSheetsClient.SheetsError.unauthorized("HTTP \(http.statusCode): \(msg)")
        }
        if http.statusCode == 404 && ignore404 { return [:] }
        guard (200..<300).contains(http.statusCode) else {
            let msg = String(data: data, encoding: .utf8) ?? ""
            throw SplitSheetsClient.SheetsError.http(http.statusCode, msg)
        }
        guard !data.isEmpty else { return [:] }
        let json = try JSONSerialization.jsonObject(with: data)
        return (json as? [String: Any]) ?? [:]
    }

    private static func encode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? s
    }
}
