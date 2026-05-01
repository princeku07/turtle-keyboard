import Foundation

// MARK: - FalProvider
//
// Image generation via the Flam/Spark FAL gateway.
// Implementation follows the official spec from the backend team.
//
// Submit:
//   POST {baseURL}/api/spark/fal/generate
//   X-API-KEY: <key>
//   { "model_id": "fal-ai/flux-2", "input": { "prompt": "..." } }
//
// Response is either:
//   sync  → { "images": [{ "url": "..." }] }
//   async → { "request_id", "status_url", "response_url", "cancel_url" }
//
// For async, poll status_url with X-API-KEY every 3 s:
//   { "status": "processing" }     → keep polling
//   { "status": "failed", error }  → fail
//   { "images": [{ "url": "..." }] } → done

final class FalProvider: AIProvider {
    let id: ProviderID = .fal

    private let baseURL    = ""
    private let builtInKey = ""

    private let pollIntervalNS: UInt64 = 3_000_000_000   // 3 s, per spec
    private let pollTimeout:    TimeInterval = 60        // shorter than spec's 5 min — keyboard UX

    private let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest  = 30
        cfg.timeoutIntervalForResource = 90
        return URLSession(configuration: cfg)
    }()

    private func log(_ msg: String) { NSLog("🐢[Fal] %@", msg) }

    func execute(_ payload: CommandPayload) async throws -> CommandResult {
        switch payload.command {
        case "cap":
            return try await generateImage(prompt: payload.prompt, modelID: payload.model.id)
        default:
            throw ProviderError.unsupportedCommand(payload.command)
        }
    }

    // MARK: - Image generation

    private func generateImage(prompt: String, modelID: String) async throws -> CommandResult {
        let key = KeyStore.shared[.fal] ?? builtInKey

        guard let endpoint = URL(string: "\(baseURL)/api/spark/fal/generate") else {
            throw ProviderError.badResponse("Invalid gateway URL")
        }

        let body: [String: Any] = [
            "model_id": modelID,
            "input": [
                "prompt": prompt,
                "aspect_ratio": "1:1"
            ]
        ]

        log("POST \(endpoint.absoluteString)  model=\(modelID)  prompt=\"\(prompt)\"")
        let initial = try await postJSON(url: endpoint, key: key, body: body)
        log("initial response: \(prettyPrint(initial))")

        // Sync: image inline
        if let url = parseImageURL(initial) {
            log("✓ sync image: \(url)")
            return .image(url)
        }

        // Async: discover a working polling endpoint, then poll it.
        let requestID = initial["request_id"] as? String ?? ""
        let statusURLString = initial["status_url"] as? String ?? ""

        log("queued — request_id=\(requestID)")
        log("status_url (raw, fal.ai): \(statusURLString)")

        let pollEndpoint = try await discoverPollEndpoint(requestID: requestID,
                                                          statusURLString: statusURLString,
                                                          key: key)
        return .image(try await pollStatus(at: pollEndpoint.url,
                                           authHeader: pollEndpoint.authHeader,
                                           key: key))
    }

    // MARK: - Discovery
    //
    // The gateway might or might not expose a polling endpoint, and queue.fal.run
    // rejects our key. Try every plausible endpoint+auth combination once and use
    // whichever responds with HTTP 200 (or any non-auth, non-not-found code that
    // implies "endpoint exists, job in progress").

    private struct PollEndpoint {
        let url: URL
        let authHeader: (name: String, value: String)
    }

    private func discoverPollEndpoint(requestID: String,
                                      statusURLString: String,
                                      key: String) async throws -> PollEndpoint {
        var candidates: [PollEndpoint] = []

        // 1. Gateway-hosted candidates (most likely to actually work)
        let gatewayPaths = [
            "/api/spark/fal/status/\(requestID)",
            "/api/spark/fal/result/\(requestID)",
            "/api/spark/fal/\(requestID)",
            "/api/spark/fal/requests/\(requestID)",
            "/api/spark/fal/requests/\(requestID)/status",
        ]
        for path in gatewayPaths {
            if let u = URL(string: "\(baseURL)\(path)") {
                candidates.append(PollEndpoint(url: u, authHeader: ("X-API-KEY", key)))
            }
        }

        // 2. fal.ai direct (status_url) with various auth headers
        if let statusURL = URL(string: statusURLString) {
            candidates.append(PollEndpoint(url: statusURL, authHeader: ("X-API-KEY", key)))
            candidates.append(PollEndpoint(url: statusURL, authHeader: ("Authorization", "Key \(key)")))
            candidates.append(PollEndpoint(url: statusURL, authHeader: ("Authorization", "Bearer \(key)")))
        }

        log("discovering working poll endpoint — trying \(candidates.count) candidates")
        for (i, candidate) in candidates.enumerated() {
            let result = await probe(candidate)
            log("  [\(i + 1)/\(candidates.count)] \(candidate.authHeader.name) \(candidate.url.absoluteString) → \(result.summary)")
            if result.usable {
                log("✓ using: \(candidate.url.absoluteString)  header=\(candidate.authHeader.name)")
                return candidate
            }
        }

        throw ProviderError.badResponse("""
            No working polling endpoint found. The Spark gateway accepted the POST \
            (request_id=\(requestID)) but cannot be polled with this key:
              • all gateway paths (/api/spark/fal/*) returned 404 — no status endpoint exists
              • queue.fal.run rejects this key (401) — it's a gateway key, not a fal key
            Fix: backend team must expose GET /api/spark/fal/status/{id}, OR set a real \
            fal.ai key via KeyStore.shared[.fal] = "fal_..."
            """)
    }

    private struct ProbeResult {
        let usable: Bool          // true if endpoint exists and might return result
        let summary: String       // log line
    }

    private func probe(_ candidate: PollEndpoint) async -> ProbeResult {
        var request = URLRequest(url: candidate.url)
        request.httpMethod = "GET"
        request.setValue(candidate.authHeader.value, forHTTPHeaderField: candidate.authHeader.name)
        request.timeoutInterval = 8

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                return ProbeResult(usable: false, summary: "no HTTP response")
            }
            // 200 → great, endpoint exists and we can poll
            // 202 / 102 / 425 → endpoint exists, job still processing (also usable)
            // 401/403 → wrong auth
            // 404 → wrong path
            // 5xx → server-side issue; try elsewhere first
            switch http.statusCode {
            case 200..<300:
                return ProbeResult(usable: true, summary: "HTTP \(http.statusCode) ✓")
            case 102, 202, 425:
                return ProbeResult(usable: true, summary: "HTTP \(http.statusCode) (in progress)")
            default:
                let snippet = String(data: data, encoding: .utf8)?.prefix(80) ?? ""
                return ProbeResult(usable: false, summary: "HTTP \(http.statusCode) \(snippet)")
            }
        } catch {
            return ProbeResult(usable: false, summary: "error: \(error.localizedDescription)")
        }
    }

    // MARK: - Polling discovered endpoint

    private func pollStatus(at url: URL,
                            authHeader: (name: String, value: String),
                            key: String) async throws -> String {
        let deadline = Date().addingTimeInterval(pollTimeout)
        var attempt = 0

        while Date() < deadline {
            attempt += 1

            do {
                var request = URLRequest(url: url)
                request.httpMethod = "GET"
                request.setValue(authHeader.value, forHTTPHeaderField: authHeader.name)
                let (data, response) = try await session.data(for: request)

                guard let http = response as? HTTPURLResponse else {
                    log("poll \(attempt): no HTTP response")
                    try await Task.sleep(nanoseconds: pollIntervalNS)
                    continue
                }

                if (200..<300).contains(http.statusCode),
                   let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {

                    if let status = (body["status"] as? String)?.lowercased(),
                       status == "failed" || status == "error" {
                        let msg = body["error"] as? String ?? body["message"] as? String ?? "unknown"
                        throw ProviderError.badResponse("Generation failed: \(msg)")
                    }

                    if let imageURL = parseImageURL(body) {
                        log("✓ poll \(attempt): image ready — \(imageURL)")
                        return imageURL
                    }

                    let status = body["status"] as? String ?? "(no status field)"
                    log("poll \(attempt): \(status)")
                } else {
                    log("poll \(attempt): HTTP \(http.statusCode)")
                }

            } catch let e as ProviderError {
                if case .badResponse = e { throw e }
                log("poll \(attempt): \(e.errorDescription ?? "error")")
            } catch {
                log("poll \(attempt): \(error.localizedDescription)")
            }

            try await Task.sleep(nanoseconds: pollIntervalNS)
        }

        log("✗ TIMEOUT after \(attempt) polls (\(Int(pollTimeout))s)")
        throw ProviderError.badResponse("Image generation timed out after \(Int(pollTimeout))s")
    }

    // MARK: - Response parsing  (per spec)

    private func parseImageURL(_ body: [String: Any]) -> String? {
        if let images = body["images"] as? [[String: Any]],
           let url = images.first?["url"] as? String { return url }
        if let image = body["image"] as? [String: Any],
           let url = image["url"] as? String { return url }
        if let url = body["image_url"] as? String { return url }
        if let output = body["output"] as? [String: Any] {
            if let images = output["images"] as? [[String: Any]],
               let url = images.first?["url"] as? String { return url }
            if let url = output["image_url"] as? String { return url }
        }
        return nil
    }

    // MARK: - HTTP

    private func postJSON(url: URL, key: String, body: [String: Any]) async throws -> [String: Any] {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(key, forHTTPHeaderField: "X-API-KEY")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await sendAndParse(request, label: "POST \(url.path)")
    }

    /// status_url polling — same X-API-KEY header per spec
    private func getJSON(url: URL, key: String) async throws -> [String: Any] {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(key, forHTTPHeaderField: "X-API-KEY")
        return try await sendAndParse(request, label: "GET \(url.path)")
    }

    private func sendAndParse(_ request: URLRequest, label: String) async throws -> [String: Any] {
        let (data, response) = try await fetch(request)
        try validate(response, data: data, label: label)

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            let snippet = String(data: data, encoding: .utf8)?.prefix(400) ?? "(non-utf8)"
            log("\(label): non-JSON response: \(snippet)")
            throw ProviderError.badResponse("Non-JSON response")
        }
        return json
    }

    private func fetch(_ request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await session.data(for: request)
        } catch let e as URLError {
            log("URLError [\(e.code.rawValue)]: \(e.localizedDescription)")
            throw ProviderError.network(e)
        } catch {
            throw ProviderError.unknown(error)
        }
    }

    private func validate(_ response: URLResponse, data: Data, label: String) throws {
        guard let http = response as? HTTPURLResponse else {
            throw ProviderError.badResponse("No HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            let snippet = String(data: data, encoding: .utf8)?.prefix(400) ?? "(non-utf8)"
            log("\(label): HTTP \(http.statusCode)  body=\(snippet)")
            throw ProviderError.http(http.statusCode)
        }
    }

    private func prettyPrint(_ obj: [String: Any], max: Int = 800) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .sortedKeys]),
              let s = String(data: data, encoding: .utf8) else {
            return "\(obj)"
        }
        return s.count > max ? String(s.prefix(max)) + "…[truncated]" : s
    }
}
