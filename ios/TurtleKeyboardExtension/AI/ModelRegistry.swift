import Foundation

// MARK: - ModelRegistry
//
// Catalog of Gemini models the keyboard routes to. The stack is
// Gemini-only — earlier multi-provider scaffolding (fal, Anthropic,
// OpenAI, LM Studio) was removed once Flash + Pro proved sufficient
// for every command.
//
// To swap models: edit a constant here and / or change the matching
// route in `CommandRouter.defaultRoutes`. Model IDs mirror
// android/ai/GeminiClient so cross-platform behaviour stays aligned.

enum ModelRegistry {

    /// Text completion — short structured outputs (/fix, /tone, /reply,
    /// /tl, /search, /ask, /org). Flash-Lite is 2–3× faster than
    /// flash-latest for the kind of one-paragraph turn the keyboard
    /// produces and roughly the same quality on these prompts.
    static let geminiFlash = AIModel(
        id: "gemini-2.5-flash-lite",
        displayName: "Gemini Flash Lite",
        provider: .google,
        capabilities: [.textEdit, .chat, .translation],
        isFree: false
    )

    /// Image generation + edit — Nano Banana (Flash). Drives /cap, /edit,
    /// /style, and /sticker (pass-1 and pass-2 of the matte pipeline).
    static let geminiImage = AIModel(
        id: "gemini-2.5-flash-image",
        displayName: "Gemini 2.5 Flash Image",
        provider: .google,
        capabilities: [.imageGeneration],
        isFree: false
    )

    /// Image edit — Nano Banana Pro. ~3× the per-image cost of Flash but
    /// holds complex layouts (sprite sheets, multi-cell grids) and
    /// preserves subject identity across many cells far more reliably.
    /// Routed to by /gif, which depends on the model returning ONE
    /// composite 4×N sheet instead of separate per-frame images.
    static let geminiImagePro = AIModel(
        id: "gemini-3-pro-image-preview",
        displayName: "Gemini 3 Pro Image",
        provider: .google,
        capabilities: [.imageGeneration],
        isFree: false
    )

    static let all: [AIModel] = [
        geminiFlash, geminiImage, geminiImagePro,
    ]

    static func find(id: String) -> AIModel? {
        all.first { $0.id == id }
    }

    /// Models compatible with a given command's required capability.
    static func compatible(with command: String) -> [AIModel] {
        guard let cap = CommandRouter.requiredCapability(for: command) else { return [] }
        return all.filter { $0.supports(cap) }
    }
}
