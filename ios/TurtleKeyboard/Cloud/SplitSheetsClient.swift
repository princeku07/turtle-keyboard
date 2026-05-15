import Foundation

/// Thin REST client for the Sheets v4 surface the SDK needs. Mirrors
/// Android's `SplitSheetsClient` schema (tab name, header columns, row
/// shape) so a sheet created on one platform reads identically on the other.
///
/// Auth is the caller's problem — pass a fresh access token to every call.
/// 401/403 surfaces as `unauthorized` so `SplitCloudSync` can re-auth.
enum SplitSheetsClient {

    static let tab = "Splits"
    private static let headers = [
        "timestampIso", "timestampMs", "deviceId", "amount", "people", "perPerson",
    ]
    private static let readRange = "\(tab)!A2:F"
    private static let base = "https://sheets.googleapis.com/v4/spreadsheets"

    enum SheetsError: Error, LocalizedError {
        case unauthorized(String)
        case http(Int, String)
        case decode(String)

        var errorDescription: String? {
            switch self {
            case .unauthorized(let m): return "Auth: \(m)"
            case .http(let c, let m):  return "HTTP \(c): \(m)"
            case .decode(let m):       return "Decode: \(m)"
            }
        }
    }

    struct Row {
        let timestampMs: Int64
        let deviceId: String
        let amount: Double
        let people: Int
    }

    // MARK: - Public

    /// Creates a new spreadsheet titled "Turtle Splits" with a Splits tab
    /// and header row. Returns the new spreadsheet ID.
    static func createSpreadsheet(accessToken: String) async throws -> String {
        let body: [String: Any] = [
            "properties": ["title": "Turtle Splits"],
            "sheets": [["properties": ["title": tab]]],
        ]
        let resp = try await request("POST", url: base, accessToken: accessToken, body: body)
        guard let id = resp["spreadsheetId"] as? String, !id.isEmpty else {
            throw SheetsError.decode("no spreadsheetId")
        }
        // Stamp headers in row 1.
        try await appendRows(accessToken: accessToken,
                             spreadsheetId: id,
                             rows: [headers.map { $0 as Any }])
        return id
    }

    /// Appends one or more rows to the Splits tab.
    static func appendRows(
        accessToken: String,
        spreadsheetId: String,
        rows: [[Any]]
    ) async throws {
        guard !rows.isEmpty else { return }
        let url = "\(base)/\(spreadsheetId)/values/\(encode(tab + "!A1")):append"
            + "?valueInputOption=RAW&insertDataOption=INSERT_ROWS"
        let body: [String: Any] = ["values": rows]
        _ = try await request("POST", url: url, accessToken: accessToken, body: body)
    }

    /// Reads every data row from the Splits tab.
    static func listRows(accessToken: String, spreadsheetId: String) async throws -> [Row] {
        let url = "\(base)/\(spreadsheetId)/values/\(encode(readRange))"
        let resp = try await request("GET", url: url, accessToken: accessToken, body: nil)
        guard let values = resp["values"] as? [[Any]] else { return [] }
        var out: [Row] = []
        out.reserveCapacity(values.count)
        for r in values {
            guard r.count >= 5 else { continue }
            let ts: Int64 = parseLong(r[1])
            let dev = stringOf(r[2])
            let amt = parseDouble(r[3])
            let people = Int(parseDouble(r[4]))
            out.append(Row(timestampMs: ts, deviceId: dev, amount: amt, people: people))
        }
        return out
    }

    /// Owner-only: nukes every data row from the Splits tab while
    /// preserving the header.
    static func deleteAllDataRows(accessToken: String, spreadsheetId: String) async throws {
        let url = "\(base)/\(spreadsheetId)/values/\(encode(tab + "!A2:F")):clear"
        _ = try await request("POST", url: url, accessToken: accessToken, body: [:])
    }

    /// Deletes all data rows whose deviceId (column C) matches `deviceId`.
    /// Iterates row indices from the bottom up so deletions don't shift
    /// indexes mid-loop.
    static func deleteRowsForDevice(
        accessToken: String,
        spreadsheetId: String,
        deviceId: String
    ) async throws {
        let sheetId = try await resolveSheetId(accessToken: accessToken,
                                               spreadsheetId: spreadsheetId,
                                               tabName: tab)
        let listURL = "\(base)/\(spreadsheetId)/values/\(encode(readRange))"
        let resp = try await request("GET", url: listURL, accessToken: accessToken, body: nil)
        guard let values = resp["values"] as? [[Any]], !values.isEmpty else { return }

        var matches: [Int] = []
        for (i, r) in values.enumerated() {
            guard r.count >= 3 else { continue }
            if stringOf(r[2]) == deviceId {
                matches.append(i + 1) // +1 = absolute row index (header row is at 0)
            }
        }
        guard !matches.isEmpty else { return }
        matches.sort(by: >)

        let requests: [[String: Any]] = matches.map { idx in
            [
                "deleteDimension": [
                    "range": [
                        "sheetId": sheetId,
                        "dimension": "ROWS",
                        "startIndex": idx,
                        "endIndex": idx + 1,
                    ],
                ],
            ]
        }
        let body: [String: Any] = ["requests": requests]
        _ = try await request("POST",
                              url: "\(base)/\(spreadsheetId):batchUpdate",
                              accessToken: accessToken,
                              body: body)
    }

    // MARK: - Internals

    private static func resolveSheetId(
        accessToken: String,
        spreadsheetId: String,
        tabName: String
    ) async throws -> Int {
        let url = "\(base)/\(spreadsheetId)?fields=sheets.properties"
        let resp = try await request("GET", url: url, accessToken: accessToken, body: nil)
        guard let sheets = resp["sheets"] as? [[String: Any]] else {
            throw SheetsError.decode("no sheets in response")
        }
        for s in sheets {
            if let props = s["properties"] as? [String: Any],
               let title = props["title"] as? String, title == tabName,
               let id = props["sheetId"] as? Int {
                return id
            }
        }
        throw SheetsError.decode("tab '\(tabName)' not found")
    }

    private static func request(
        _ method: String,
        url: String,
        accessToken: String,
        body: Any?
    ) async throws -> [String: Any] {
        guard let u = URL(string: url) else {
            throw SheetsError.decode("bad URL")
        }
        var req = URLRequest(url: u)
        req.httpMethod = method
        req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body = body {
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else {
            throw SheetsError.http(0, "no response")
        }
        if http.statusCode == 401 || http.statusCode == 403 {
            let msg = String(data: data, encoding: .utf8) ?? ""
            throw SheetsError.unauthorized("HTTP \(http.statusCode): \(msg)")
        }
        guard (200..<300).contains(http.statusCode) else {
            let msg = String(data: data, encoding: .utf8) ?? ""
            throw SheetsError.http(http.statusCode, msg)
        }
        guard !data.isEmpty else { return [:] }
        let json = try JSONSerialization.jsonObject(with: data)
        return (json as? [String: Any]) ?? [:]
    }

    // MARK: - Cell helpers

    private static func encode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? s
    }
    private static func stringOf(_ v: Any) -> String {
        if let s = v as? String { return s }
        return String(describing: v)
    }
    private static func parseLong(_ v: Any) -> Int64 {
        if let n = v as? NSNumber { return n.int64Value }
        if let s = v as? String, let n = Int64(s) { return n }
        if let s = v as? String, let d = Double(s) { return Int64(d) }
        return 0
    }
    private static func parseDouble(_ v: Any) -> Double {
        if let n = v as? NSNumber { return n.doubleValue }
        if let s = v as? String, let d = Double(s) { return d }
        return 0
    }
}
