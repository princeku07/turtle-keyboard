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
import android.text.TextUtils;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.ai.AiClient;
import com.prince.turtlekeyboard.ai.OnDeviceAiClient;
import com.prince.turtlekeyboard.ai.TurtleAiClient;
import com.prince.turtlekeyboard.ai.StubAiClient;
import com.prince.turtlekeyboard.input.InputTarget;
import com.prince.turtlekeyboard.command.CommandComposer;
import com.prince.turtlekeyboard.command.CommandDispatcher;
import com.prince.turtlekeyboard.command.CommandRegistry;
import com.prince.turtlekeyboard.command.SlashCommand;
import com.prince.turtlekeyboard.command.SlashCommandDetector;
import com.prince.turtlekeyboard.gesture.SpaceGestureHandler;
import com.prince.turtlekeyboard.ime.view.KeyPreviewPopup;
import com.prince.turtlekeyboard.ime.view.KeyboardRootView;
import com.prince.turtlekeyboard.ime.view.TurtleKeyboardView;
import com.prince.notion.NotionIntegration;
import com.prince.slack.SlackIntegration;
import com.prince.split.SplitIntegration;
import com.prince.turtlekeyboard.integration.drive.DriveIntegration;
import com.prince.turtlekeyboard.integration.poll.PollIntegration;
import com.prince.turtlekeyboard.integration.puzzle.PuzzleIntegration;
import com.prince.turtlekeyboard.integration.usermcp.UserMcpIntegration;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Thin IME orchestrator. Wires the bound view to a {@link KeyboardController}, routes
 * key events through {@link ShiftController} + {@link InputCommitter}, and forwards
 * completed text to the slash-command pipeline.
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
    // Snapshot of the host pkg at command-fire time so the notification can offer
    // "Share to <originating app>" even if the keyboard is gone by result time.
    @Nullable private String pendingSourcePkg;
    private com.prince.turtlekeyboard.integration.PersistentAppProfileRegistry appProfiles;
    private com.prince.turtlekeyboard.integration.EnrolledShortcutsManager enrolledShortcuts;
    private SuggestionProvider suggestionProvider;
    private SuggestionEngine suggestionEngine;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bitmapIo = Executors.newSingleThreadExecutor();
    /** Background thread for the per-keystroke suggest pipeline. Daemon + MIN_PRIORITY
     *  so it never preempts the IME's input dispatch. */
    private final ExecutorService suggestionExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TurtleSuggest");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });
    /** Incremented on every refresh request; the background callback drops its result
     *  if the value moved on while it was computing — keeps the strip in sync with
     *  the latest keystroke under fast typing without a queue blowout. */
    private int suggestionVersion;
    @Nullable private Future<?> pendingSuggestionFuture;
    /** Cached InputType flags (CAP_SENTENCES / CAP_WORDS / CAP_CHARACTERS) for the
     *  currently-bound editor. Drives the auto-cap shift decision in
     *  {@link #updateAutoCap(CharSequence)}. */
    private int autoCapMode;
    /** Active editor action (IME_ACTION_SEARCH / SEND / GO / NEXT / DONE / PREVIOUS),
     *  or 0 when the field expects a plain newline. Set on every onStartInputView. */
    private int editorAction;
    /** Tracks consecutive DELETE keys during a single hold. Past
     *  {@link #DELETE_CHARS_BEFORE_WORD_MODE} repeats we switch from char-delete
     *  to whole-word delete so long backspaces don't crawl character-by-character.
     *  Reset on every fresh DELETE press in {@link #onPress(int)}. */
    private int deleteRepeatCount;
    private static final int DELETE_CHARS_BEFORE_WORD_MODE = 5;
    /** Per-package Drawable cache. {@link android.content.pm.PackageManager#getApplicationIcon}
     *  is 5–15ms; we'd previously pay that on every focus change for enrolled apps and on
     *  every enrollment-banner consideration for unknown apps. */
    private final java.util.Map<String, android.graphics.drawable.Drawable> iconCache =
            new java.util.HashMap<>();
    // SPI image-pick routing: each pickImage() allocates an id and parks its callback here.
    private final java.util.concurrent.atomic.AtomicInteger pickerReqIds =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.Map<Integer, com.prince.kbd.core.IntegrationContext.ImagePickCallback>
            pickerCallbacks = new java.util.concurrent.ConcurrentHashMap<>();
    private ThemeManager themes;
    private AudioManager audio;
    /** Single Prefs instance held by the service so onPress and friends don't allocate
     *  a fresh wrapper on each access. The underlying SharedPreferences read is a
     *  HashMap lookup — cheap enough that toggling sound/haptics in settings takes
     *  effect on the next keypress, no focus bounce required. */
    private Prefs prefs;
    private KeyPreviewPopup preview;
    private KeyboardView keyboardView;
    private VoiceInputController voice;
    private IntegrationRegistry integrations;
    private TurtleAiClient aiClient;
    private OnDeviceAiClient onDeviceAi;
    /** True between onStartInputView and onFinishInputView; results route to a notification when false. */
    private boolean inputViewVisible;

    /** Re-shows the IME once the picker shim activity finishes and the editor re-binds. */
    private boolean pendingShowAfterPick;

    /** Set when commitContent fails mid-async (commonly /gif) so a tap-to-insert chip can retry. */
    @Nullable private Uri pendingInsertUri;
    @Nullable private String pendingInsertMime;

    @Nullable private com.prince.turtlekeyboard.ime.view.EmojiPanelView activeEmojiPanel;
    /** Lazy-constructed; reused across show/hide cycles. Mounted into {@link KeyboardRootView#panelHost()}. */
    @Nullable private com.prince.turtlekeyboard.ime.view.AiAssistPanelView aiAssistPanel;
    /** Single slot for the four key-band panels (Quick / Emoji / History / MoreActions). */
    @Nullable private com.prince.turtlekeyboard.ime.view.PanelSlot keyAreaPanel;
    /** Single slot for the focused input-owning component (AI prompt field, emoji search, …).
     *  Updated by {@link #inputTargetWatcher}; consulted in onKey + voiceSink.onFinal. */
    @Nullable private InputTarget activeInputTarget;
    /** Wired to every InputTarget panel — swaps the slot when any panel enters/exits its input mode. */
    private final InputTarget.ActiveChangeListener inputTargetWatcher =
            (target, active) -> activeInputTarget = active ? target : null;

    /** Façade handed to {@link com.prince.turtlekeyboard.command.PromptDecorator}s so they can
     *  drive the preset strips without depending on view internals. */
    private final com.prince.turtlekeyboard.command.PromptDecorator.Ui promptUi =
            new com.prince.turtlekeyboard.command.PromptDecorator.Ui() {
                @Override public void showTextPresets(
                        List<String> presets, java.util.function.Consumer<String> onPick) {
                    root.stylePreviewStrip().hide();
                    root.presetStrip().setPresets(presets, onPick::accept);
                }
                @Override public void showImagePreviewPresets(
                        List<String> presets, java.util.function.Consumer<String> onPick) {
                    root.presetStrip().hide();
                    root.stylePreviewStrip().setPresets(presets, onPick::accept);
                }
                @Override public void hidePresets() {
                    root.presetStrip().hide();
                    root.stylePreviewStrip().hide();
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        // Start dictionary load on service create (not view inflate) for a populated first strip.
        suggestionEngine = new SuggestionEngine(this);
        suggestionEngine.loadAsync(this);
        // Page Gemini Nano in early so the first /assist tap doesn't pay the cold-start.
        onDeviceAi = new OnDeviceAiClient(this);
        onDeviceAi.warmup();
    }

    @Override
    public View onCreateInputView() {
        themes = new ThemeManager(this);
        root = (KeyboardRootView) View.inflate(this, R.layout.keyboard_view, null);

        KeyboardView kv = root.keyboardView();
        // Stale activeInputTarget from a prior view tree would silently swallow DELETE.
        activeInputTarget = null;
        activeEmojiPanel = null;
        aiAssistPanel = null;
        keyAreaPanel = new com.prince.turtlekeyboard.ime.view.PanelSlot(
                root.quickPanelHost(), kv);
        // Panels removed by replacement (or hide) don't run their own exit-input-mode
        // path, so their input target reference would leak — clear it on every unmount.
        keyAreaPanel.setOnUnmount(() -> activeInputTarget = null);
        keyboard = new KeyboardController(this);
        keyboard.attach(kv);
        shift = new ShiftController();
        shift.attach(kv);
        kv.setOnKeyboardActionListener(this);
        // Framework preview drifts when KeyboardView isn't the IME root; we use a custom popup.
        kv.setPreviewEnabled(false);
        kv.setHapticFeedbackEnabled(true);
        if (kv instanceof TurtleKeyboardView) {
            TurtleKeyboardView tkv = (TurtleKeyboardView) kv;
            tkv.setModeKeyLongPressListener(() -> {
                KeyboardController.Layout target = keyboard.active() == KeyboardController.Layout.DIALPAD
                        ? KeyboardController.Layout.QWERTY
                        : KeyboardController.Layout.DIALPAD;
                keyboard.setLayout(target);
                if (prefs == null || prefs.getBool(Prefs.KEY_HAPTICS, true)) {
                    kv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
            });
        }
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
                root.panel().show(label, hintFor(commandName), "", 0);
                // Strip paste chip stays visible in prompt mode; pasteFromClipboard routes to the composer.
                root.panel().setPasteAvailable(readClipboardText());
                refreshPromptSuggestions("", 0);

                switch (registry.imagePickerFor(commandName)) {
                    case EDIT:
                        root.panel().setUploadAction(
                                TurtleInputMethodService.this::launchEditImagePicker);
                        launchEditImagePicker();
                        break;
                    case US:
                        root.panel().setUploadAction(
                                TurtleInputMethodService.this::launchUsImagePicker);
                        launchUsImagePicker();
                        break;
                    case NONE:
                        break;
                }

                com.prince.turtlekeyboard.command.PromptDecorator dec =
                        registry.promptDecoratorFor(commandName);
                if (dec != null) dec.onStart(promptUi);
                else promptUi.hidePresets();
            }
            @Override public void onPromptChanged(String commandName, String query, int cursorPos) {
                root.panel().update(query, cursorPos);
                refreshPromptSuggestions(query, cursorPos);
                com.prince.turtlekeyboard.command.PromptDecorator dec =
                        registry.promptDecoratorFor(commandName);
                if (dec != null) dec.onQueryChanged(promptUi, query);
            }
            @Override public void onComposeEnd() {
                root.banner().clear();
                root.cmdSuggestions().hide();
                root.panel().hide();
                promptUi.hidePresets();
                root.strip().setPasteText(readClipboardText());
                refreshSuggestions();
                // Don't clear the staged image here — onComposeEnd fires before dispatch sees it.
            }
        });
        // HostProvider hands the IME's decor to the HTML→image renderer so the WebView attaches to a real window.
        TurtleAiClient.HostProvider hostProvider = () -> {
            android.view.Window w = getWindow() == null ? null : getWindow().getWindow();
            View decor = w == null ? null : w.getDecorView();
            return decor instanceof android.view.ViewGroup ? (android.view.ViewGroup) decor : null;
        };
        if (suggestionEngine == null) {
            suggestionEngine = new SuggestionEngine(this);
            suggestionEngine.loadAsync(this);
        }
        suggestionProvider = new SymSpellSuggestionProvider(suggestionEngine);
        // Repaint the strip on dictionary load; callback fires on the load thread, hop to main.
        suggestionEngine.setOnReadyListener(
                () -> mainHandler.post(this::refreshSuggestions));
        voice = new VoiceInputController(this);
        root.voiceStage().setListener(() -> { if (voice != null) voice.stop(); });

        root.panel().setOnGoListener(this::dispatchPromptPanel);
        root.panel().setOnPasteListener(text -> composer.appendString(text));
        root.panel().setOnCursorMoveListener(pos -> {
            composer.setPromptCursor(pos);
        });
        // Picker activity stole focus; we re-show the IME and reflect the staged image in the panel.
        stagingPipeline().setEditListener(this::onEditImageStaged);
        stagingPipeline().setUsListener(this::onUsImagesStaged);
        root.strip().setOnPickListener(this::onSuggestionPicked);
        root.strip().setOnMicTapListener(this::toggleVoiceInput);
        root.strip().setOnPasteTapListener(this::pasteFromClipboard);
        root.strip().setOnSettingsTapListener(this::openHostDetailView);
        root.strip().setOnEmojiTapListener(this::toggleEmojiPanel);
        root.strip().setOnSparkleTapListener(this::showAiAssistPanel);
        root.cmdSuggestions().setOnPickListener(this::onCommandSuggestionPicked);
        root.cmdSuggestions().setOnDismissListener(this::dismissCommandSuggestions);

        appProfiles = new PersistentAppProfileRegistry(getApplicationContext(), prefs.root());
        // User pins outrank built-in affinity defaults in the registry ranker.
        registry.setPins(new UserCommandPins(prefs.root().scoped("pins")));
        if (integrations != null) { integrations.shutdown(); integrations = null; }
        if (aiClient != null) { aiClient.destroy(); aiClient = null; }
        // GeminiService is the single AI seam; MOCK_AI swaps the live client
        // for MockGeminiService so /sticker, /gif, /gift, /cap and /edit all
        // hit the bundled gg.png fixture without burning Gemini quota. Same
        // instance flows into both the integrations (via IntegrationContext)
        // and TurtleAiClient (constructor) so one toggle covers every path.
        com.prince.kbd.core.GeminiService gemini =
                com.prince.turtlekeyboard.BuildConfig.MOCK_AI
                        ? new com.prince.turtlekeyboard.ai.MockGeminiService(this)
                        : new com.prince.ai.GeminiClient(
                                com.prince.turtlekeyboard.BuildConfig.GEMINI_API_KEY);
        TurtleAiClient ai = new TurtleAiClient(
                this, hostProvider, stagingPipeline(), gemini, new StubAiClient());
        aiClient = ai;
        com.prince.kbd.core.McpService mcp = new com.prince.ai.McpClient();
        com.prince.kbd.core.GoogleAuth googleAuth = new com.prince.kbd.core.GoogleAuthImpl(
                getApplicationContext(), prefs.root().scoped("google"));
        com.prince.turtlekeyboard.integration.ImageBridge imageBridge =
                new com.prince.turtlekeyboard.integration.ImageBridge() {
                    @Override
                    public void pickImage(
                            com.prince.kbd.core.IntegrationContext.ImagePickCallback cb) {
                        int id = pickerReqIds.incrementAndGet();
                        pickerCallbacks.put(id, cb);
                        launchSpiImagePicker(id);
                    }

                    @Override
                    public void commitImage(android.net.Uri uri, String mime) {
                        // Async pipelines can land after focus has drifted; fall back to clipboard + insert chip.
                        if (!insertImage(uri, mime)) {
                            copyToClipboard(uri, mime);
                            offerInsertChip(uri, mime);
                        }
                    }
                };
        // SPI-routed picks (ImagePickerActivity → PickerResultBus). Re-shows IME and defers callback to a settled tree.
        com.prince.turtlekeyboard.ai.PickerResultBus.setListener(
                (reqId, bytes, mime) -> mainHandler.post(() -> {
                    final com.prince.kbd.core.IntegrationContext.ImagePickCallback cb =
                            pickerCallbacks.remove(reqId);
                    if (cb == null) {
                        android.util.Log.w("TurtleIME", "SPI picker delivery: no callback for reqId=" + reqId);
                        return;
                    }
                    android.util.Log.i("TurtleIME", "SPI picker delivery: reqId=" + reqId
                            + " bytes=" + (bytes == null ? "null" : bytes.length));

                    // Try once immediately; the helper covers the case where input isn't ready yet.
                    requestShowSelf(0);
                    requestShowSelfAfterPick();

                    // Defer the callback so ctx.showPanel lands on a settled view tree.
                    root.post(() -> {
                        android.util.Log.i("TurtleIME", "SPI picker firing onPicked, panelHost visibility="
                                + root.panelHost().getVisibility());
                        cb.onPicked(bytes == null
                                ? null
                                : new com.prince.kbd.core.IntegrationContext.PickedImage(bytes, mime));
                        android.util.Log.i("TurtleIME", "SPI picker after onPicked, panelHost children="
                                + root.panelHost().getChildCount()
                                + " visibility=" + root.panelHost().getVisibility());
                    });
                }));
        IntegrationContext integrationCtx = new KeyboardIntegrationContextImpl(
                getApplicationContext(), root, committer, prefs.root(), appProfiles,
                gemini, mcp, googleAuth, imageBridge);
        java.util.List<KeyboardIntegration> integrationList = java.util.Arrays.asList(
                // PuzzleIntegration first: its activate(...) re-mounts a pending /puzzle panel
                // wiped when the picker stole focus.
                new PuzzleIntegration(),
                new SplitIntegration(),
                new NotionIntegration(),
                new SlackIntegration(),
                new WebIntegration(),
                new DriveIntegration(),
                new PollIntegration(),
                new WyrIntegration(),
                new com.prince.turtlekeyboard.integration.gif.GifIntegration(),
                new com.prince.turtlekeyboard.integration.sticker.StickerIntegration(),
                // UserMcpIntegration last so user bindings can't shadow built-in commands.
                new UserMcpIntegration(integrationCtx));
        java.util.List<CommandProvider> builtins = java.util.Arrays.asList(new BuiltinAiCommands());
        integrations = new IntegrationRegistry(integrationList, builtins, integrationCtx, registry);

        enrolledShortcuts = new com.prince.turtlekeyboard.integration.EnrolledShortcutsManager(
                appProfiles, registry,
                new com.prince.turtlekeyboard.integration.StaticSuggestedShortcutSource());
        enrolledShortcuts.registerAllEnrolled();

        // /history is a local-handler command (no AI round trip).
        registry.register(new com.prince.kbd.core.CommandSpec(
                "history", "History", "🗂️", false,
                (prompt, ctx) -> showHistoryPanel()));

        com.prince.turtlekeyboard.command.BuiltinPromptUi.register(
                registry, this::dispatchStylePreset, this::dispatchUsPreset);

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
        syncCapsLockIndicator();
        root.banner().clear();
        root.preview().hide();
        // Re-opening the keyboard always lands on the keys. The IME service reuses
        // the inflated view across focus cycles, so any panel the user left open
        // (Quick / Emoji / History / MoreActions / AI assist) persists into the
        // next editor unless we explicitly dismiss it here.
        if (isAiAssistPanelVisible()) hideAiAssistPanel();
        if (keyAreaPanel != null) keyAreaPanel.hide();
        activeEmojiPanel = null;
        activeInputTarget = null;
        currentPkg = info == null ? null : info.packageName;
        autoCapMode = info == null ? 0 : (info.inputType
                & (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS));
        editorAction = computeEditorAction(info);
        if (keyboardView instanceof TurtleKeyboardView) {
            ((TurtleKeyboardView) keyboardView).setEditorAction(editorAction);
        }
        updateAutoCapFromHost();
        logHostContext(info);
        if (integrations != null) {
            integrations.onInputStart(info);
            // Re-evaluate in case the field already has text from a prior session.
            integrations.onTextChanged(committer.textBeforeCursor(16), committer.textAfterCursor(16));
        }
        maybeOfferEnrollment(info);
        refreshHostAppBadge(info);
        refreshSuggestions();
        root.strip().setVisibility(View.VISIBLE);
        root.strip().setPasteText(readClipboardText());
        refreshSparkleVisibility();
    }

    private void pasteFromClipboard() {
        String text = readClipboardText();
        if (text == null) return;
        if (composer != null && composer.isActive()
                && composer.mode() == com.prince.turtlekeyboard.command.CommandComposer.Mode.PROMPT) {
            composer.appendString(text);
        } else {
            committer.commitText(text);
        }
        root.strip().setPasteText(null);
    }

    private void openHostDetailView() {
        showMoreActionsPanel();
    }

    private void showMoreActionsPanel() {
        com.prince.turtlekeyboard.ime.view.MoreActionsPanelView panel =
                new com.prince.turtlekeyboard.ime.view.MoreActionsPanelView(this);
        panel.applyTheme(themes.current());
        panel.show(new com.prince.turtlekeyboard.ime.view.MoreActionsPanelView.Callbacks() {
            @Override public void onClose() { hideQuickPanel(); }
            @Override public void onAction(int actionId) { onMoreActionPicked(actionId); }
        });
        keyAreaPanel.show(panel);
    }

    private void onMoreActionPicked(int actionId) {
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
        if (isAiAssistPanelVisible()) hideAiAssistPanel();
        // Persist accrued user-word bumps before the IME backgrounds.
        if (suggestionEngine != null) suggestionEngine.flush();
    }

    // onWindowShown/Hidden is the ground truth for keyboard-on-screen; onFinishInputView misses some app-switch cases.
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
        refreshSparkleVisibility();
        // Cursor moved (tap-to-position, host-side edit, paste) — re-evaluate auto-cap
        // against the new context. getCapsMode + 64-char IPC is sub-ms, no need to debounce.
        updateAutoCapFromHost();
    }

    /** Debounce for the cursor-driven sparkle visibility check. 200 ms is below user
     *  perception for a static icon and collapses bursty {@code onUpdateSelection}
     *  storms (cursor scrubbing, IME-driven recompositions) into one IPC. */
    private static final long SPARKLE_DEBOUNCE_MS = 200L;
    private final Runnable sparkleRunnable = this::applySparkleVisibility;

    /** Shows the ✨ AI-assist trigger when the field has >1 word. Debounced — fires
     *  one IPC after the cursor has been still for {@link #SPARKLE_DEBOUNCE_MS}. */
    private void refreshSparkleVisibility() {
        mainHandler.removeCallbacks(sparkleRunnable);
        mainHandler.postDelayed(sparkleRunnable, SPARKLE_DEBOUNCE_MS);
    }

    private void applySparkleVisibility() {
        if (root == null || committer == null) return;
        CharSequence before = committer.textBeforeCursor(64);
        CharSequence after = committer.textAfterCursor(64);
        root.strip().setSparkleVisible(hasTwoWords(before, after));
    }

    /** True once a second word-character is found across {@code before + after}.
     *  Short-circuits on the second boundary so a long field doesn't pay for a
     *  full scan. Cheaper than {@code String.split("\\s+")} which allocates a
     *  regex, an array, and walks the whole string. */
    private static boolean hasTwoWords(CharSequence before, CharSequence after) {
        int words = 0;
        boolean inWord = false;
        if (before != null) {
            for (int i = 0; i < before.length(); i++) {
                char c = before.charAt(i);
                if (Character.isWhitespace(c)) {
                    if (inWord) { words++; if (words >= 2) return true; inWord = false; }
                } else {
                    inWord = true;
                }
            }
        }
        if (after != null) {
            for (int i = 0; i < after.length(); i++) {
                char c = after.charAt(i);
                if (Character.isWhitespace(c)) {
                    if (inWord) { words++; if (words >= 2) return true; inWord = false; }
                } else {
                    inWord = true;
                }
            }
        }
        if (inWord) words++;
        return words >= 2;
    }

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        // Don't early-return on a null InputConnection: SHIFT, MODE_CHANGE, MIC, EMOJI
        // and the composer/active-input-target paths are all IC-independent, and the
        // IC-using paths (commit/backspace/sendEnter) already null-guard internally.
        // The old early return silently dropped panel toggles during focus transitions.

        // Whatever component currently owns the input (AI prompt field, emoji search, …)
        // gets printable keys + DELETE + DONE. SHIFT/MODE_CHANGE fall through so the user
        // can capitalise / switch layouts mid-typing.
        if (activeInputTarget != null) {
            if (primaryCode == Keycodes.DELETE) {
                activeInputTarget.onBackspace();
                return;
            }
            if (primaryCode == Keycodes.DONE) {
                activeInputTarget.onDone();
                return;
            }
            if (primaryCode > 0) {
                activeInputTarget.appendChar((char) primaryCode);
                return;
            }
        }

        if (primaryCode == Keycodes.MIC) {
            toggleVoiceInput();
            return;
        }
        if (primaryCode == Keycodes.EMOJI) {
            toggleEmojiPanel();
            return;
        }

        if (composer.isActive() && handleComposingKey(primaryCode)) return;

        switch (primaryCode) {
            case Keycodes.DELETE:
                deleteRepeatCount++;
                if (deleteRepeatCount > DELETE_CHARS_BEFORE_WORD_MODE) {
                    deletePreviousWord();
                } else {
                    committer.backspace();
                }
                refreshSuggestions();
                // Deleting back across ". " or to start of input changes the auto-cap context.
                updateAutoCapFromHost();
                break;
            case Keycodes.SHIFT:
                if (keyboard.isQwerty()) {
                    shift.onShiftPress();
                    syncCapsLockIndicator();
                } else keyboard.toggleSymbolShift();
                break;
            case Keycodes.DONE:
                if (editorAction != 0) {
                    // Field declared a specific action (Search / Send / Go / etc.) — submit
                    // through the proper API instead of synthesizing KEYCODE_ENTER. Some
                    // editors (Hinge, login forms) ignore the synthesized key.
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) ic.performEditorAction(editorAction);
                    else committer.sendEnter();
                } else {
                    committer.sendEnter();
                }
                break;
            case Keycodes.MODE_CHANGE:
                keyboard.toggleLetterSymbol();
                if (keyboard.isQwerty()) {
                    shift.reapply();
                    // Coming back to QWERTY at a sentence start (e.g. user typed a
                    // digit, switched back) should re-shift, since shift.reapply()
                    // only restores caps-lock.
                    updateAutoCapFromHost();
                }
                break;
            case Keycodes.SLASH:
                if (!composer.isActive() && atWordBoundary()) {
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
                if (primaryCode <= 0) return true;
                char c = (char) primaryCode;
                if (Character.isLetter(c) && shift.isUpper()) c = Character.toUpperCase(c);
                composer.appendChar(c);
                shift.onCharCommitted();
                return true;
        }
    }

    private void finishCompose(char terminator) {
        if (composer.mode() == CommandComposer.Mode.PROMPT) {
            // Enter/Done dispatch; SPACE appends to the query.
            if (terminator == Keycodes.SPACE) {
                composer.appendChar(' ');
                return;
            }
            dispatchPromptPanel();
            return;
        }

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
            // Unknown command — surface what was typed so the user doesn't lose it.
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

    private void dispatchStylePreset(String preset) {
        if (preset == null || preset.isEmpty()) return;
        composer.cancel();
        SlashCommand cmd = new SlashCommand("style", preset, "/style " + preset);
        dispatcher.dispatchComposed(cmd);
    }

    private void dispatchUsPreset(String preset) {
        if (preset == null || preset.isEmpty()) return;
        composer.cancel();
        SlashCommand cmd = new SlashCommand("us", preset, "/us " + preset);
        dispatcher.dispatchComposed(cmd);
    }

    private static final String NOTIF_CHANNEL_ID = "image_ready";
    private boolean notifChannelCreated;

    /** BigPictureStyle notification with a share-sheet tap and an optional direct "Share to &lt;originPkg&gt;" action. */
    private void notifyImageReady(java.io.File img, Uri uri, @Nullable String originPkg) {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            Log.w("TurtleIME", "notify: NotificationManager null");
            return;
        }
        // Android 13+: POST_NOTIFICATIONS is a runtime permission; without it notify() silently drops.
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

        bitmapIo.execute(() -> {
            android.graphics.Bitmap decoded;
            try {
                decoded = android.graphics.BitmapFactory.decodeFile(img.getAbsolutePath());
            } catch (Exception e) {
                decoded = null;
            }
            final android.graphics.Bitmap big = decoded;
            mainHandler.post(() -> buildAndPostImageNotification(nm, uri, originPkg, big));
        });
    }

    private void buildAndPostImageNotification(android.app.NotificationManager nm,
                                               Uri uri, @Nullable String originPkg,
                                               @Nullable android.graphics.Bitmap big) {
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
        // resolveActivity misses non-default receivers (e.g. WhatsApp ContactPicker); query + pin a component instead.
        if (originPkg != null) {
            Intent direct = new Intent(Intent.ACTION_SEND);
            direct.setType("image/png");
            direct.putExtra(Intent.EXTRA_STREAM, uri);
            direct.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            direct.setPackage(originPkg);
            android.content.pm.PackageManager pm = getPackageManager();
            java.util.List<android.content.pm.ResolveInfo> hits =
                    pm.queryIntentActivities(direct, 0);
            if (!hits.isEmpty()) {
                android.content.pm.ActivityInfo target = hits.get(0).activityInfo;
                direct.setComponent(new android.content.ComponentName(
                        target.packageName, target.name));
                // Explicit URI grant — FileProvider's PendingIntent grant doesn't survive setPackage.
                grantUriPermission(originPkg, uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                CharSequence label;
                try {
                    android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(originPkg, 0);
                    label = pm.getApplicationLabel(ai);
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    label = originPkg;
                }
                android.app.PendingIntent directPi = android.app.PendingIntent.getActivity(
                        this, 1, direct,
                        android.app.PendingIntent.FLAG_IMMUTABLE
                                | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                b.addAction(new androidx.core.app.NotificationCompat.Action.Builder(
                        R.mipmap.ic_launcher, "Share to " + label, directPi).build());
                Log.d("TurtleIME", "notify: direct action wired pkg=" + originPkg
                        + " activity=" + target.name);
            } else {
                Log.d("TurtleIME", "notify: origin pkg " + originPkg
                        + " has no ACTION_SEND handler for image/png — "
                        + "skipping direct action");
            }
        }
        try {
            int id = (int) (System.currentTimeMillis() & 0x7fffffff);
            nm.notify(id, b.build());
            Log.d("TurtleIME", "notify: posted id=" + id);
        } catch (SecurityException e) {
            // OEM quirk fallback; the explicit check above should catch this normally.
            Log.w("TurtleIME", "notify suppressed: " + e.getMessage());
        }
    }

    /** Stages a side-by-side thumbnail of the picked /us photos and re-shows the IME. */
    private void onUsImagesStaged(
            @Nullable java.util.List<byte[]> images, @Nullable java.util.List<String> mimes) {
        if (images == null || images.isEmpty()) {
            root.post(() -> root.panel().setStagedImage(null, null));
            return;
        }
        final java.util.List<byte[]> snapshot = new java.util.ArrayList<>(images);
        bitmapIo.execute(() -> {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = 4;
            android.graphics.Bitmap first = snapshot.size() > 0
                    ? android.graphics.BitmapFactory.decodeByteArray(
                            snapshot.get(0), 0, snapshot.get(0).length, opts) : null;
            android.graphics.Bitmap second = snapshot.size() > 1
                    ? android.graphics.BitmapFactory.decodeByteArray(
                            snapshot.get(1), 0, snapshot.get(1).length, opts) : null;
            final android.graphics.Bitmap composite = composeSideBySide(first, second);
            root.post(() -> root.panel().setStagedImage(composite, () ->
                    stagingPipeline().stageUsImages(null, null)));
        });
        requestShowSelfAfterPick();
    }

    /** Returns a square-tile side-by-side bitmap of {@code a} and {@code b}; null inputs render blank. */
    @Nullable
    private android.graphics.Bitmap composeSideBySide(
            @Nullable android.graphics.Bitmap a, @Nullable android.graphics.Bitmap b) {
        if (a == null && b == null) return null;
        int tile = 96;
        int gap = 4;
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                tile * 2 + gap, tile, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        drawCenterCropped(c, a, 0, 0, tile, tile);
        drawCenterCropped(c, b, tile + gap, 0, tile, tile);
        return out;
    }

    private void drawCenterCropped(android.graphics.Canvas c, @Nullable android.graphics.Bitmap src,
                                   int dx, int dy, int dw, int dh) {
        if (src == null) return;
        int sw = src.getWidth();
        int sh = src.getHeight();
        float scale = Math.max((float) dw / sw, (float) dh / sh);
        int outW = Math.round(sw * scale);
        int outH = Math.round(sh * scale);
        int x = dx + (dw - outW) / 2;
        int y = dy + (dh - outH) / 2;
        android.graphics.Rect dst = new android.graphics.Rect(x, y, x + outW, y + outH);
        c.save();
        c.clipRect(dx, dy, dx + dw, dy + dh);
        c.drawBitmap(src, null, dst, null);
        c.restore();
    }

    /** Stages the picked image into the panel and re-shows the IME (picker shim stole focus). */
    private void onEditImageStaged(@Nullable byte[] bytes, @Nullable String mime) {
        if (bytes == null) {
            root.post(() -> root.panel().setStagedImage(null, null));
            return;
        }
        bitmapIo.execute(() -> {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = 4;
            final android.graphics.Bitmap thumb =
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            root.post(() -> root.panel().setStagedImage(thumb, () ->
                    stagingPipeline().stageEditImage(null, null)));
        });
        requestShowSelfAfterPick();
    }

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        if (pendingShowAfterPick) {
            pendingShowAfterPick = false;
            mainHandler.post(() -> requestShowSelf(0));
        }
    }

    /** Process-wide image-staging pipeline (held by {@link TurtleApp}). */
    private com.prince.turtlekeyboard.ai.StagingPipeline stagingPipeline() {
        return com.prince.turtlekeyboard.TurtleApp.from(this).stagingPipeline();
    }

    /** Picker fallback: the shim activity tears down our IME; the next onStartInput
     *  consumes the flag, but if it never fires (e.g., user backs out), this re-shows us. */
    private void requestShowSelfAfterPick() {
        pendingShowAfterPick = true;
        mainHandler.postDelayed(() -> {
            if (pendingShowAfterPick) {
                pendingShowAfterPick = false;
                requestShowSelf(0);
            }
        }, 300L);
    }

    /** SPI variant of {@link #launchEditImagePicker}; routes the result to the in-flight ctx.pickImage callback. */
    private void launchSpiImagePicker(int requestId) {
        android.content.Intent i = new android.content.Intent(this,
                com.prince.turtlekeyboard.ai.ImagePickerActivity.class);
        i.putExtra(com.prince.turtlekeyboard.ai.ImagePickerActivity.EXTRA_REQUEST_ID, requestId);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            android.util.Log.w("TurtleIME", "spi picker launch failed", e);
            // Deliver null so the integration doesn't hang.
            com.prince.turtlekeyboard.ai.PickerResultBus.deliver(requestId, null, null);
        }
    }

    /** Launches the picker shim; bytes flow back via {@link StagingPipeline} for the next dispatch. */
    private void launchEditImagePicker() {
        // Clear any leftover staged image so cancelling the picker doesn't resurrect an old one.
        stagingPipeline().stageEditImage(null, null);
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

    /** Launches the multi-image picker (exactly 2); bytes flow back via {@link StagingPipeline}. */
    private void launchUsImagePicker() {
        stagingPipeline().stageUsImages(null, null);
        android.content.Intent i = new android.content.Intent(this,
                com.prince.turtlekeyboard.ai.ImagePickerActivity.class);
        i.putExtra(com.prince.turtlekeyboard.ai.ImagePickerActivity.EXTRA_PICK_COUNT, 2);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            android.util.Log.w("TurtleIME", "us picker launch failed", e);
            root.banner().showAndAutoHide("Picker unavailable", 2000L);
        }
    }

    /** Returns null when the clipboard has nothing pasteable. */
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
        // Dismiss the paste preview once the user starts typing so suggestion slots reclaim the strip.
        if (root != null && root.strip() != null) {
            root.strip().setPasteText(null);
        }
        char c = (char) code;
        if (Character.isLetter(c) && shift.isUpper()) c = Character.toUpperCase(c);
        // No auto-correction on space; suggestions only apply on chip tap.
        committer.commitChar(c);
        if (code == Keycodes.SPACE) spaceGesture.onSpacePressed();
        // Single IPC shared between learner, slash detector, and suggestion refresh.
        // 200 chars is the slash-detector window (largest of the three); learner and
        // refresh only consult the trailing portion so the extra bytes cost nothing.
        final CharSequence before = committer.textBeforeCursor(200);
        if (!Character.isLetter(c)) maybeLearnLastWord(before);
        shift.onCharCommitted();
        slashDetector.onTextChanged(c, before);
        refreshSuggestions(before);
        // After a sentence terminator + space we want the next letter shifted.
        // shift.onCharCommitted() already unshifted; auto-cap re-shifts if the new
        // text-before-cursor is at a sentence/word/document start.
        updateAutoCap(before);
    }

    /** Returns the action ID the Enter key should perform, or 0 if the field
     *  expects a plain newline (multi-line text, no declared action, or the
     *  editor explicitly opted out via {@link EditorInfo#IME_FLAG_NO_ENTER_ACTION}). */
    private static int computeEditorAction(@Nullable EditorInfo info) {
        if (info == null) return 0;
        if ((info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return 0;
        int a = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (a == EditorInfo.IME_ACTION_UNSPECIFIED || a == EditorInfo.IME_ACTION_NONE) return 0;
        return a;
    }

    /** Honours the editor's CAP_SENTENCES / CAP_WORDS / CAP_CHARACTERS flag by
     *  shifting the keyboard at sentence/word/document starts. Never toggles
     *  shift off — that's left to manual shift presses and the natural
     *  per-letter unshift in {@link ShiftController#onCharCommitted()} — so
     *  manual case overrides are preserved. */
    private void updateAutoCap(@Nullable CharSequence before) {
        if (autoCapMode == 0 || shift == null || shift.isCapsLock() || keyboardView == null) return;
        if (!keyboard.isQwerty()) return;
        if (before == null) return;
        int caps = TextUtils.getCapsMode(before, before.length(), autoCapMode);
        if (caps != 0 && !keyboardView.isShifted()) {
            keyboardView.setShifted(true);
        }
    }

    /** Mirrors {@link ShiftController}'s caps-lock state into the view so the
     *  shift glyph swaps between ⇧ (sticky-off / shift-once) and ⇪ (sticky-on). */
    private void syncCapsLockIndicator() {
        if (shift != null && keyboardView instanceof TurtleKeyboardView) {
            ((TurtleKeyboardView) keyboardView).setCapsLocked(shift.isCapsLock());
        }
    }

    /** Deletes the run of whitespace immediately before the cursor (if any) plus
     *  the word that precedes it. Single binder call. Matches Gboard's long-press
     *  backspace cadence — eats "world|" → "" and "hello world |" → "hello ". */
    private void deletePreviousWord() {
        if (committer == null) return;
        CharSequence before = committer.textBeforeCursor(64);
        if (before == null || before.length() == 0) return;
        int end = before.length();
        while (end > 0 && Character.isWhitespace(before.charAt(end - 1))) end--;
        while (end > 0 && !Character.isWhitespace(before.charAt(end - 1))) end--;
        int n = before.length() - end;
        if (n > 0) committer.deleteBeforeCursor(n);
    }

    /** Convenience for paths that haven't already snapshotted text-before-cursor. */
    private void updateAutoCapFromHost() {
        if (committer == null) return;
        updateAutoCap(committer.textBeforeCursor(64));
    }

    /** Walks back from the cursor to the preceding word and feeds it to the suggestion engine. */
    private void maybeLearnLastWord(CharSequence before) {
        if (suggestionEngine == null || before == null || before.length() == 0) return;
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

    private void toggleVoiceInput() {
        if (voice == null) return;
        if (!VoiceInputController.hasMicPermission(this)) {
            // IMEs can't request runtime perms; bounce to MainActivity which holds the flow.
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
            // Stage replaces the mini bar; keep one voice indicator on screen.
            root.voiceListening().stop();

            // Overlay the keys without growing the IME window: height=kh, topMargin=-kh.
            int kh = root.keyboardView().getHeight();
            if (kh > 0) {
                android.view.ViewGroup.LayoutParams lp = root.voiceStage().getLayoutParams();
                lp.height = kh;
                if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                    ((android.widget.LinearLayout.LayoutParams) lp).topMargin = -kh;
                }
                root.voiceStage().setLayoutParams(lp);
            }

            View mic = root.strip().micButton();
            int[] micLoc = new int[2];
            mic.getLocationInWindow(micLoc);
            int micCx = micLoc[0] + mic.getWidth() / 2;
            int micCy = micLoc[1] + mic.getHeight() / 2;

            root.voiceStage().start(micCx, micCy);
        }
        @Override public void onListeningStopped() {
            root.voiceStage().stop();
        }
        @Override public void onRms(float dB) { root.voiceStage().setRms(dB); }
        @Override public void onPartial(String text) {
            if (text != null && !text.isEmpty()) root.voiceStage().setTranscript(text);
        }
        @Override public void onFinal(String text) {
            if (text == null || text.isEmpty()) return;
            // Whichever component owns the input takes precedence — same routing rule as typed keys.
            if (activeInputTarget != null) {
                activeInputTarget.appendText(text);
                return;
            }
            if (composer.isActive()) {
                // Route into whichever phase the composer is in (NAME / PROMPT).
                for (int i = 0; i < text.length(); i++) composer.appendChar(text.charAt(i));
            } else {
                committer.commitText(text);
                slashDetector.onTextChanged(text.charAt(text.length() - 1));
                refreshSuggestions();
            }
        }
        @Override public void onError(String userVisibleMessage) {
            if (root.voiceStage().getVisibility() == View.VISIBLE) {
                root.voiceStage().showError(userVisibleMessage);
            } else {
                root.banner().showAndAutoHide(userVisibleMessage, 1500);
            }
        }
    };

    @Override
    public void onDestroy() {
        // Listeners would otherwise fire into this dead instance's view tree after the next
        // IME (re)creation registers fresh refs onto the same pipeline.
        stagingPipeline().setEditListener(null);
        stagingPipeline().setUsListener(null);
        com.prince.turtlekeyboard.ai.PickerResultBus.setListener(null);
        if (suggestionEngine != null) {
            suggestionEngine.setOnReadyListener(null);
            suggestionEngine.shutdown();
        }
        if (voice != null) { voice.destroy(); voice = null; }
        if (integrations != null) { integrations.shutdown(); integrations = null; }
        if (aiClient != null) { aiClient.destroy(); aiClient = null; }
        if (onDeviceAi != null) { onDeviceAi.destroy(); onDeviceAi = null; }
        bitmapIo.shutdown();
        suggestionExecutor.shutdown();
        super.onDestroy();
    }

    private void refreshSuggestions() {
        refreshSuggestions(null);
    }

    /** Overload for emitChar's path where the IPC has already been done — passes
     *  {@code before} through so we don't pay a second binder roundtrip. Callers
     *  outside the emit hot path can call the no-arg variant and we'll fetch. */
    private void refreshSuggestions(@Nullable CharSequence before) {
        // Prompt buffer never reaches the host; use the prompt-context path so suggestions track the panel.
        // Prompt path stays synchronous — input is local, no IPC, and the panel is already in compose mode.
        if (composer != null && composer.isActive()
                && composer.mode() == com.prince.turtlekeyboard.command.CommandComposer.Mode.PROMPT) {
            refreshPromptSuggestions(composer.query(), composer.promptCursor());
            return;
        }
        if (suggestionProvider == null || committer == null || root == null) return;
        // IPC stays on main thread (InputConnection is owned here); the suggest pipeline
        // (DAFSA walk + SymSpell lookup) moves to a background executor so the key path
        // doesn't pay 2–7ms per keystroke at sustained typing speed.
        final int version = ++suggestionVersion;
        final CharSequence snapshot = before != null ? before : committer.textBeforeCursor(64);
        final SuggestionProvider provider = suggestionProvider;
        if (pendingSuggestionFuture != null) pendingSuggestionFuture.cancel(false);
        pendingSuggestionFuture = suggestionExecutor.submit(() -> {
            final List<String> list = provider.suggest(snapshot);
            mainHandler.post(() -> {
                if (version != suggestionVersion || root == null) return;
                root.strip().setSuggestions(list);
            });
        });
    }

    /** Sibling of refreshSuggestions that reads from the composer buffer instead of the host editor. */
    private void refreshPromptSuggestions(String query, int cursorPos) {
        if (root == null || composer == null) return;
        String context = slicePromptContext(query, cursorPos);
        com.prince.turtlekeyboard.command.PromptSuggestionSource override =
                registry.suggestionSourceFor(composer.commandName());
        List<String> list;
        if (override != null) {
            list = override.suggest(context);
        } else if (suggestionProvider != null) {
            list = suggestionProvider.suggest(context);
        } else {
            return;
        }
        root.strip().setSuggestions(list == null ? java.util.Collections.emptyList() : list);
    }

    /** Trailing 64 chars before the cursor — the context window suggestion sources see. */
    private static String slicePromptContext(String query, int cursorPos) {
        String q = query == null ? "" : query;
        int safe = Math.max(0, Math.min(q.length(), cursorPos));
        String ctx = q.substring(0, safe);
        return ctx.length() > 64 ? ctx.substring(ctx.length() - 64) : ctx;
    }

    private void onSuggestionPicked(String suggestion) {
        // PROMPT mode: route through the composer so the panel updates and the host doesn't see the suggestion.
        if (composer != null && composer.isActive()
                && composer.mode() == com.prince.turtlekeyboard.command.CommandComposer.Mode.PROMPT) {
            String q = composer.query();
            int cursor = composer.promptCursor();
            int wordStart = cursor;
            while (wordStart > 0 && !Character.isWhitespace(q.charAt(wordStart - 1))) {
                wordStart--;
            }
            // Looping backspace fires the composer Ui callback that repaints the panel.
            for (int i = cursor; i > wordStart; i--) composer.backspace();
            composer.appendString(suggestion + " ");
            if (suggestionEngine != null) suggestionEngine.learn(suggestion);
            return;
        }
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
        // Skip system surfaces, sensitive fields, already-enrolled/suppressed pkgs.
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

        android.graphics.drawable.Drawable icon = appIcon(pkg);
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
        // Skip system surfaces and sensitive fields so the badge never appears on a password screen.
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
        android.graphics.drawable.Drawable icon = appIcon(pkg);
        if (icon == null) { root.hostAppBadge().hide(); return; }
        root.hostAppBadge().show(icon);
    }

    /** Cached PackageManager lookup. Result is held by package name for the IME
     *  lifetime — drawables are bitmap-backed and a handful of enrolled apps
     *  fits easily in memory. Returns null when the package isn't installed. */
    @Nullable
    private android.graphics.drawable.Drawable appIcon(String pkg) {
        if (pkg == null) return null;
        if (iconCache.containsKey(pkg)) return iconCache.get(pkg);
        android.graphics.drawable.Drawable icon;
        try {
            icon = getApplicationContext().getPackageManager().getApplicationIcon(pkg);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            icon = null;
        }
        iconCache.put(pkg, icon);
        return icon;
    }

    private static boolean isSystemPackage(String pkg) {
        // Don't filter arbitrary "com.android.*" — Chrome ships as com.android.chrome.
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
        // emitChar already committed the two space chars; undo them.
        committer.deleteBeforeCursor(2);
        CharSequence last = committer.textBeforeCursor(1);
        if (last != null && last.length() > 0) {
            slashDetector.onTextChanged(last.charAt(0));
        }
        refreshSuggestions();
        if (isQuickPanelVisible()) {
            hideQuickPanel();
            return;
        }
        showQuickPanel();
    }

    private boolean isQuickPanelVisible() {
        return keyAreaPanel != null && keyAreaPanel.isVisible();
    }

    private void showQuickPanel() {
        com.prince.turtlekeyboard.ime.view.QuickPanelView panel =
                new com.prince.turtlekeyboard.ime.view.QuickPanelView(this);
        panel.applyTheme(themes.current());
        // Re-query: currentPkg can go stale if Android re-enters the input view without re-firing onStartInputView.
        EditorInfo liveInfo = getCurrentInputEditorInfo();
        if (liveInfo != null && liveInfo.packageName != null) currentPkg = liveInfo.packageName;
        panel.show(registry.allSortedFor(currentPkg), this::onQuickPanelPick, this::hideQuickPanel);
        keyAreaPanel.show(panel);
    }

    private void hideQuickPanel() {
        if (keyAreaPanel != null) keyAreaPanel.hide();
    }

    /** Emoji panel and Quick Panel share the quickPanelHost slot. */
    private void toggleEmojiPanel() {
        if (isEmojiPanelVisible()) {
            hideEmojiPanel();
            return;
        }
        if (isQuickPanelVisible()) hideQuickPanel();
        showEmojiPanel();
    }

    private boolean isEmojiPanelVisible() {
        return keyAreaPanel != null
                && keyAreaPanel.currentChild() instanceof com.prince.turtlekeyboard.ime.view.EmojiPanelView;
    }

    private void showEmojiPanel() {
        final com.prince.turtlekeyboard.ime.view.EmojiPanelView panel =
                new com.prince.turtlekeyboard.ime.view.EmojiPanelView(this);
        panel.applyTheme(themes.current());
        panel.setOnInputActiveChangedListener(inputTargetWatcher);
        panel.show(this::commitEmoji, this::hideEmojiPanel);
        panel.setOnGifPickListener(this::insertHistoryGif);
        panel.setOnGifAddListener(() -> composer.enterPromptMode("gif"));

        final android.inputmethodservice.KeyboardView keys = root.keyboardView();
        int targetHeight = keys.getHeight();
        if (targetHeight <= 0) targetHeight = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT;
        // Lock browse height so search mode can shrink + restore without the panel collapsing.
        panel.setBrowseHeightPx(targetHeight);
        // Search mode re-shows keys behind the search bar; exit hides them in the same frame
        // the panel snaps back to browse height (fade would leave a 68dp jump).
        panel.setOnSearchStateListener(new com.prince.turtlekeyboard.ime.view.EmojiPanelView.OnSearchStateListener() {
            @Override public void onEnterSearch() {
                keys.animate().cancel();
                keys.setAlpha(0f);
                keys.setVisibility(View.VISIBLE);
                keys.animate().alpha(1f).setDuration(180).start();
            }
            @Override public void onExitSearch() {
                keys.animate().cancel();
                keys.setAlpha(1f);
                keys.setVisibility(View.GONE);
            }
        });

        keyAreaPanel.show(panel);
        activeEmojiPanel = panel;
    }

    private void hideEmojiPanel() {
        // Search mode never gets an exit fire on this teardown path (view is removed directly),
        // so drop any input slot the panel was holding before nulling it out.
        if (activeInputTarget == activeEmojiPanel) activeInputTarget = null;
        activeEmojiPanel = null;
        // Same teardown as the Quick Panel — they share the slot.
        hideQuickPanel();
    }

    private boolean isAiAssistPanelVisible() {
        return keyAreaPanel != null
                && keyAreaPanel.currentChild() == aiAssistPanel
                && aiAssistPanel != null;
    }

    /** Mounts the AI assist panel via {@link com.prince.turtlekeyboard.ime.view.PanelSlot}
     *  — same slot/pattern as Emoji / Quick / History panels. Browse mode replaces the
     *  keys (no IME size change at all). Compose mode (user taps the custom prompt
     *  field) shrinks the panel and re-shows the keys below — same UX as emoji search. */
    private void showAiAssistPanel() {
        if (isAiAssistPanelVisible()) {
            hideAiAssistPanel();
            return;
        }
        if (aiAssistPanel == null) {
            aiAssistPanel = new com.prince.turtlekeyboard.ime.view.AiAssistPanelView(this);
            aiAssistPanel.setOnInputActiveChangedListener(inputTargetWatcher);
        }
        final com.prince.turtlekeyboard.ime.view.AiAssistPanelView panel = aiAssistPanel;
        final android.inputmethodservice.KeyboardView keys = root.keyboardView();
        // Lock browse height so re-entering browse from compose restores correctly.
        int browseHeight = keys.getHeight();
        panel.setBrowseHeightPx(browseHeight > 0
                ? browseHeight
                : android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        panel.applyTheme(themes.current());
        panel.show(this::runAiAssist, this::hideAiAssistPanel);
        // AutoTransition inside the panel handles the fade — we just flip visibility.
        panel.setOnComposeStateListener(new com.prince.turtlekeyboard.ime.view.AiAssistPanelView.OnComposeStateListener() {
            @Override public void onEnterCompose() {
                keys.setVisibility(View.VISIBLE);
            }
            @Override public void onExitCompose() {
                keys.setVisibility(View.GONE);
            }
        });
        keyAreaPanel.show(panel);
    }

    private void hideAiAssistPanel() {
        if (aiAssistPanel != null) {
            // Synchronous state reset (no animation now that PanelSlot handles teardown).
            aiAssistPanel.hide();
        }
        if (keyAreaPanel != null) keyAreaPanel.hide();
    }

    /** Snapshots the field, runs the rewrite (on-device when Nano is ready, else cloud Gemini),
     *  silently replaces on success, banners on error. */
    private void runAiAssist(String systemPrompt) {
        if (aiClient == null || committer == null || aiAssistPanel == null) return;
        CharSequence current = committer.getAllText();
        String text = current == null ? "" : current.toString();
        if (text.trim().isEmpty()) {
            root.banner().showAndAutoHide("Field is empty", 1200L);
            return;
        }
        final com.prince.turtlekeyboard.ime.view.AiAssistPanelView panel = aiAssistPanel;
        panel.setInflight(true);
        root.banner().show("Rewriting…");

        TurtleAiClient.RewriteCallback callback = new TurtleAiClient.RewriteCallback() {
            @Override public void onSuccess(String rewritten) {
                root.banner().clear();
                if (committer != null && rewritten != null && !rewritten.isEmpty()) {
                    committer.replaceAll(rewritten);
                }
                hideAiAssistPanel();
            }
            @Override public void onError(String message) {
                panel.setInflight(false);
                root.banner().showAndAutoHide(message, 1500L);
            }
        };

        OnDeviceAiClient.Availability avail = onDeviceAi == null
                ? OnDeviceAiClient.Availability.UNAVAILABLE
                : onDeviceAi.availability();
        boolean onDevice = avail == OnDeviceAiClient.Availability.AVAILABLE;
        android.util.Log.i("TurtleIME",
                "assist route=" + (onDevice ? "on-device" : "cloud")
                        + " nanoState=" + avail);
        if (onDevice) {
            onDeviceAi.rewrite(systemPrompt, text, new TurtleAiClient.RewriteCallback() {
                @Override public void onSuccess(String rewritten) { callback.onSuccess(rewritten); }
                @Override public void onError(String message) {
                    // Nano failed mid-flight (rare, but possible) — quietly fall back to cloud.
                    aiClient.rewrite(systemPrompt, text, callback);
                }
            });
        } else {
            aiClient.rewrite(systemPrompt, text, callback);
        }
    }

    /** Goes straight to the IC so EmojiCompat-rendered text lands on hosts that filter custom spans. */
    private void commitEmoji(String emoji) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || emoji == null || emoji.isEmpty()) return;
        ic.commitText(emoji, 1);
    }

    private void showHistoryPanel() {
        com.prince.turtlekeyboard.ime.view.HistoryPanelView panel =
                new com.prince.turtlekeyboard.ime.view.HistoryPanelView(this);
        panel.applyTheme(themes.current());
        panel.show(com.prince.turtlekeyboard.ai.ImageHistory.list(this),
                this::insertHistoryImage,
                this::hideQuickPanel);
        keyAreaPanel.show(panel);
    }

    private void insertHistoryImage(java.io.File file) {
        if (file == null || !file.exists()) {
            root.banner().showAndAutoHide("File missing", 1500L);
            return;
        }
        Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", file);
        hideQuickPanel();
        // GIFs committed under image/png lose their animation in the host.
        String mime = file.getName().endsWith(".gif") ? "image/gif" : "image/png";
        if (!insertImage(uri, mime)) copyToClipboard(uri, mime);
    }

    private void insertHistoryGif(java.io.File file) {
        if (file == null || !file.exists()) {
            root.banner().showAndAutoHide("File missing", 1500L);
            return;
        }
        Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", file);
        hideEmojiPanel();
        if (!insertImage(uri, "image/gif")) copyToClipboard(uri, "image/gif");
    }

    private void refreshCommandSuggestions(String displayed) {
        // Wait for at least one letter after the slash so "/" alone doesn't dump every command.
        String prefix = displayed == null || displayed.isEmpty() || displayed.charAt(0) != '/'
                ? "" : displayed.substring(1);
        if (prefix.isEmpty()) {
            root.cmdSuggestions().hide();
            return;
        }
        root.cmdSuggestions().show(registry.matchesFor(prefix, currentPkg));
    }

    private void dismissCommandSuggestions() {
        // Exit NAME mode AND hide the strip so further typing doesn't restart suggestions.
        composer.cancel();
        root.cmdSuggestions().hide();
    }

    private void onCommandSuggestionPicked(CommandRegistry.Entry entry) {
        // No-prompt commands fire immediately on pick (e.g. /history needs no Go-tap).
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

    /** Repaints the keyboard when the OS night-mode bit flips so AUTO theme tracks system live. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (themes != null && root != null) {
            applyTheme();
        }
    }

    // ===== CommandDispatcher.ResultUi =====

    @Override public void showStatus(String message) {
        // Trailing "…" marks a loading status; everything else falls through to a transient banner.
        if (message != null && message.endsWith("…")) {
            // Snapshot the host pkg now so a backgrounded keyboard can still route the notification share.
            pendingSourcePkg = currentPkg;
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
            root.banner().showAndAutoHide("No local preview available", 2000L);
            return;
        }
        root.banner().clear();
        if (!source.exists()) {
            root.banner().showAndAutoHide("Preview file missing", 2000L);
            return;
        }
        // Keyboard backgrounded mid-generation falls back to notification.
        Log.d("TurtleIME", "showImage visible=" + inputViewVisible + " path="
                + (inputViewVisible ? "preview" : "notification"));
        if (!inputViewVisible) {
            String originPkg = pendingSourcePkg;
            pendingSourcePkg = null;
            notifyImageReady(source, uri, originPkg);
            return;
        }
        pendingSourcePkg = null;
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

    /** Encodes the source into the user-picked format, then commits or falls back to clipboard. */
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

    /** Tries {@link InputConnectionCompat#commitContent}; caller decides the fallback when it returns false. */
    private boolean insertImage(Uri uri, String mime) {
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null || info == null) {
            android.util.Log.d("TurtleIME", "insertImage skip mime=" + mime
                    + " ic=" + (ic == null ? "null" : "ok")
                    + " info=" + (info == null ? "null" : "ok"));
            return false;
        }
        // Don't pre-check contentMimeTypes: WhatsApp accepts image/gif via commitContent without listing it.
        // The list is logged on failure so logcat shows what the host claims to support.
        String[] advertised =
                androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(info);
        InputContentInfoCompat content = new InputContentInfoCompat(
                uri, new ClipDescription("turtle", new String[]{mime}), null);
        int flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION;
        boolean ok = InputConnectionCompat.commitContent(ic, info, content, flags, null);
        android.util.Log.d("TurtleIME", "insertImage commit pkg=" + info.packageName
                + " mime=" + mime + " ok=" + ok
                + (ok ? "" : " advertised=" + java.util.Arrays.toString(advertised)));
        if (ok) root.banner().showAndAutoHide("Inserted 🐢", 1500L);
        return ok;
    }

    /** Persistent chip offering to retry insertion of an image whose auto-commit failed. */
    private void offerInsertChip(Uri uri, String mime) {
        pendingInsertUri = uri;
        pendingInsertMime = mime;
        final String label = "image/gif".equals(mime)
                ? "Tap to insert GIF 🎞️"
                : "Tap to insert image 🖼️";
        root.chip().show(label, null);
        root.chip().setOnTapListener(() -> {
            Uri u = pendingInsertUri;
            String m = pendingInsertMime;
            pendingInsertUri = null;
            pendingInsertMime = null;
            root.chip().setOnTapListener(null);
            root.chip().hide();
            if (u == null) return;
            if (!insertImage(u, m)) copyToClipboard(u, m);
        });
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
        pendingSourcePkg = null;
    }

    // ===== KeyboardView.OnKeyboardActionListener stubs =====

    @Override public void onPress(int primaryCode) {
        // Reset on every fresh press so a single backspace tap can never inherit
        // word-delete mode from a prior hold.
        if (primaryCode == Keycodes.DELETE) deleteRepeatCount = 0;
        boolean haptics = prefs == null || prefs.getBool(Prefs.KEY_HAPTICS, true);
        boolean sound = prefs == null || prefs.getBool(Prefs.KEY_KEY_SOUND, true);
        if (root != null && haptics) {
            root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
        if (audio != null && sound) {
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
