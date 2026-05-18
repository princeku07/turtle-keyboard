import Foundation

/// Curated style-transfer presets for `/style`. Mirrors Android's
/// `LmStudioAiClient.STYLE_PRESETS` 1-to-1 — same keys, same descriptions,
/// same display order — so the cross-platform behaviour stays in lockstep.
///
/// Lookup is case-insensitive. Anything not in the map is treated as a
/// free-form style instruction (see `CommandRouter.systemPrompt` for the
/// `case "style":` branch).
enum StylePresets {

    /// Display order for the preset chip strip. Lower-case keys; the UI
    /// is responsible for any title-casing.
    static let orderedKeys: [String] = [
        "ghibli", "anime", "pixar", "disney", "lego", "clay",
        "pixel", "watercolor", "oil", "comic", "manga",
        "cyberpunk", "vintage", "noir", "vaporwave", "lineart",
    ]

    /// Builds the text part sent alongside the inline reference image
    /// for `/style`. Mirrors Android's `LmStudioAiClient.stylePromptFor`
    /// 1-to-1 — Gemini's image-edit models read the text of the content
    /// turn (not the `systemInstruction`), so the actual restyle
    /// instruction must live here for the model to honour it.
    static func userPrompt(for rawPrompt: String) -> String {
        let key = rawPrompt.trimmingCharacters(in: .whitespaces).lowercased()
        if let desc = description(forKey: key) {
            return "Restyle this image as: \(desc) Preserve the subject's identity and composition."
        }
        return "Restyle this image: \(rawPrompt). Preserve the subject's identity and composition."
    }

    /// Curated descriptions sent to the image model. Tuned to bias Nano
    /// Banana toward visually distinct, recognizable looks (the kind of
    /// thing that goes viral when applied to a selfie).
    static func description(forKey key: String) -> String? {
        switch key {
        case "ghibli":     return "Studio Ghibli watercolor anime style. Soft pastel palette, hand-drawn feel, dreamy atmosphere, gentle natural lighting, painterly textures."
        case "anime":      return "Modern Japanese anime style. Crisp lineart, vibrant cel-shaded colors, expressive eyes, stylized features."
        case "pixar":      return "3D Pixar animation style. Soft volumetric lighting, slightly exaggerated proportions, warm cinematic colors, smooth surfaces."
        case "disney":     return "Classic 2D Disney animation. Clean inked lineart, expressive eyes, vibrant flat colors, smooth shading."
        case "lego":       return "LEGO minifigure style. Plastic blocky figure, signature LEGO yellow skin if a person, simple geometric shapes, studded surfaces."
        case "clay":       return "Stop-motion claymation in the style of Aardman Studios. Visible clay texture, fingerprints, slightly imperfect lopsided features, warm light."
        case "pixel":      return "16-bit pixel art. Limited retro palette, visible pixels, classic JRPG aesthetic."
        case "watercolor": return "Watercolor painting. Soft bleeding edges, visible paper texture, transparent washes of color, loose brushstrokes."
        case "oil":        return "Renaissance oil painting. Rich textures, dramatic chiaroscuro lighting, refined brushwork, museum-quality feel."
        case "comic":      return "Western comic book style. Bold black ink outlines, halftone dot shading, vivid flat colors, dynamic posing."
        case "manga":      return "Black and white manga. Detailed linework, screen tones for shading, expressive ink strokes, no color."
        case "cyberpunk":  return "Cyberpunk neon. Saturated pinks and cyans, rain-slick streets, holographic signage, dystopian future vibe."
        case "vintage":    return "Vintage 1970s polaroid. Faded warm colors, light leaks, fine grain, nostalgic feel, soft edges."
        case "noir":       return "Film noir black and white. High contrast, dramatic shadows, atmospheric mood, cinematic framing."
        case "vaporwave":  return "Vaporwave aesthetic. Pastel pinks and purples, retro 80s elements, glitch art, palm trees, sunset gradient."
        case "lineart":    return "Clean black-ink line drawing on white. No color, no shading, confident contour lines."
        default:           return nil
        }
    }
}
