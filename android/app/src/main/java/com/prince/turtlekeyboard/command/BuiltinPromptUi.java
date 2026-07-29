package com.prince.turtlekeyboard.command;

import com.prince.turtlekeyboard.ai.TurtleAiClient;

import java.util.function.Consumer;

/**
 * Wires the built-in image-command prompt UI onto a {@link CommandRegistry}:
 * picker kinds for /edit, /style, /gif, /gift, /sticker, /us, plus prompt
 * decorators for /style (image-preview chips) and /us (text chips that hide
 * once the user types).
 *
 * <p>Kept out of the IME so the orchestrator class stays focused on lifecycle.
 * Integration-registered commands wire themselves the same way in their own
 * setup code — see {@code DriveIntegration}, {@code StickerIntegration}, etc.</p>
 */
public final class BuiltinPromptUi {

    private BuiltinPromptUi() {}

    /**
     * @param registry        the command registry to populate
     * @param onStylePreset   fired when the user taps a /style preset chip
     * @param onUsPreset      fired when the user taps a /us preset chip
     */
    public static void register(CommandRegistry registry,
                                Consumer<String> onStylePreset,
                                Consumer<String> onUsPreset) {
        registry.setImagePicker("edit",    ImagePickerKind.EDIT);
        registry.setImagePicker("style",   ImagePickerKind.EDIT);
        registry.setImagePicker("gif",     ImagePickerKind.EDIT);
        registry.setImagePicker("gift",    ImagePickerKind.EDIT);
        registry.setImagePicker("sticker", ImagePickerKind.EDIT);
        registry.setImagePicker("us",      ImagePickerKind.US);

        // /us owns the suggestion-strip row with its own preset chips — suppress
        // dictionary suggestions so the two don't compete for the same row.
        registry.setSuggestionSource("us", PromptSuggestionSource.NONE);

        registry.setPromptDecorator("style", new PromptDecorator() {
            @Override public void onStart(Ui ui) {
                ui.showImagePreviewPresets(TurtleAiClient.stylePresetNames(), onStylePreset);
            }
        });

        // /us shows preset chips until the user starts typing a custom prompt.
        registry.setPromptDecorator("us", new PromptDecorator() {
            @Override public void onStart(Ui ui) {
                ui.showTextPresets(TurtleAiClient.usPresetNames(), onUsPreset);
            }
            @Override public void onQueryChanged(Ui ui, String query) {
                if (query != null && !query.isEmpty()) {
                    ui.hidePresets();
                } else {
                    ui.showTextPresets(TurtleAiClient.usPresetNames(), onUsPreset);
                }
            }
        });
    }
}
