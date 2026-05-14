package com.prince.turtlekeyboard.ime;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.KeyboardView;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.ai.LmStudioAiClient;
import com.prince.turtlekeyboard.ai.StubAiClient;
import com.prince.turtlekeyboard.command.CommandComposer;
import com.prince.turtlekeyboard.command.CommandDispatcher;
import com.prince.turtlekeyboard.command.CommandRegistry;
import com.prince.turtlekeyboard.command.SlashCommand;
import com.prince.turtlekeyboard.command.SlashCommandDetector;
import com.prince.turtlekeyboard.gesture.SpaceGestureHandler;
import com.prince.turtlekeyboard.ime.view.KeyPreviewPopup;
import com.prince.turtlekeyboard.ime.view.KeyboardRootView;
import com.prince.notion.NotionIntegration;
import com.prince.slack.SlackIntegration;
import com.prince.split.SplitIntegration;
import com.prince.turtlekeyboard.integration.drive.DriveIntegration;
import com.prince.turtlekeyboard.integration.poll.PollIntegration;
import com.prince.turtlekeyboard.integration.wyr.WyrIntegration;
import com.prince.web.WebIntegration;
import com.prince.kbd.core.CommandProvider;
import com.prince.kbd.core.IntegrationContext;
import com.prince.kbd.core.KeyboardIntegration;
import com.prince.turtlekeyboard.command.BuiltinAiCommands;
import com.prince.turtlekeyboard.command.UserCommandPins;
import com.prince.turtlekeyboard.input.InputCommitter;
import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.integration.IntegrationRegistry;
import com.prince.turtlekeyboard.integration.KeyboardIntegrationContextImpl;
import com.prince.turtlekeyboard.integration.PersistentAppProfileRegistry;
import com.prince.turtlekeyboard.keyboard.KeyboardController;
import com.prince.turtlekeyboard.keyboard.Keycodes;
import com.prince.turtlekeyboard.keyboard.ShiftController;
import com.prince.turtlekeyboard.settings.Prefs;
import com.prince.turtlekeyboard.suggest.SuggestionEngine;
import com.prince.turtlekeyboard.suggestion.SuggestionProvider;
import com.prince.turtlekeyboard.suggestion.SymSpellSuggestionProvider;
import com.prince.turtlekeyboard.theme.KeyboardTheme;
import com.prince.turtlekeyboard.theme.ThemeManager;
import com.prince.turtlekeyboard.ui.MainActivity;
import com.prince.turtlekeyboard.voice.VoiceInputController;

import java.util.List;

/**
 * Thin orchestrator. Owns nothing but the lifecycle hookup: it wires the bound view to a
 * {@link KeyboardController}, routes key events through a {@link ShiftController} and
 * {@link InputCommitter}, and forwards completed text to the slash-command pipeline.
 */
public class TurtleInputMethodService extends InputMethodService
        implements KeyboardView.OnKeyboardActionListener, CommandDispatcher.ResultUi {

    private static final String SPLIT_TAG = "SPLITTEST";

    private KeyboardRootView root;
    private KeyboardController keyboard;
    private ShiftController shift;
    private InputCommitter committer;
    private SpaceGestureHandler spaceGesture;
    private SlashCommandDetector slashDetector;
    private CommandRegistry registry;
    private CommandComposer composer;
    private CommandDispatcher dispatcher;
    @Nullable private String currentPkg;
    private com.prince.turtlekeyboard.integration.PersistentAppProfileRegistry appProfiles;
    private com.prince.turtlekeyboard.integration.EnrolledShortcutsManager enrolledShortcuts;
    private SuggestionProvider suggestionProvider;
    private SuggestionEngine suggestionEngine;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ThemeManager themes;
    private AudioManager audio;
    private KeyPreviewPopup preview;
    private KeyboardView keyboardView;
    private VoiceInputController voice;
    private IntegrationRegistry integrations;
    /** True while {@link #onStartInputView} has fired and {@link #onFinishInputView}
     *  has not. When false, image results are surfaced as a notification instead of
     *  the in-keyboard preview because the keyboard isn't on screen. */
    private boolean inputViewVisible;

    @Override
    public void onCreate() {
        super.onCreate();
        // Kick off the dictionary load as soon as the IME service is created,
        // not when the input view is inflated — buys ~100–300 ms of head start
        // before the user actually focuses a text field, which is the
        // difference between an empty strip on first launch and a populated
        // one. Idempotent against onCreateInputView fallback below.
        suggestionEngine = new SuggestionEngine(this);
        suggestionEngine.loadAsync(this);
    }

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
                refreshCommandSuggestions(displayed);
            }
            @Override public void onPromptStart(String commandName) {
                root.banner().clear();
                root.cmdSuggestions().hide();
                CommandRegistry.Entry e = registry.get(commandName);
                String label = (e != null ? e.emoji + " " + e.label : "/" + commandName);
                root.panel().show(label, hintFor(commandName), "");
                // Surface a one-tap paste affordance when the clipboard has text — drops
                // a URL / quote / phrase straight into the prompt without re-typing.
                root.panel().setPasteAvailable(readClipboardText());
                // /edit and /style both consume an input image — launch the picker now
                // so the user picks first, then comes back and types the instruction
                // (or for /style, just a preset name like "ghibli").
                if ("edit".equals(commandName) || "style".equals(commandName)) {
                    launchEditImagePicker();
                }
                // /style + /us: surface curated preset chips so the user can fire a
                // canned prompt with one tap. /style transforms the input photo; /us
                // places the user's stored reference faces into a scenario.
                if ("style".equals(commandName)) {
                    root.presetStrip().setPresets(
                            LmStudioAiClient.stylePresetNames(),
                            TurtleInputMethodService.this::dispatchStylePreset);
                } else if ("us".equals(commandName)) {
                    root.presetStrip().setPresets(
                            LmStudioAiClient.usPresetNames(),
                            TurtleInputMethodService.this::dispatchUsPreset);
                } else {
                    root.presetStrip().hide();
                }
            }
            @Override public void onPromptChanged(String commandName, String query) {
                root.panel().update(query);
            }
            @Override public void onComposeEnd() {
                root.banner().clear();
                root.cmdSuggestions().hide();
                root.panel().hide();
                root.presetStrip().hide();
                // Don't clear the staged /edit image here — onComposeEnd fires from
                // composer.cancel() inside dispatchPromptPanel() *before* the dispatch
                // reaches the AI client, which would wipe the image we just picked.
                // Stale staged state is cleared at the next picker launch instead.
            }
        });
        // HostProvider returns the IME's SoftInputWindow decor so the HTML→image
        // renderer can briefly attach a WebView to a real window. Using the
        // IME's own decor keeps the WebView in this process and within a window
        // that's actually attached when /org runs.
        LmStudioAiClient.HostProvider hostProvider = () -> {
            android.view.Window w = getWindow() == null ? null : getWindow().getWindow();
            View decor = w == null ? null : w.getDecorView();
            return decor instanceof android.view.ViewGroup ? (android.view.ViewGroup) decor : null;
        };
        if (suggestionEngine == null) {
            // Fallback in case onCreate didn't run for some reason — should not
            // happen in practice, but cheap insurance.
            suggestionEngine = new SuggestionEngine(this);
            suggestionEngine.loadAsync(this);
        }
        suggestionProvider = new SymSpellSuggestionProvider(suggestionEngine);
        // Repaint the strip the moment the dictionary finishes loading. The
        // engine fires this callback on its load thread; hop back to the main
        // thread before touching views. Replacing the listener on each
        // onCreateInputView is fine — only the most recent IME view's refresh
        // is meaningful.
        suggestionEngine.setOnReadyListener(
                () -> mainHandler.post(this::refreshSuggestions));
        voice = new VoiceInputController(this);
        root.voiceStage().setListener(() -> { if (voice != null) voice.stop(); });

        root.panel().setOnGoListener(this::dispatchPromptPanel);
        root.panel().setOnPasteListener(text -> composer.appendString(text));
        // When the picker stages a new /edit image (or clears one) we want to show a
        // thumbnail in the prompt panel and bring the IME back to the foreground —
        // the picker activity stole focus and many hosts don't auto-resume the IME.
        LmStudioAiClient.setOnImageStagedListener(this::onEditImageStaged);
        root.strip().setOnPickListener(this::onSuggestionPicked);
        root.strip().setOnMicTapListener(this::toggleVoiceInput);
        root.strip().setOnPasteTapListener(this::pasteFromClipboard);
        root.strip().setOnSettingsTapListener(this::openHostDetailView);
        root.cmdSuggestions().setOnPickListener(this::onCommandSuggestionPicked);

        // Build the integration context off the freshly inflated views, then construct the
        // registry — its constructor pumps each integration's commands into the registry.
        Prefs prefs = new Prefs(this);
        appProfiles = new PersistentAppProfileRegistry(getApplicationContext(), prefs.root());
        // User-configurable per-pkg command pins. Read by the registry's ranker so user
        // overrides outrank the built-in affinity defaults.
        registry.setPins(new UserCommandPins(prefs.root().scoped("pins")));
        // One AiClient drives both the slash-command dispatcher and the module-side
        // LLM service — saves duplicate construction and keeps /notion talking to the
        // same backend as /cap, /fix, etc.
        LmStudioAiClient ai = new LmStudioAiClient(this, hostProvider, new StubAiClient());
        // Single shared Gemini client — modules call ctx.ai() with their own system
        // prompts. Replaced the legacy LlmService / ImageService composition; each
        // command now owns its prompt + dispatch in its own integration.
        com.prince.kbd.core.GeminiService gemini = new com.prince.ai.GeminiClient(
                com.prince.turtlekeyboard.BuildConfig.GEMINI_API_KEY);
        // Shared Google OAuth — every module that hits Google APIs (Split for Sheets/Drive,
        // Drive for /us reference photos, future Calendar / Gmail / Photos integrations)
        // reuses this single instance via ctx.googleAuth(). Storage in the "google" namespace
        // keeps token state consistent across modules and across host activities.
        com.prince.kbd.core.GoogleAuth googleAuth = new com.prince.kbd.core.GoogleAuthImpl(
                getApplicationContext(), prefs.root().scoped("google"));
        IntegrationContext integrationCtx = new KeyboardIntegrationContextImpl(
                getApplicationContext(), root, committer, prefs.root(), appProfiles,
                gemini, googleAuth);
        java.util.List<KeyboardIntegration> integrationList = java.util.Arrays.asList(
                new SplitIntegration(),
                new NotionIntegration(),
                new SlackIntegration(),
                new WebIntegration(),
                new DriveIntegration(),
                new PollIntegration(),
                new WyrIntegration());
        java.util.List<CommandProvider> builtins = java.util.Arrays.asList(new BuiltinAiCommands());
        integrations = new IntegrationRegistry(integrationList, builtins, integrationCtx, registry);

        // Replay shortcut registrations for every enrolled app. Runs after module
        // commands are registered, so per-app suggestions sit alongside (not on top of)
        // module-owned triggers like /split.
        enrolledShortcuts = new com.prince.turtlekeyboard.integration.EnrolledShortcutsManager(
                appProfiles, registry,
                new com.prince.turtlekeyboard.integration.StaticSuggestedShortcutSource());
        enrolledShortcuts.registerAllEnrolled();

        // /history — in-keyboard grid of generated images. Local-handler command
        // (no AI round trip); the lambda captures the IME so it can mount the
        // HistoryPanelView on the existing quickPanelHost slot.
        registry.register(new com.prince.kbd.core.CommandSpec(
                "history", "History", "🗂️", false,
                (prompt, ctx) -> showHistoryPanel()));

        // Dispatcher now takes the registry + a context provider so integration-contributed
        // slash commands can run locally without an AI round trip.
        dispatcher = new CommandDispatcher(
                ai, committer, this, registry, () -> integrationCtx);

        applyTheme();
        return root;
    }

    private void logHostContext(EditorInfo info) {
        if (info == null) {
            Log.d(SPLIT_TAG, "onStartInputView info=null");
            return;
        }
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        Log.d(SPLIT_TAG, "host pkg=" + info.packageName
                + " inputType=0x" + Integer.toHexString(info.inputType)
                + " class=0x" + Integer.toHexString(cls)
                + " variation=0x" + Integer.toHexString(variation)
                + " hint=" + info.hintText
                + " label=" + info.label
                + " field=" + info.fieldName);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputViewVisible = true;
        keyboard.setLayout(KeyboardController.Layout.QWERTY);
        shift.reset();
        root.banner().clear();
        root.preview().hide();
        currentPkg = info == null ? null : info.packageName;
        logHostContext(info);
        if (integrations != null) {
            integrations.onInputStart(info);
            // Field may already have text (e.g. user re-opened keyboard) — re-evaluate now.
            integrations.onTextChanged(committer.textBeforeCursor(16), committer.textAfterCursor(16));
        }
        maybeOfferEnrollment(info);
        refreshHostAppBadge(info);
        refreshSuggestions();
        // Surface the top bar with mic + (paste preview, when clipboard has text).
        root.strip().setVisibility(View.VISIBLE);
        root.strip().setPasteText(readClipboardText());
    }

    /** Wired to the center paste preview pill on the top bar. Commits the clipboard
     *  text directly to the focused field, then dismisses the pill so it doesn't
     *  re-fire on the next tap. */
    private void pasteFromClipboard() {
        String text = readClipboardText();
        if (text == null) return;
        committer.commitText(text);
        root.strip().setPasteText(null);
    }

    /** Wired to the leading hamburger button on the top bar. Mounts the more-options
     *  panel in the same slot the Quick Panel uses, replacing the keys. */
    private void openHostDetailView() {
        showMoreActionsPanel();
    }

    private void showMoreActionsPanel() {
        android.view.ViewGroup host = root.quickPanelHost();
        com.prince.turtlekeyboard.ime.view.MoreActionsPanelView panel =
                new com.prince.turtlekeyboard.ime.view.MoreActionsPanelView(this);
        panel.applyTheme(themes.current());
        panel.show(new com.prince.turtlekeyboard.ime.view.MoreActionsPanelView.Callbacks() {
            @Override public void onClose() { hideQuickPanel(); }
            @Override public void onAction(int actionId) { onMoreActionPicked(actionId); }
        });

        // Match the keyboard's measured height so the panel sits in the same band as the keys.
        android.inputmethodservice.KeyboardView keys = root.keyboardView();
        int targetHeight = keys.getHeight();
        host.removeAllViews();
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                targetHeight > 0 ? targetHeight : android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        host.addView(panel, lp);
        host.setVisibility(View.VISIBLE);
        keys.setVisibility(View.GONE);
    }

    private void onMoreActionPicked(int actionId) {
        // Hide the panel first; specific actions either commit text, launch an
        // activity, or surface another panel that handles its own visibility.
        hideQuickPanel();
        switch (actionId) {
            case com.prince.turtlekeyboard.ime.view.MoreActionsPanelView.ACTION_QUICK_PANEL:
                showQuickPanel();
                break;
            case com.prince.turtlekeyboard.ime.view.MoreActionsPanelView.ACTION_HISTORY:
                showHistoryPanel();
                break;
            case com.prince.turtlekeyboard.ime.view.MoreActionsPanelView.ACTION_SETTINGS:
                launchHostActivity();
                break;
            case com.prince.turtlekeyboard.ime.view.MoreActionsPanelView.ACTION_VOICE:
                toggleVoiceInput();
                break;
            case com.prince.turtlekeyboard.ime.view.MoreActionsPanelView.ACTION_THEME:
                cycleTheme();
                break;
            case com.prince.turtlekeyboard.ime.view.MoreActionsPanelView.ACTION_EMOJI:
                committer.commitText("😀");
                break;
            case com.prince.turtlekeyboard.ime.view.MoreActionsPanelView.ACTION_TRANSLATE:
                composer.enterPromptMode("tl");
                break;
            case com.prince.turtlekeyboard.ime.view.MoreActionsPanelView.ACTION_UNDO:
                committer.backspace();
                break;
            default:
                // No-op for unknown actions.
                break;
        }
    }

    private void launchHostActivity() {
        android.content.Intent i = new android.content.Intent(this, MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(i);
        } catch (android.content.ActivityNotFoundException ignored) {}
    }

    /** Round-robin through the available themes; re-applies immediately. */
    private void cycleTheme() {
        Prefs prefs = new Prefs(this);
        String current = prefs.getString(Prefs.KEY_THEME,
                com.prince.turtlekeyboard.theme.ThemeManager.AUTO);
        String next;
        switch (current) {
            case com.prince.turtlekeyboard.theme.ThemeManager.LIGHT:
                next = com.prince.turtlekeyboard.theme.ThemeManager.DARK; break;
            case com.prince.turtlekeyboard.theme.ThemeManager.DARK:
                next = com.prince.turtlekeyboard.theme.ThemeManager.AUTO; break;
            default:
                next = com.prince.turtlekeyboard.theme.ThemeManager.LIGHT; break;
        }
        prefs.putString(Prefs.KEY_THEME, next);
        applyTheme();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        inputViewVisible = false;
        if (integrations != null) integrations.onInputEnd();
        hideEnrollmentBanner();
        if (root != null && root.hostAppBadge() != null) root.hostAppBadge().hide();
    }

    // onFinishInputView misses some app-switch cases (the input session can stay
    // "open" while the IME window itself is gone). onWindowHidden/Shown is the
    // ground truth for "is the keyboard pixel-on-screen right now", which is the
    // signal we actually want for the image-ready notification fork.
    @Override
    public void onWindowShown() {
        super.onWindowShown();
        inputViewVisible = true;
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        inputViewVisible = false;
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
                                  int newSelStart, int newSelEnd,
                                  int candStart, int candEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candStart, candEnd);
        if (integrations != null && committer != null) {
            integrations.onTextChanged(committer.textBeforeCursor(16), committer.textAfterCursor(16));
        }
    }

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        if (committer.connection() == null) return;

        // Mic is global: works the same whether the user is mid-compose or
        // typing into the host editor. The sink picks the destination.
        if (primaryCode == Keycodes.MIC) {
            toggleVoiceInput();
            return;
        }
        if (primaryCode == Keycodes.EMOJI) {
            // Placeholder: commit a default smiley until the dedicated emoji panel ships.
            committer.commitText("😀");
            return;
        }

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

    /** One-tap dispatch from a preset chip in {@code /style} prompt mode — bypasses
     *  the typed prompt entirely so users can go selfie → Ghibli with a single tap.
     *  The AI client looks up the preset name in its style map and expands it to the
     *  full style instruction. */
    private void dispatchStylePreset(String preset) {
        if (preset == null || preset.isEmpty()) return;
        composer.cancel();
        SlashCommand cmd = new SlashCommand("style", preset, "/style " + preset);
        dispatcher.dispatchComposed(cmd);
    }

    /** One-tap dispatch from a preset chip in {@code /us} prompt mode — same shape as
     *  {@link #dispatchStylePreset}. The AI client looks up the preset key in its /us
     *  scenario map and expands it to the full scenario description sent alongside the
     *  user's reference selfies. */
    private void dispatchUsPreset(String preset) {
        if (preset == null || preset.isEmpty()) return;
        composer.cancel();
        SlashCommand cmd = new SlashCommand("us", preset, "/us " + preset);
        dispatcher.dispatchComposed(cmd);
    }

    private static final String NOTIF_CHANNEL_ID = "image_ready";
    private boolean notifChannelCreated;

    /** Posts a {@code BigPictureStyle} notification with the generated image when the
     *  keyboard is hidden at result time. Tapping the notification opens a system
     *  share sheet seeded with the image's FileProvider URI, so the user can drop
     *  it into whichever app they're now in. */
    private void notifyImageReady(java.io.File img, Uri uri) {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            Log.w("TurtleIME", "notify: NotificationManager null");
            return;
        }
        // Android 13+: POST_NOTIFICATIONS is a runtime permission. Without it the
        // notify() call below silently drops. MainActivity is responsible for asking
        // the user (we can't request runtime perms from a Service). Log explicitly
        // so the cause is visible in logcat instead of mysterious silence.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            int granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS);
            if (granted != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w("TurtleIME", "notify: POST_NOTIFICATIONS not granted — "
                        + "open the host app once so it can ask, or grant via App Settings");
                root.banner().showAndAutoHide(
                        "Notifications off — open Turtle to enable", 2500L);
                return;
            }
        }
        if (!notifChannelCreated && android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    NOTIF_CHANNEL_ID, "Image generation",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Notifies when /cap or /edit finishes while the keyboard is closed.");
            nm.createNotificationChannel(ch);
            notifChannelCreated = true;
            Log.d("TurtleIME", "notify: created channel " + NOTIF_CHANNEL_ID);
        }

        android.graphics.Bitmap big;
        try {
            big = android.graphics.BitmapFactory.decodeFile(img.getAbsolutePath());
        } catch (Exception e) {
            big = null;
        }

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("image/png");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(share, "Share image");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                this, 0, chooser,
                android.app.PendingIntent.FLAG_IMMUTABLE
                        | android.app.PendingIntent.FLAG_UPDATE_CURRENT);

        androidx.core.app.NotificationCompat.Builder b =
                new androidx.core.app.NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("Turtle: image ready 🐢")
                        .setContentText("Tap to share")
                        .setAutoCancel(true)
                        .setContentIntent(pi);
        if (big != null) {
            b.setLargeIcon(big);
            b.setStyle(new androidx.core.app.NotificationCompat.BigPictureStyle()
                    .bigPicture(big));
        }
        try {
            int id = (int) (System.currentTimeMillis() & 0x7fffffff);
            nm.notify(id, b.build());
            Log.d("TurtleIME", "notify: posted id=" + id);
        } catch (SecurityException e) {
            // Belt-and-suspenders — the explicit check above should have caught this,
            // but catch it anyway in case of OEM quirks. Image is still in history.
            Log.w("TurtleIME", "notify suppressed: " + e.getMessage());
        }
    }

    /** Called from the {@link LmStudioAiClient.OnImageStagedListener} after the
     *  picker delivers (or clears). Decodes a small thumbnail off the main thread,
     *  pushes it into the prompt panel, and asks the IME to re-show — the picker
     *  activity tore down focus and many host apps don't auto-resume the IME. */
    private void onEditImageStaged(@Nullable byte[] bytes, @Nullable String mime) {
        if (bytes == null) {
            root.post(() -> root.panel().setStagedImage(null, null));
            return;
        }
        // Decode at a small sample size — the panel slot is 32dp, no need for the
        // full-res bitmap.
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = 4;
        final android.graphics.Bitmap thumb =
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        root.post(() -> {
            root.panel().setStagedImage(thumb, () ->
                    LmStudioAiClient.stageEditImage(null, null));
            // requestShowSelf is a no-op if there's no focused field, but in the
            // common case (host EditText still focused, just covered by the picker)
            // it pulls the keyboard back up.
            requestShowSelf(0);
        });
    }

    /** Fires the system image picker via the transparent shim activity. The IME
     *  isn't an Activity, so we route through {@code ImagePickerActivity} which
     *  starts {@code ACTION_GET_CONTENT} and stages the picked bytes back into
     *  {@code LmStudioAiClient} for the next {@code /edit} dispatch to consume. */
    private void launchEditImagePicker() {
        // Drop any leftover staged image from a prior /edit session that exited
        // without dispatching. If the user cancels this picker, the static stays
        // null instead of resurrecting an old image.
        LmStudioAiClient.stageEditImage(null, null);
        android.content.Intent i = new android.content.Intent(this,
                com.prince.turtlekeyboard.ai.ImagePickerActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            android.util.Log.w("TurtleIME", "edit picker launch failed", e);
            root.banner().showAndAutoHide("Picker unavailable", 2000L);
        }
    }

    /** Best-effort read of the system clipboard's primary item as plain text. Returns
     *  null when there's nothing pasteable — the prompt panel hides its chip in that case. */
    @Nullable
    private String readClipboardText() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        android.content.ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence cs = clip.getItemAt(0).coerceToText(this);
        if (cs == null) return null;
        String s = cs.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private String hintFor(String commandName) {
        switch (commandName) {
            case "search": return "type a query or URL…";
            case "tl":     return "text to translate…";
            case "tone":   return "rewrite tone (e.g. formal)…";
            case "cap":    return "describe the image…";
            case "edit":   return "copy an image, then describe the edit…";
            case "style":  return "ghibli, anime, pixar, watercolor, lego…";
            case "sticker":return "describe the sticker…";
            case "web":    return "type a URL or search…";
            case "ask":    return "ask a question…";
            case "org":    return "what to organize…";
            case "fix":    return "tap → to fix grammar";
            case "reply":  return "tap → to draft a reply";
            case "splits": return "tap → to view history";
            default:
                CommandRegistry.Entry e = registry.get(commandName);
                return (e != null && !e.needsPrompt) ? "tap → to run" : "type and tap →";
        }
    }

    private boolean atWordBoundary() {
        CharSequence before = committer.textBeforeCursor(1);
        if (before.length() == 0) return true;
        char c = before.charAt(0);
        return Character.isWhitespace(c) || c == '\n';
    }

    private void emitChar(int code) {
        // Once the user starts typing, the clipboard preview is no longer the
        // foreground action — dismiss it so the suggestion slots reclaim the
        // center of the top bar.
        if (root != null && root.strip() != null) {
            root.strip().setPasteText(null);
        }
        char c = (char) code;
        if (Character.isLetter(c) && shift.isUpper()) c = Character.toUpperCase(c);
        // No auto-correction on space — suggestions are applied only when the
        // user taps a chip in the suggestion strip (onSuggestionPicked).
        committer.commitChar(c);
        if (code == Keycodes.SPACE) spaceGesture.onSpacePressed();
        // Word boundary just committed (space, punctuation, etc.) — record the
        // word that ended so personal vocabulary builds up over time.
        if (!Character.isLetter(c)) maybeLearnLastWord();
        shift.onCharCommitted();
        slashDetector.onTextChanged();
        refreshSuggestions();
    }

    /**
     * Looks back from the cursor, skips trailing whitespace, then walks the
     * preceding alpha run (with {@code '} and {@code -} allowed) and feeds it
     * to the suggestion engine.
     */
    private void maybeLearnLastWord() {
        if (suggestionEngine == null || committer == null) return;
        CharSequence before = committer.textBeforeCursor(64);
        if (before == null || before.length() == 0) return;
        int end = before.length();
        while (end > 0 && Character.isWhitespace(before.charAt(end - 1))) end--;
        int start = end;
        while (start > 0) {
            char ch = before.charAt(start - 1);
            if (!(Character.isLetter(ch) || ch == '\'' || ch == '-')) break;
            start--;
        }
        if (start < end) {
            suggestionEngine.learn(before.subSequence(start, end).toString());
        }
    }

    // -- Voice input ------------------------------------------------------

    private void toggleVoiceInput() {
        if (voice == null) return;
        if (!VoiceInputController.hasMicPermission(this)) {
            // IMEs cannot request runtime permissions directly; bounce the
            // user to MainActivity which holds the permission flow.
            root.banner().showAndAutoHide("Enable mic in Turtle app", 2000);
            try {
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.putExtra(MainActivity.EXTRA_REQUEST_MIC, true);
                startActivity(i);
            } catch (Exception ignored) {}
            return;
        }
        voice.toggle(voiceSink);
    }

    private final VoiceInputController.Sink voiceSink = new VoiceInputController.Sink() {
        @Override public void onListeningStarted() {
            root.banner().clear();
            // Mini banner-row bars are redundant once the full stage takes over —
            // keep one voice indicator on screen, not two.
            root.voiceListening().stop();
            // Lock the stage's height to whatever the keyboard is currently
            // occupying, then hide the keyboard. The LinearLayout sum stays
            // the same, so the IME window height does not change.
            int kh = root.keyboardView().getHeight();
            if (kh > 0) {
                android.view.ViewGroup.LayoutParams lp = root.voiceStage().getLayoutParams();
                lp.height = kh;
                root.voiceStage().setLayoutParams(lp);
            }

            // Mic stays visible in the strip throughout listening, so its
            // window-space centre is stable. The stage resolves the local
            // coordinate inside post() after layout settles, so passing
            // window coords here is safe even though we're about to swap
            // visibilities.
            View mic = root.strip().micButton();
            int[] micLoc = new int[2];
            mic.getLocationInWindow(micLoc);
            int micCx = micLoc[0] + mic.getWidth() / 2;
            int micCy = micLoc[1] + mic.getHeight() / 2;

            root.keyboardView().setVisibility(View.GONE);
            root.voiceStage().start(micCx, micCy);
        }
        @Override public void onListeningStopped() {
            root.voiceStage().stop();
            root.keyboardView().setVisibility(View.VISIBLE);
        }
        @Override public void onRms(float dB) { root.voiceStage().setRms(dB); }
        @Override public void onPartial(String text) {
            if (text != null && !text.isEmpty()) root.voiceStage().setTranscript(text);
        }
        @Override public void onFinal(String text) {
            if (text == null || text.isEmpty()) return;
            if (composer.isActive()) {
                // Route into whichever phase the composer is in (NAME / PROMPT).
                for (int i = 0; i < text.length(); i++) composer.appendChar(text.charAt(i));
            } else {
                committer.commitText(text);
                slashDetector.onTextChanged();
                refreshSuggestions();
            }
        }
        @Override public void onError(String userVisibleMessage) {
            root.voiceStage().stop();
            root.keyboardView().setVisibility(View.VISIBLE);
            root.banner().showAndAutoHide(userVisibleMessage, 1500);
        }
    };

    @Override
    public void onDestroy() {
        if (voice != null) { voice.destroy(); voice = null; }
        super.onDestroy();
    }

    private void refreshSuggestions() {
        if (suggestionProvider == null || committer == null || root == null) return;
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
        if (suggestionEngine != null) suggestionEngine.learn(suggestion);
        refreshSuggestions();
    }

    private void maybeOfferEnrollment(@Nullable EditorInfo info) {
        // Decline early under any condition where a personalization prompt would be wrong:
        // missing/system app, sensitive field (passwords, PIN, OTP), enrolled, suppressed.
        if (info == null || info.packageName == null) { hideEnrollmentBanner(); return; }
        String pkg = info.packageName;
        if (isSystemPackage(pkg)) { hideEnrollmentBanner(); return; }
        if (com.prince.split.EditorFieldHeuristics.looksSensitive(info)) {
            hideEnrollmentBanner(); return;
        }
        if (appProfiles == null) return;
        if (appProfiles.statusFor(pkg) != com.prince.kbd.core.AppProfileRegistry.Status.UNKNOWN) {
            hideEnrollmentBanner(); return;
        }

        com.prince.kbd.core.AppProfile profile = appProfiles.get(pkg);
        if (profile == null) { hideEnrollmentBanner(); return; }

        android.graphics.drawable.Drawable icon;
        try {
            icon = getApplicationContext().getPackageManager().getApplicationIcon(pkg);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            icon = null;
        }
        com.prince.turtlekeyboard.ime.view.AppEnrollmentBannerView b = root.enrollmentBanner();
        b.applyTheme(themes.current());
        b.show(icon, profile.displayName, new com.prince.turtlekeyboard.ime.view.AppEnrollmentBannerView.Listener() {
            @Override public void onAccept() {
                appProfiles.enroll(pkg);
                if (enrolledShortcuts != null) enrolledShortcuts.registerFor(pkg);
                hideEnrollmentBanner();
                root.banner().showAndAutoHide("Added " + profile.displayName, 1200L);
            }
            @Override public void onDismiss() {
                appProfiles.suppress(pkg);
                hideEnrollmentBanner();
            }
        });
    }

    private void hideEnrollmentBanner() {
        if (root != null && root.enrollmentBanner() != null) root.enrollmentBanner().hide();
    }

    private void refreshHostAppBadge(@Nullable EditorInfo info) {
        // Show only for apps the user has *mapped* (auto-enrolled seed apps + user-enrolled
        // apps). Skip system surfaces and sensitive fields so the badge never leaks
        // "we know where you are" into a password screen.
        if (info == null || info.packageName == null) { root.hostAppBadge().hide(); return; }
        String pkg = info.packageName;
        if (isSystemPackage(pkg)) { root.hostAppBadge().hide(); return; }
        if (com.prince.split.EditorFieldHeuristics.looksSensitive(info)) {
            root.hostAppBadge().hide(); return;
        }
        if (appProfiles == null
                || appProfiles.statusFor(pkg) != com.prince.kbd.core.AppProfileRegistry.Status.ENROLLED) {
            root.hostAppBadge().hide();
            return;
        }
        android.graphics.drawable.Drawable icon;
        try {
            icon = getApplicationContext().getPackageManager().getApplicationIcon(pkg);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            icon = null;
        }
        if (icon == null) { root.hostAppBadge().hide(); return; }
        root.hostAppBadge().show(icon);
    }

    private static boolean isSystemPackage(String pkg) {
        // Only filter true system surfaces (system UI, settings, launcher, IMEs and our
        // own host) — *not* arbitrary "com.android.*" packages, since Chrome ships as
        // com.android.chrome and several Google apps use that prefix.
        if (pkg.equals("com.prince.turtlekeyboard")) return true;
        if (pkg.equals("com.android.systemui")) return true;
        if (pkg.equals("com.android.settings")) return true;
        if (pkg.equals("com.android.launcher") || pkg.equals("com.android.launcher3")) return true;
        if (pkg.equals("com.google.android.apps.nexuslauncher")) return true;
        if (pkg.startsWith("com.android.inputmethod.")) return true;
        if (pkg.equals("com.google.android.inputmethod.latin")) return true;
        return false;
    }

    private void onDoubleTapSpace() {
        // The two space chars were already committed by emitChar before the double-tap
        // detector saw the gesture — undo them so the host editor doesn't keep stray
        // spaces from a pure-gesture invocation.
        committer.deleteBeforeCursor(2);
        slashDetector.onTextChanged();
        refreshSuggestions();
        // PRD §6.6: double-tap-space toggles the Quick Panel — a 2-col grid of slash
        // commands that *replaces* the key area.
        if (isQuickPanelVisible()) {
            hideQuickPanel();
            return;
        }
        showQuickPanel();
    }

    private boolean isQuickPanelVisible() {
        return root.quickPanelHost().getVisibility() == View.VISIBLE;
    }

    private void showQuickPanel() {
        android.view.ViewGroup host = root.quickPanelHost();
        com.prince.turtlekeyboard.ime.view.QuickPanelView panel =
                new com.prince.turtlekeyboard.ime.view.QuickPanelView(this);
        panel.applyTheme(themes.current());
        // Re-query the current host package right before ranking — onStartInputView
        // stamps `currentPkg` but Android can re-enter the input view on some redraws
        // without re-firing it, leaving the field stale. Asking for live info here
        // guarantees /notion + /slack-affine commands hoist correctly in Slack, etc.
        EditorInfo liveInfo = getCurrentInputEditorInfo();
        if (liveInfo != null && liveInfo.packageName != null) currentPkg = liveInfo.packageName;
        panel.show(registry.allSortedFor(currentPkg), this::onQuickPanelPick, this::hideQuickPanel);

        // Match the keyboard's measured height so the grid sits in the same vertical band
        // the keys occupied — no jump in IME height when toggling.
        android.inputmethodservice.KeyboardView keys = root.keyboardView();
        int targetHeight = keys.getHeight();
        host.removeAllViews();
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                targetHeight > 0 ? targetHeight : android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        host.addView(panel, lp);
        host.setVisibility(View.VISIBLE);
        keys.setVisibility(View.GONE);
    }

    private void hideQuickPanel() {
        root.quickPanelHost().removeAllViews();
        root.quickPanelHost().setVisibility(View.GONE);
        root.keyboardView().setVisibility(View.VISIBLE);
    }

    /** Mounts the {@link com.prince.turtlekeyboard.ime.view.HistoryPanelView} in
     *  the same slot the Quick Panel uses, replacing the keys. Tap on a thumbnail
     *  fires {@link #insertHistoryImage} which uses {@code commitContent} to drop
     *  the image into the host field directly (clipboard fallback for hosts that
     *  don't accept inline images). */
    private void showHistoryPanel() {
        android.view.ViewGroup host = root.quickPanelHost();
        com.prince.turtlekeyboard.ime.view.HistoryPanelView panel =
                new com.prince.turtlekeyboard.ime.view.HistoryPanelView(this);
        panel.applyTheme(themes.current());
        panel.show(com.prince.turtlekeyboard.ai.ImageHistory.list(this),
                this::insertHistoryImage,
                this::hideQuickPanel);

        android.inputmethodservice.KeyboardView keys = root.keyboardView();
        int targetHeight = keys.getHeight();
        host.removeAllViews();
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                targetHeight > 0 ? targetHeight : android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        host.addView(panel, lp);
        host.setVisibility(View.VISIBLE);
        keys.setVisibility(View.GONE);
    }

    /** Inserts a history image into the host field. Mirrors the share path used by
     *  the result preview, but skips the format picker — history files are already
     *  PNGs that hosts overwhelmingly accept. */
    private void insertHistoryImage(java.io.File file) {
        if (file == null || !file.exists()) {
            root.banner().showAndAutoHide("File missing", 1500L);
            return;
        }
        Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", file);
        hideQuickPanel();
        if (!insertImage(uri, "image/png")) copyToClipboard(uri, "image/png");
    }

    private void refreshCommandSuggestions(String displayed) {
        // displayed is the composer NAME buffer including the leading "/". Wait for at
        // least one letter after the slash before showing the strip — typing "/" alone
        // shouldn't dump every registered command on the user.
        String prefix = displayed == null || displayed.isEmpty() || displayed.charAt(0) != '/'
                ? "" : displayed.substring(1);
        if (prefix.isEmpty()) {
            root.cmdSuggestions().hide();
            return;
        }
        // Affinity ranking: per-app shortcuts (like /standup in Slack) sit ahead of
        // generic ones when both prefix-match.
        root.cmdSuggestions().show(registry.matchesFor(prefix, currentPkg));
    }

    private void onCommandSuggestionPicked(CommandRegistry.Entry entry) {
        // No-prompt commands have nothing for the user to type or confirm — fire
        // immediately on pick so e.g. /history doesn't need an extra Go-tap. The
        // host editor never sees "/<name>" either way.
        if (!entry.needsPrompt) {
            composer.cancel();
            dispatcher.dispatchComposed(
                    new SlashCommand(entry.name, "", "/" + entry.name));
            return;
        }
        composer.enterPromptMode(entry.name);
    }

    private void onQuickPanelPick(CommandRegistry.Entry entry) {
        hideQuickPanel();
        if (!entry.needsPrompt) {
            composer.cancel();
            dispatcher.dispatchComposed(
                    new SlashCommand(entry.name, "", "/" + entry.name));
            return;
        }
        composer.enterPromptMode(entry.name);
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
        // Trailing "…" is CommandDispatcher's loading marker — show the dark
        // gradient loader panel (with the status text) until a terminal result
        // arrives. Any other status (errors, completions) hides it and falls
        // through to the transient banner.
        if (message != null && message.endsWith("…")) {
            root.generatingLoader().show(message);
            root.shimmer().stop();
            return;
        }
        root.generatingLoader().hide();
        root.shimmer().stop();
        root.banner().showAndAutoHide(message, 2000L);
    }

    @Override public void showSuggestions(String[] suggestions) {
        root.shimmer().stop();
        root.generatingLoader().hide();
        root.strip().setSuggestions(java.util.Arrays.asList(suggestions));
    }

    @Override public void showImage(String imagePayload) {
        root.shimmer().stop();
        root.generatingLoader().hide();
        if (imagePayload == null || imagePayload.isEmpty()) {
            root.banner().showAndAutoHide("Empty result", 1500L);
            return;
        }
        java.io.File source;
        Uri uri;
        int sep = imagePayload.indexOf('|');
        if (sep > 0) {
            uri = Uri.parse(imagePayload.substring(0, sep));
            source = new java.io.File(imagePayload.substring(sep + 1));
        } else {
            // No path provided — can't preview. Future remote-image commands will need
            // to download to cache before reaching here.
            root.banner().showAndAutoHide("No local preview available", 2000L);
            return;
        }
        root.banner().clear();
        if (!source.exists()) {
            root.banner().showAndAutoHide("Preview file missing", 2000L);
            return;
        }
        // Keyboard isn't on screen (user switched apps mid-generation) — fall back to
        // a notification so the result isn't silently dropped.
        Log.d("TurtleIME", "showImage visible=" + inputViewVisible + " path="
                + (inputViewVisible ? "preview" : "notification"));
        if (!inputViewVisible) {
            notifyImageReady(source, uri);
            return;
        }
        boolean ok = root.preview().show(source, new com.prince.turtlekeyboard.ime.view.ImagePreviewView.Listener() {
            @Override public void onShare(com.prince.turtlekeyboard.ai.ImageVariants.Type type) {
                root.preview().hide();
                shareAs(source, type);
            }
            @Override public void onCancel() {
                root.preview().hide();
            }
        });
        if (!ok) root.banner().showAndAutoHide("Preview decode failed", 2000L);
    }

    /** Encode the source PNG into the user-picked format, then commitContent (or
     *  fall back to clipboard) under the appropriate MIME. */
    private void shareAs(java.io.File source, com.prince.turtlekeyboard.ai.ImageVariants.Type type) {
        com.prince.turtlekeyboard.ai.ImageVariants.Variant v;
        try {
            java.io.File outDir = new java.io.File(getCacheDir(), "shared_images");
            v = com.prince.turtlekeyboard.ai.ImageVariants.make(source, type, outDir);
        } catch (Exception e) {
            root.banner().showAndAutoHide("Encode failed: " + e.getMessage(), 2500L);
            return;
        }
        Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", v.file);
        if (!insertImage(uri, v.mime)) copyToClipboard(uri, v.mime);
    }

    /**
     * Tries {@link InputConnectionCompat#commitContent} so apps that accept the chosen
     * MIME (Gmail, WhatsApp, Messages, …) receive the file inline. Returns false if
     * the host field doesn't advertise that MIME — caller falls back to clipboard.
     */
    private boolean insertImage(Uri uri, String mime) {
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null || info == null) return false;
        String[] mimes = androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(info);
        boolean accepts = false;
        for (String m : mimes) {
            if (ClipDescription.compareMimeTypes(m, mime)) { accepts = true; break; }
        }
        if (!accepts) return false;
        InputContentInfoCompat content = new InputContentInfoCompat(
                uri, new ClipDescription("turtle", new String[]{mime}), null);
        int flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION;
        boolean ok = InputConnectionCompat.commitContent(ic, info, content, flags, null);
        if (ok) root.banner().showAndAutoHide("Inserted 🐢", 1500L);
        return ok;
    }

    private void copyToClipboard(Uri uri, String mime) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            root.banner().showAndAutoHide("Clipboard unavailable", 2000L);
            return;
        }
        ClipData clip = new ClipData(new ClipDescription("Turtle", new String[]{mime}),
                new ClipData.Item(uri));
        cm.setPrimaryClip(clip);
        root.banner().showAndAutoHide("Copied — long-press → Paste 📋", 2500L);
    }

    @Override public void clearStatus() {
        root.shimmer().stop();
        root.generatingLoader().hide();
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
