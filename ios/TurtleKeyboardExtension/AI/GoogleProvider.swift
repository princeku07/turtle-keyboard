import Foundation
import CoreGraphics
import ImageIO

// MARK: - GoogleProvider
//
// Text + image commands via Google Generative Language API (Gemini).
// Mirrors android/ai/GeminiClient.java:
//   • text   → gemini-2.5-flash-lite  (or any text-capable model in ModelRegistry)
//   • image  → gemini-2.5-flash-image ("Nano Banana"), returned as inline PNG bytes
//
// Supported commands: /fix, /tone, /reply, /tl, /ask, /org, /cap
//
// API key: set via KeyStore.shared[.google] = "your_key"
// Get a key at: https://aistudio.google.com/app/apikey

final class GoogleProvider: AIProvider {
    let id: ProviderID = .google

    private let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest  = 30
        cfg.timeoutIntervalForResource = 90
        return URLSession(configuration: cfg)
    }()

    func execute(_ payload: CommandPayload) async throws -> CommandResult {
        // `/sticker` runs a bespoke two-pass matte pipeline — short-circuit
        // here so the standard generate / edit path doesn't fire.
        if payload.command == "sticker" {
            return try await runStickerPipeline(payload: payload)
        }
        // `/gif` runs a similar two-pass matte but over a sprite sheet,
        // then slices + encodes an animated GIF.
        if payload.command == "gif" {
            return try await runGifPipeline(payload: payload)
        }

        let systemPrompt = CommandRouter.systemPrompt(for: payload.command, prompt: payload.prompt)
        let userContent  = userMessage(from: payload)

        if payload.model.supports(.imageGeneration) {
            // /edit and friends supply a reference image — route through
            // the multi-part path that prepends inlineData to the content.
            if let ref = payload.referenceImage, !ref.isEmpty {
                let png = try await editImage(modelID: payload.model.id,
                                              systemPrompt: systemPrompt,
                                              userPrompt: userContent,
                                              reference: ref)
                return .imageData(png)
            }
            let png = try await generateImage(modelID: payload.model.id,
                                              systemPrompt: systemPrompt,
                                              userPrompt: userContent)
            return .imageData(png)
        }

        let raw = try await generateText(modelID: payload.model.id,
                                         systemPrompt: systemPrompt,
                                         userPrompt: userContent)

        switch payload.command {
        case "reply": return .suggestions(parseSuggestionsJSON(raw))
        default:      return .text(raw)
        }
    }

    // MARK: - Prompt construction

    private func userMessage(from p: CommandPayload) -> String {
        switch p.command {
        case "fix", "proofread", "tone", "reply", "tl":
            return p.context.isEmpty ? p.prompt : p.context
        case "style":
            // The inline-image text part has to carry the full restyle
            // instruction — Gemini ignores systemInstruction for image
            // edits on flash-image. `StylePresets.userPrompt(for:)`
            // expands a preset key (e.g. "ghibli") into the curated
            // "Restyle this image as: …" sentence, or wraps free-form
            // text the same way.
            return StylePresets.userPrompt(for: p.prompt)
        default:
            return p.prompt
        }
    }

    // MARK: - Text

    private func generateText(modelID: String, systemPrompt: String, userPrompt: String) async throws -> String {
        let data = try await callGenerateContent(modelID: modelID,
                                                 systemPrompt: systemPrompt,
                                                 userPrompt: userPrompt)
        guard let json       = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidates = json["candidates"] as? [[String: Any]],
              let content    = candidates.first?["content"] as? [String: Any],
              let parts      = content["parts"] as? [[String: Any]] else {
            throw ProviderError.badResponse("Unexpected Gemini response shape")
        }
        let text = parts.compactMap { $0["text"] as? String }.joined()
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Image

    private func generateImage(modelID: String, systemPrompt: String, userPrompt: String) async throws -> Data {
        let data = try await callGenerateContent(modelID: modelID,
                                                 systemPrompt: systemPrompt,
                                                 userPrompt: userPrompt)

        guard let json       = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidates = json["candidates"] as? [[String: Any]],
              let content    = candidates.first?["content"] as? [String: Any],
              let parts      = content["parts"] as? [[String: Any]] else {
            throw ProviderError.badResponse("Unexpected Gemini image response shape")
        }

        for part in parts {
            // Accept both camelCase and snake_case (the API has used both).
            let inline = (part["inlineData"] as? [String: Any]) ?? (part["inline_data"] as? [String: Any])
            if let b64 = inline?["data"] as? String,
               let bytes = Data(base64Encoded: b64, options: [.ignoreUnknownCharacters]) {
                return bytes
            }
        }
        throw ProviderError.badResponse("No inline image part in Gemini response")
    }

    // MARK: - Image edit (multipart: inlineData + text)
    //
    // Mirrors android/.../ai/GeminiClient.doImageEdit. The single `contents`
    // entry carries two parts — the reference image inline (base64 PNG)
    // followed by the user's instruction text. Gemini's flash-image model
    // reads both and returns the edited image as inlineData in the response.

    private func editImage(modelID: String,
                           systemPrompt: String,
                           userPrompt: String,
                           reference: Data) async throws -> Data {
        let data = try await callGenerateContent(modelID: modelID,
                                                 systemPrompt: systemPrompt,
                                                 userPrompt: userPrompt,
                                                 reference: reference)

        guard let json       = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidates = json["candidates"] as? [[String: Any]],
              let content    = candidates.first?["content"] as? [String: Any],
              let parts      = content["parts"] as? [[String: Any]] else {
            throw ProviderError.badResponse("Unexpected Gemini edit response shape")
        }
        for part in parts {
            let inline = (part["inlineData"] as? [String: Any]) ?? (part["inline_data"] as? [String: Any])
            if let b64 = inline?["data"] as? String,
               let bytes = Data(base64Encoded: b64, options: [.ignoreUnknownCharacters]) {
                return bytes
            }
        }
        throw ProviderError.badResponse("No edited-image part in Gemini response")
    }

    // MARK: - HTTP

    private func callGenerateContent(modelID: String,
                                     systemPrompt: String,
                                     userPrompt: String,
                                     reference: Data? = nil) async throws -> Data {
        let key = try KeyStore.shared.requireKey(for: .google)

        guard let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/\(modelID):generateContent") else {
            throw ProviderError.badResponse("Invalid Gemini URL for model \(modelID)")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(key, forHTTPHeaderField: "X-goog-api-key")

        var parts: [[String: Any]] = []
        if let ref = reference, !ref.isEmpty {
            // Inline image first, then the text instruction. Same order
            // Gemini's docs use for image-edit prompts.
            parts.append([
                "inlineData": [
                    "mimeType": "image/png",
                    "data": ref.base64EncodedString(),
                ]
            ])
        }
        parts.append(["text": userPrompt])

        var body: [String: Any] = ["contents": [["parts": parts]]]
        if !systemPrompt.isEmpty {
            body["systemInstruction"] = ["parts": [["text": systemPrompt]]]
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await fetch(request)
        try validate(response, data: data)
        return data
    }

    private func fetch(_ request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await session.data(for: request)
        } catch let e as URLError { throw ProviderError.network(e) }
        catch { throw ProviderError.unknown(error) }
    }

    private func validate(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else {
            throw ProviderError.badResponse("No HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            let snippet = String(data: data, encoding: .utf8)?.prefix(400) ?? ""
            NSLog("🐢[Gemini] HTTP %d %@", http.statusCode, String(snippet))
            throw ProviderError.http(http.statusCode)
        }
    }

    // MARK: - /sticker pipeline (two-pass matte)
    //
    // Mirrors `android/.../integration/sticker/StickerIntegration.java`.
    // Pass 1: text-to-image (or edit if a reference photo is staged) with
    // the sticker.txt system prompt — locks the model to a white-bg render.
    // Pass 2: take the pass-1 PNG as inline reference, ask the model to
    // swap the background to black while preserving every subject pixel.
    // The two renders are then fed to `AlphaMatte.differenceMatte` to
    // recover per-pixel alpha; the result is encoded as a transparent PNG.
    // Falls back to the opaque pass-1 PNG if any step fails — the user
    // still gets a sticker, just without the cut-out.

    private func runStickerPipeline(payload: CommandPayload) async throws -> CommandResult {
        let modelID = payload.model.id
        let hasPhoto = (payload.referenceImage?.isEmpty == false)

        // Photo mode uses `sticker_photo.txt` — same matte constraints,
        // but the prompt tells the model to preserve the subject's
        // identity from the inline reference photo. Text-only mode uses
        // `sticker.txt` (generate from scratch).
        let pass1Prompt = PromptLoader.load(id: hasPhoto ? "sticker_photo" : "sticker")
            ?? CommandRouter.systemPrompt(for: "sticker", prompt: payload.prompt)

        // Pass 1
        let whitePng: Data
        if let ref = payload.referenceImage, !ref.isEmpty {
            whitePng = try await editImage(modelID: modelID,
                                            systemPrompt: pass1Prompt,
                                            userPrompt: payload.prompt,
                                            reference: ref)
        } else {
            whitePng = try await generateImage(modelID: modelID,
                                                systemPrompt: pass1Prompt,
                                                userPrompt: payload.prompt)
        }

        // Pass 2 — swap white → black. Best-effort: a failure here just
        // means the user gets the opaque white-bg pass-1 image.
        let blackPng: Data
        do {
            blackPng = try await editImage(modelID: modelID,
                                            systemPrompt: "",
                                            userPrompt: MattePrompts.swapWhiteToBlackSubject,
                                            reference: whitePng)
        } catch {
            NSLog("🐢[Gemini] sticker pass-2 failed (%@) — falling back to opaque pass-1",
                  String(describing: error))
            return .imageData(whitePng)
        }

        // Matte. Any decode / matte / encode failure falls back to pass-1.
        guard let onWhite = Self.decodeCGImage(from: whitePng),
              let onBlack = Self.decodeCGImage(from: blackPng) else {
            return .imageData(whitePng)
        }
        guard onWhite.width == onBlack.width, onWhite.height == onBlack.height else {
            NSLog("🐢[Gemini] sticker matte dim mismatch %dx%d vs %dx%d — falling back",
                  onWhite.width, onWhite.height, onBlack.width, onBlack.height)
            return .imageData(whitePng)
        }
        guard let matted = AlphaMatte.differenceMatte(onWhite: onWhite, onBlack: onBlack),
              let pngData = Self.encodePNG(matted) else {
            return .imageData(whitePng)
        }
        return .imageData(pngData)
    }

    // MARK: - /gif pipeline (sheet → matte → slice → animated GIF)
    //
    // Mirrors `android/.../integration/gif/GifIntegration.java`. Pass 1
    // asks Gemini for a 4-column sprite SHEET on white; pass 2 swaps the
    // sheet's background to black; matte recovers per-cell alpha; the
    // sheet is sliced into frames via `SpriteSheetSlicer`; finally the
    // frames are encoded as an animated GIF via ImageIO.
    //
    // Failure modes (each falls back to the next-most-useful output):
    //   • Pass-2 model error      → return the opaque sheet PNG.
    //   • Decode / dim mismatch   → return the opaque sheet PNG.
    //   • Slice / encode failure  → return the matted sheet PNG.

    private func runGifPipeline(payload: CommandPayload) async throws -> CommandResult {
        let gifSystemPrompt = CommandRouter.systemPrompt(for: "gif", prompt: payload.prompt)
        let modelID = payload.model.id

        // Pass 1 — sprite sheet on white. Photo mode (when a reference
        // photo is staged via the picker) routes through `editImage` so
        // the model animates the user's subject across the sheet's
        // cells; text-only mode falls back to `generateImage`. Mirrors
        // Android's `GifIntegration.runGif`, which requires the photo
        // unconditionally — on iOS we leave the text-only fallback in
        // place for callers that might dispatch the command without a
        // staged image (e.g. integration scripting).
        let whiteSheet: Data
        if let ref = payload.referenceImage, !ref.isEmpty {
            whiteSheet = try await editImage(modelID: modelID,
                                              systemPrompt: gifSystemPrompt,
                                              userPrompt: payload.prompt,
                                              reference: ref)
        } else {
            whiteSheet = try await generateImage(modelID: modelID,
                                                  systemPrompt: gifSystemPrompt,
                                                  userPrompt: payload.prompt)
        }

        // Pass 2 — swap white → black on the sheet. Sheet variant of the
        // matte prompt adds explicit "preserve cell grid / count / order"
        // guardrails so the model doesn't re-stitch frames.
        let blackSheet: Data
        do {
            blackSheet = try await editImage(modelID: modelID,
                                              systemPrompt: "",
                                              userPrompt: MattePrompts.swapWhiteToBlackSheet,
                                              reference: whiteSheet)
        } catch {
            NSLog("🐢[Gemini] gif pass-2 failed (%@) — falling back to opaque sheet",
                  String(describing: error))
            return .imageData(whiteSheet)
        }

        // Matte.
        guard let onWhite = Self.decodeCGImage(from: whiteSheet),
              let onBlack = Self.decodeCGImage(from: blackSheet) else {
            return .imageData(whiteSheet)
        }
        guard onWhite.width == onBlack.width, onWhite.height == onBlack.height else {
            NSLog("🐢[Gemini] gif matte dim mismatch %dx%d vs %dx%d — falling back",
                  onWhite.width, onWhite.height, onBlack.width, onBlack.height)
            return .imageData(whiteSheet)
        }
        guard let mattedSheet = AlphaMatte.differenceMatte(onWhite: onWhite, onBlack: onBlack) else {
            return .imageData(whiteSheet)
        }

        // Detect grid shape from the sheet's aspect ratio, then slice.
        let aspect = Double(mattedSheet.width) / Double(mattedSheet.height)
        let rows = SpriteSheetSlicer.gridRows(forAspect: aspect)
        let delayCs = SpriteSheetSlicer.frameDelayCentiseconds(forRows: rows)
        NSLog("🐢[Gemini] gif sheet %dx%d aspect=%.2f → 4x%d @ %dcs",
              mattedSheet.width, mattedSheet.height, aspect, rows, delayCs)
        guard let frames = SpriteSheetSlicer.slice(mattedSheet,
                                                    cols: SpriteSheetSlicer.cols,
                                                    rows: rows),
              !frames.isEmpty else {
            // Slice failed — fall back to the still matted sheet PNG.
            if let png = Self.encodePNG(mattedSheet) { return .imageData(png) }
            return .imageData(whiteSheet)
        }

        // Encode animated GIF via ImageIO. Loop = 0 means infinite.
        guard let gif = Self.encodeAnimatedGIF(frames: frames,
                                                 delaySeconds: Double(delayCs) / 100.0,
                                                 loops: 0) else {
            if let png = Self.encodePNG(mattedSheet) { return .imageData(png) }
            return .imageData(whiteSheet)
        }
        return .imageData(gif)
    }

    /// Encode an array of `CGImage` frames as an animated GIF via ImageIO.
    /// `delaySeconds` is per-frame delay; `loops = 0` loops forever.
    /// Returns nil on any encode failure.
    ///
    /// Mirrors the Graphic Control Extension that Android's `GifEncoder`
    /// writes: transparency flag = 1, disposal method = 2 (restore to
    /// background), background colour index = 0 (transparent). Without
    /// the disposal hint, transparent frames stack on top of each other
    /// in the rendered GIF — viewers like WhatsApp / iMessage paint each
    /// new frame over the previous one, so motion blur trails the
    /// subject and the chat background bleeds through.
    static func encodeAnimatedGIF(frames: [CGImage],
                                  delaySeconds: Double,
                                  loops: Int) -> Data? {
        guard !frames.isEmpty else { return nil }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(
            out, "com.compuserve.gif" as CFString, frames.count, nil
        ) else { return nil }

        // Global: loop forever.
        let gifProps: [CFString: Any] = [
            kCGImagePropertyGIFDictionary: [
                kCGImagePropertyGIFLoopCount: loops,
                kCGImagePropertyGIFHasGlobalColorMap: false,
            ] as CFDictionary,
        ]
        CGImageDestinationSetProperties(dest, gifProps as CFDictionary)

        // Per-frame: delay + frame-info hint (newer ImageIO honours this).
        // Note: `kCGImagePropertyGIFDelayTime` is clamped to ≥ 0.02 s by
        // some viewers, so we also set the unclamped version which
        // strict viewers (Twitter, WhatsApp) consult instead.
        let frameProps: [CFString: Any] = [
            kCGImagePropertyGIFDictionary: [
                kCGImagePropertyGIFDelayTime:          delaySeconds,
                kCGImagePropertyGIFUnclampedDelayTime: delaySeconds,
                // Disposal method 2 = "restore to background" — each
                // frame clears its predecessor before drawing.
                "DisposalMethod" as CFString:          2,
            ] as CFDictionary,
        ]
        for frame in frames {
            CGImageDestinationAddImage(dest, frame, frameProps as CFDictionary)
        }

        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Data
    }

    /// Decode raw bytes into a `CGImage`. Used by `/sticker` and `/gif`.
    static func decodeCGImage(from data: Data) -> CGImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, [
            kCGImageSourceShouldCache: false,
        ] as CFDictionary) else { return nil }
        return CGImageSourceCreateImageAtIndex(src, 0, nil)
    }

    /// Encode a `CGImage` as PNG bytes via ImageIO. Preserves the alpha
    /// channel that `AlphaMatte` writes into the buffer.
    static func encodePNG(_ image: CGImage) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(
            out, "public.png" as CFString, 1, nil
        ) else { return nil }
        CGImageDestinationAddImage(dest, image, nil)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Data
    }
}
