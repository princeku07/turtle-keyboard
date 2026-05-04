import Foundation

// MARK: - ModelRegistry
//
// Single source of truth for every AI model Turtle Keyboard can route to.
//
// To add a new model:
//   1. Add a static let here with its metadata.
//   2. Make sure the corresponding provider is registered in CommandRouter.providers.
//   3. Optionally set it as a default route in CommandRouter.defaultRoutes.

enum ModelRegistry {

    // MARK: fal.ai  ──────────────────────────────────────────────────────────

    static let flux2 = AIModel(
        id: "fal-ai/flux-2",
        displayName: "Flux 2",
        provider: .fal,
        capabilities: [.imageGeneration],
        isFree: true
    )

    static let fluxSchnell = AIModel(
        id: "fal-ai/flux/schnell",
        displayName: "Flux Schnell",
        provider: .fal,
        capabilities: [.imageGeneration],
        isFree: true
    )

    static let fluxPro = AIModel(
        id: "fal-ai/flux-pro",
        displayName: "Flux Pro",
        provider: .fal,
        capabilities: [.imageGeneration],
        isFree: false
    )

    // MARK: Local (LM Studio / llama.cpp)  ──────────────────────────────────

    static let gemma4 = AIModel(
        id: "google/gemma-4-e4b",
        displayName: "Gemma 4 e4b (local)",
        provider: .lmstudio,
        capabilities: [.textEdit, .chat, .translation],
        isFree: true
    )

    // MARK: Anthropic  ───────────────────────────────────────────────────────

    static let claudeHaiku = AIModel(
        id: "claude-haiku-4-5-20251001",
        displayName: "Claude Haiku",
        provider: .anthropic,
        capabilities: [.textEdit, .chat, .translation],
        isFree: false
    )

    static let claudeSonnet = AIModel(
        id: "claude-sonnet-4-6",
        displayName: "Claude Sonnet",
        provider: .anthropic,
        capabilities: [.textEdit, .chat, .translation],
        isFree: false
    )

    // MARK: Google  ──────────────────────────────────────────────────────────

    static let geminiFlash = AIModel(
        id: "gemini-1.5-flash",
        displayName: "Gemini Flash",
        provider: .google,
        capabilities: [.textEdit, .chat, .translation],
        isFree: false
    )

    static let geminiPro = AIModel(
        id: "gemini-1.5-pro",
        displayName: "Gemini Pro",
        provider: .google,
        capabilities: [.textEdit, .chat, .translation],
        isFree: false
    )

    // MARK: OpenAI  ──────────────────────────────────────────────────────────

    static let gpt4oMini = AIModel(
        id: "gpt-4o-mini",
        displayName: "GPT-4o mini",
        provider: .openai,
        capabilities: [.textEdit, .chat, .translation],
        isFree: false
    )

    static let gpt4o = AIModel(
        id: "gpt-4o",
        displayName: "GPT-4o",
        provider: .openai,
        capabilities: [.textEdit, .chat, .translation],
        isFree: false
    )

    // MARK: Full catalog  ────────────────────────────────────────────────────

    static let all: [AIModel] = [
        gemma4,
        flux2, fluxSchnell, fluxPro,
        claudeHaiku, claudeSonnet,
        geminiFlash, geminiPro,
        gpt4oMini, gpt4o,
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
