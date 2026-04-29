package com.prince.turtlekeyboard.ime;

import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.KeyboardView;
import android.media.AudioManager;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.ai.StubAiClient;
import com.prince.turtlekeyboard.command.CommandComposer;
import com.prince.turtlekeyboard.command.CommandDispatcher;
import com.prince.turtlekeyboard.command.CommandRegistry;
import com.prince.turtlekeyboard.command.SlashCommand;
import com.prince.turtlekeyboard.command.SlashCommandDetector;
import com.prince.turtlekeyboard.gesture.SpaceGestureHandler;
import com.prince.turtlekeyboard.ime.view.KeyPreviewPopup;
import com.prince.turtlekeyboard.ime.view.KeyboardRootView;
import com.prince.turtlekeyboard.input.InputCommitter;
import com.prince.turtlekeyboard.keyboard.KeyboardController;
import com.prince.turtlekeyboard.keyboard.Keycodes;
import com.prince.turtlekeyboard.keyboard.ShiftController;
import com.prince.turtlekeyboard.suggestion.BasicSuggestionProvider;
import com.prince.turtlekeyboard.suggestion.SuggestionProvider;
import com.prince.turtlekeyboard.theme.KeyboardTheme;
import com.prince.turtlekeyboard.theme.ThemeManager;

import java.util.List;

/**
 * Thin orchestrator. Owns nothing but the lifecycle hookup: it wires the bound view to a
 * {@link KeyboardController}, routes key events through a {@link ShiftController} and
 * {@link InputCommitter}, and forwards completed text to the slash-command pipeline.
 */
public class TurtleInputMethodService extends InputMethodService
        implements KeyboardView.OnKeyboardActionListener, CommandDispatcher.ResultUi {

    private KeyboardRootView root;
    private KeyboardController keyboard;
    private ShiftController shift;
    private InputCommitter committer;
    private SpaceGestureHandler spaceGesture;
    private SlashCommandDetector slashDetector;
    private CommandRegistry registry;
    private CommandComposer composer;
    private CommandDispatcher dispatcher;
    private SuggestionProvider suggestionProvider;
    private ThemeManager themes;
    private AudioManager audio;
    private KeyPreviewPopup preview;
    private KeyboardView keyboardView;

    @Override
    public View onCreateInputView() {
        themes = new ThemeManager(this);
        root = (KeyboardRootView) View.inflate(this, R.layout.keyboard_view, null);

        KeyboardView kv = root.keyboardView();
        keyboard = new KeyboardController(this);
        keyboard.attach(kv);
        shift = new ShiftController();
        shift.attach(kv);
        kv.setOnKeyboardActionListener(this);
        // Framework preview is disabled because its anchor math drifts when the
        // KeyboardView isn't the IME root. We use a custom PopupWindow instead.
        kv.setPreviewEnabled(false);
        kv.setHapticFeedbackEnabled(true);
        keyboardView = kv;
        preview = new KeyPreviewPopup(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);

        committer = new InputCommitter(this::getCurrentInputConnection);
        spaceGesture = new SpaceGestureHandler(this::onDoubleTapSpace);
        registry = new CommandRegistry();
        slashDetector = new SlashCommandDetector(committer, registry, this::onSlashCommand);
        composer = new CommandComposer(new CommandComposer.Ui() {
            @Override public void onNameChanged(String displayed) {
                root.panel().hide();
                root.banner().show(displayed);
            }
            @Override public void onPromptStart(String commandName) {
                root.banner().clear();
                CommandRegistry.Entry e = registry.get(commandName);
                String label = (e != null ? e.emoji + " " + e.label : "/" + commandName);
                root.panel().show(label, hintFor(commandName), "");
            }
            @Override public void onPromptChanged(String commandName, String query) {
                root.panel().update(query);
            }
            @Override public void onComposeEnd() {
                root.banner().clear();
                root.panel().hide();
            }
        });
        dispatcher = new CommandDispatcher(new StubAiClient(), committer, this);
        suggestionProvider = new BasicSuggestionProvider();

        root.panel().setOnGoListener(this::dispatchPromptPanel);
        root.strip().setOnPickListener(this::onSuggestionPicked);
        applyTheme();
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        keyboard.setLayout(KeyboardController.Layout.QWERTY);
        shift.reset();
        root.banner().clear();
        refreshSuggestions();
    }

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        if (committer.connection() == null) return;

        if (composer.isActive() && handleComposingKey(primaryCode)) return;

        switch (primaryCode) {
            case Keycodes.DELETE:
                committer.backspace();
                refreshSuggestions();
                break;
            case Keycodes.SHIFT:
                if (keyboard.isQwerty()) shift.onShiftPress();
                else keyboard.toggleSymbolShift();
                break;
            case Keycodes.DONE:
                committer.sendEnter();
                break;
            case Keycodes.MODE_CHANGE:
                keyboard.toggleLetterSymbol();
                if (keyboard.isQwerty()) shift.reapply();
                break;
            case Keycodes.SLASH:
                if (atWordBoundary()) {
                    composer.startName();
                } else {
                    emitChar(primaryCode);
                }
                break;
            default:
                emitChar(primaryCode);
                break;
        }
    }

    /** Returns true if the key was consumed by the in-keyboard command composer. */
    private boolean handleComposingKey(int primaryCode) {
        switch (primaryCode) {
            case Keycodes.DELETE:
                composer.backspace();
                return true;
            case Keycodes.SHIFT:
                if (keyboard.isQwerty()) shift.onShiftPress();
                else keyboard.toggleSymbolShift();
                return true;
            case Keycodes.MODE_CHANGE:
                keyboard.toggleLetterSymbol();
                if (keyboard.isQwerty()) shift.reapply();
                return true;
            case Keycodes.SPACE:
            case Keycodes.ENTER:
            case Keycodes.DONE:
                finishCompose((char) primaryCode);
                return true;
            default:
                if (primaryCode <= 0) return true; // swallow other function keys
                char c = (char) primaryCode;
                if (Character.isLetter(c) && shift.isUpper()) c = Character.toUpperCase(c);
                composer.appendChar(c);
                shift.onCharCommitted();
                return true;
        }
    }

    private void finishCompose(char terminator) {
        if (composer.mode() == CommandComposer.Mode.PROMPT) {
            // Enter / Done in PROMPT mode dispatches; SPACE just appends to the query.
            if (terminator == Keycodes.SPACE) {
                composer.appendChar(' ');
                return;
            }
            dispatchPromptPanel();
            return;
        }

        // NAME mode: terminator chosen by the user determines transition.
        String text = composer.nameText();
        SlashCommand cmd = SlashCommand.parse(text);
        if (cmd != null && registry.has(cmd.name)) {
            CommandRegistry.Entry entry = registry.get(cmd.name);
            if (entry.needsPrompt) {
                composer.enterPromptMode(cmd.name);
            } else {
                composer.cancel();
                dispatcher.dispatchComposed(cmd);
            }
        } else {
            // Unknown / malformed command — surface what was typed so the user doesn't lose it.
            composer.cancel();
            committer.commitText(text + terminator);
            refreshSuggestions();
        }
    }

    private void dispatchPromptPanel() {
        if (composer.mode() != CommandComposer.Mode.PROMPT) return;
        String name = composer.commandName();
        String query = composer.query();
        composer.cancel();
        SlashCommand cmd = new SlashCommand(name, query,
                "/" + name + (query.isEmpty() ? "" : " " + query));
        dispatcher.dispatchComposed(cmd);
    }

    private String hintFor(String commandName) {
        switch (commandName) {
            case "search": return "type a query or URL…";
            case "tl":     return "text to translate…";
            case "tone":   return "rewrite tone (e.g. formal)…";
            case "cap":    return "describe the image…";
            default:        return "type and tap →";
        }
    }

    private boolean atWordBoundary() {
        CharSequence before = committer.textBeforeCursor(1);
        if (before.length() == 0) return true;
        char c = before.charAt(0);
        return Character.isWhitespace(c) || c == '\n';
    }

    private void emitChar(int code) {
        char c = (char) code;
        if (Character.isLetter(c) && shift.isUpper()) c = Character.toUpperCase(c);
        committer.commitChar(c);
        if (code == Keycodes.SPACE) spaceGesture.onSpacePressed();
        shift.onCharCommitted();
        slashDetector.onTextChanged();
        refreshSuggestions();
    }

    private void refreshSuggestions() {
        if (suggestionProvider == null) return;
        List<String> list = suggestionProvider.suggest(committer.textBeforeCursor(64));
        root.strip().setSuggestions(list);
    }

    private void onSuggestionPicked(String suggestion) {
        // Replace the current word with the picked suggestion + a trailing space.
        CharSequence before = committer.textBeforeCursor(64);
        int wordLen = 0;
        for (int i = before.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(before.charAt(i))) break;
            wordLen++;
        }
        if (wordLen > 0) committer.deleteBeforeCursor(wordLen);
        committer.commitText(suggestion + " ");
        refreshSuggestions();
    }

    private void onDoubleTapSpace() {
        // PRD §6.6: double-tap-space opens the Quick Panel. Banner is a stand-in until
        // QuickPanelView ships.
        root.banner().showAndAutoHide("🐢 Quick Panel (coming soon)", 1500L);
    }

    private void onSlashCommand(com.prince.turtlekeyboard.command.SlashCommand cmd) {
        dispatcher.dispatch(cmd);
    }

    private void applyTheme() {
        KeyboardTheme t = themes.current();
        root.applyTheme(t);
    }

    // ===== CommandDispatcher.ResultUi =====

    @Override public void showStatus(String message) {
        root.banner().showAndAutoHide(message, 2000L);
    }

    @Override public void showSuggestions(String[] suggestions) {
        root.strip().setSuggestions(java.util.Arrays.asList(suggestions));
    }

    @Override public void showImage(String imageUri) {
        // Real impl: copy to clipboard + commitContent() per PRD §6.4 / §8.3.
        root.banner().showAndAutoHide("Image ready — tap to paste", 2500L);
    }

    @Override public void clearStatus() {
        root.banner().clear();
    }

    // ===== KeyboardView.OnKeyboardActionListener stubs =====

    @Override public void onPress(int primaryCode) {
        if (root != null) {
            root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
        if (audio != null) {
            int sfx;
            switch (primaryCode) {
                case Keycodes.DELETE: sfx = AudioManager.FX_KEYPRESS_DELETE; break;
                case Keycodes.DONE:   sfx = AudioManager.FX_KEYPRESS_RETURN; break;
                case Keycodes.SPACE:  sfx = AudioManager.FX_KEYPRESS_SPACEBAR; break;
                default:              sfx = AudioManager.FX_KEYPRESS_STANDARD; break;
            }
            audio.playSoundEffect(sfx);
        }
        if (shouldPreview(primaryCode) && preview != null) {
            preview.show(keyboardView, primaryCode);
        }
    }
    @Override public void onRelease(int primaryCode) {
        if (preview != null) preview.dismiss();
    }

    private boolean shouldPreview(int code) {
        // No preview for modifier/function keys or space.
        if (code <= 0) return false;
        if (code == Keycodes.SPACE) return false;
        return true;
    }
    @Override public void onText(CharSequence text) {
        if (composer.isActive()) {
            for (int i = 0; i < text.length(); i++) composer.appendChar(text.charAt(i));
        } else {
            committer.commitText(text);
        }
    }
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}
