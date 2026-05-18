import Foundation

/// Pass-2 user prompts for the two-pass alpha-matte technique used by
/// `/sticker` and `/gif`. Pass 1 (system-prompt-driven) renders the
/// subject on pure white; pass 2 takes the pass-1 PNG as an inline
/// reference and asks the model to swap the background to pure black
/// **without touching the subject**. The client then runs
/// `AlphaMatte.differenceMatte(onWhite:onBlack:)` over the two renders to
/// recover per-pixel alpha.
///
/// Article reference:
/// jidefr.medium.com/generating-transparent-background-images-with-nano-banana-pro
enum MattePrompts {

    /// Single-image variant. Used by `/sticker`. Mirrors Android's
    /// `StickerIntegration.EDIT_TO_BLACK_PROMPT`.
    static let swapWhiteToBlackSubject: String =
        "Change the white background to a solid pure black #000000 background. "
        + "Keep the subject and every pixel of it exactly unchanged: "
        + "same identity, same colors, same outline, same pose, same "
        + "expression, same proportions, same line weight. Do not move, "
        + "resize, recolor, or restyle the subject. Only the background "
        + "color changes."

    /// Sprite-sheet variant. Used by `/gif`. Adds explicit "preserve the
    /// cell grid" guardrails so the model doesn't re-stitch frames or drop
    /// rows when swapping the background. Mirrors Android's
    /// `GifIntegration.EDIT_TO_BLACK_PROMPT`.
    static let swapWhiteToBlackSheet: String =
        "Change the white background to a solid pure black #000000 background. "
        + "Keep every cell, every frame, every pose, every expression, and "
        + "every pixel of the subject exactly unchanged. Do not move, resize, "
        + "recolor, or restyle the subject. Do not change the layout, cell "
        + "count, or frame ordering. Only the background color changes."
}
