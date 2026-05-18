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
import com.prince.turtlekeyboard.ai.AiClient;
import com.prince.turtlekeyboard.ai.LmStudioAiClient;
import com.prince.turtlekeyboard.ai.StubAiClient;
import com.prince.turtlekeyboard.command.CommandComposer;
import com.prince.turtlekeyboard.command.CommandDispatcher;
import com.prince.turtlekeyboard.command.CommandRegistry;
import com.prince.turtlekeyboard.command.SlashCommand;
import com.prince.turtlekeyboard.command.SlashCommandDetector;
import com.prince.turtlekeyboard.gesture.SpaceGestureHandler;
import com.prince.turtlekeyboard.ime.view.BackspaceMenuPopup;
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
    // Package the user was in when the *current* AI command fired. Snapshotted
    // at loader-show time so that if the keyboard is gone by result time we
    // can still offer a direct "Share to <originating app>" action in the
    // notification — preserves the chat-thread context they started from.
    @Nullable private String pendingSourcePkg;
    private com.prince.turtlekeyboard.integration.PersistentAppProfileRegistry appProfiles;
    private com.prince.turtlekeyboard.integration.EnrolledShortcutsManager enrolledShortcuts;
    private SuggestionProvider suggestionProvider;
    private SuggestionEngine suggestionEngine;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // SPI image-pick routing — see ImageBridge / PickerResultBus. Each ctx.pickImage()
    // call allocates a fresh request id and parks its callback here until the picker
    // activity delivers a result. Legacy /edit picks bypass this map entirely.
    private final java.util.concurrent.atomic.AtomicInteger pickerReqIds =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.Map<Integer, com.prince.kbd.core.IntegrationContext.ImagePickCallback>
            pickerCallbacks = new java.util.concurrent.ConcurrentHashMap<>();
    private ThemeManager themes;
    private AudioManager audio;
    private KeyPreviewPopup preview;
    private KeyboardView keyboardView;
    private BackspaceMenuPopup deleteMenu;
    /**
     * Latched the moment the backspace long-press menu fires, cleared on the next
     * fresh ACTION_DOWN on ⌫. While latched, all DELETE keycodes are dropped —
     * even if the menu has been dismissed — so a still-held finger from the
     * original hold (e.g. user tapped Clear with a second finger and then pasted
     * fresh text) can't keep shredding what they just put back.
     */
    private boolean backspaceHoldConsumed;
    private VoiceInputController voice;
    private IntegrationRegistry integrations;
    /** True while {@link #onStartInputView} has fired and {@link #onFinishInputView}
     *  has not. When false, image results are surfaced as a notification instead of
     *  the in-keyboard preview because the keyboard isn't on screen. */
    private boolean inputViewVisible;

    /** Set after a picker stages an image so the IME pulls itself back into view
     *  once the shim activity finishes and the host editor re-binds. Cleared on
     *  the first {@link #onStartInput} fire-after-stage or by the delayed fallback
     *  in {@link #onEditImageStaged}, whichever wins. */
    private boolean pendingShowAfterPick;

    /** Reference to the currently-mounted emoji panel, when one exists. The key
     *  handler reads {@link com.prince.turtlekeyboard.ime.view.EmojiPanelView#isInSearchMode()}
     *  on this to route keystrokes to the search field instead of committing
     *  them to the host editor. */
    @Nullable private com.prince.turtlekeyboard.ime.view.EmojiPanelView activeEmojiPanel;

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
        if (kv instanceof TurtleKeyboardView) {
            TurtleKeyboardView tkv = (TurtleKeyboardView) kv;
            tkv.setModeKeyLongPressListener(() -> {
                KeyboardController.Layout target = keyboard.active() == KeyboardController.Layout.DIALPAD
                        ? KeyboardController.Layout.QWERTY
                        : KeyboardController.Layout.DIALPAD;
                keyboard.setLayout(target);
                kv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            });
            tkv.setBackspaceLongPressListener(() -> {
                backspaceHoldConsumed = true;
                if (deleteMenu != null) {
                    deleteMenu.showAbove(kv, Keycodes.DELETE);
                    kv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
            });
        }
        keyboardView = kv;
        preview = new KeyPreviewPopup(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);

        committer = new InputCommitter(this::getCurrentInputConnection);
        deleteMenu = new BackspaceMenuPopup(this);
        deleteMenu.setActionListener(new BackspaceMenuPopup.ActionListener() {
            @Override public void onClearAll() { committer.clearAll(); refreshSuggestions(); }
            @Override public void onDeleteWord() { committer.deleteWord(); refreshSuggestions(); }
            @Override public void onDeleteSentence() { committer.deleteSentence(); refreshSuggestions(); }
        });
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
                // While typing a prompt the strip should show word predictions,
                // not the host-paste preview pill (which otherwise owns the
                // center and hides the suggestion slots). The panel grows its
                // own 📋 button below so the user can still paste — into the
                // prompt buffer, not the host editor.
                root.strip().setPasteText(null);
                // Surface a one-tap paste affordance when the clipboard has text — drops
                // a URL / quote / phrase straight into the prompt without re-typing.
                root.panel().setPasteAvailable(readClipboardText());
                // Seed the suggestion strip with predictions for the empty
                // prompt so the user sees options the moment the panel opens.
                refreshPromptSuggestions("", 0);
                // /edit, /style, /gif, /gift, /sticker can all consume an input image
                // — launch the picker now so the user picks first, then comes back and
                // types the instruction (or for /style, just a preset name like
                // "ghibli"). /gif, /gift, and /sticker piggyback on the same staging
                // slot; their integrations consume it on dispatch. /sticker is the only
                // one of these where the photo is optional — if the user dismisses the
                // picker, StickerIntegration falls back to text-to-image.
                if ("edit".equals(commandName) || "style".equals(commandName)
                        || "gif".equals(commandName) || "gift".equals(commandName)
                        || "sticker".equals(commandName)) {
                    // Surface a 📷 button beside the label so the user can retry
                    // the picker if they dismissed the sheet without selecting
                    // (or want to swap the staged image). The button auto-hides
                    // while a thumb is staged.
                    root.panel().setUploadAction(
                            TurtleInputMethodService.this::launchEditImagePicker);
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
            @Override public void onPromptChanged(String commandName, String query, int cursorPos) {
                root.panel().update(query, cursorPos);
                // Keep word predictions flowing while the user types a
                // command's prompt — the suggestion strip is hidden too
                // aggressively otherwise and the user types blind. We feed
                // it the prompt's last-word context, same as a regular
                // text-input flow would.
                refreshPromptSuggestions(query, cursorPos);
            }
            @Override public void onComposeEnd() {
                root.banner().clear();
                root.cmdSuggestions().hide();
                root.panel().hide();
                root.presetStrip().hide();
                // Restore the strip's paste-preview pill for normal text
                // editing — we hid it on prompt entry so word suggestions
                // could show through.
                root.strip().setPasteText(readClipboardText());
                // Repaint the suggestion strip from the host editor's
                // content; otherwise the strip still shows the
                // prompt-context predictions from the last keystroke
                // before the user dispatched or cancelled.
                refreshSuggestions();
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
        // Tap / drag inside the query area positions the composer's caret;
        // subsequent keystrokes insert at that offset rather than always
        // appending to the end.
        root.panel().setOnCursorMoveListener(composer::setPromptCursor);
        // When the picker stages a new /edit image (or clears one) we want to show a
        // thumbnail in the prompt panel and bring the IME back to the foreground —
        // the picker activity stole focus and many hosts don't auto-resume the IME.
        LmStudioAiClient.setOnImageStagedListener(this::onEditImageStaged);
        root.strip().setOnPickListener(this::onSuggestionPicked);
        root.strip().setOnMicTapListener(this::toggleVoiceInput);
        root.strip().setOnPasteTapListener(this::pasteFromClipboard);
        root.strip().setOnSettingsTapListener(this::openHostDetailView);
        root.strip().setOnEmojiTapListener(this::toggleEmojiPanel);
        root.cmdSuggestions().setOnPickListener(this::onCommandSuggestionPicked);
        root.cmdSuggestions().setOnDismissListener(this::dismissCommandSuggestions);

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
        // Single shared MCP client — pure JSON-RPC tools/call transport. Endpoint URL
        // and per-user auth token are owned by the calling integration (mirrors how
        // each integration owns its system prompts for ctx.ai()).
        com.prince.kbd.core.McpService mcp = new com.prince.ai.McpClient();
        // Shared Google OAuth — every module that hits Google APIs (Split for Sheets/Drive,
        // Drive for /us reference photos, future Calendar / Gmail / Photos integrations)
        // reuses this single instance via ctx.googleAuth(). Storage in the "google" namespace
        // keeps token state consistent across modules and across host activities.
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
                        if (!insertImage(uri, mime)) copyToClipboard(uri, mime);
                    }
                };
        // SPI-routed picks land here on the main thread (ImagePickerActivity's
        // onActivityResult posts via PickerResultBus). Legacy /edit picks don't
        // touch this listener — they stay on LmStudioAiClient.setOnImageStagedListener.
        //
        // Mirrors the /edit onEditImageStaged dance precisely:
        //   1. requestShowSelf attempted now AND via the pendingShowAfterPick flag
        //      so the IME re-shows whether onStartInput fires (editor refocuses) or
        //      not (only the IME window lost focus).
        //   2. The integration's callback — which may mount UI via ctx.showPanel —
        //      runs through root.post(...) so the keyboard view tree is settled
        //      (and ideally already visible from the requestShowSelf above) before
        //      we touch panelHost.
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

                    // Kick the IME show NOW (often a no-op if input isn't ready yet)
                    // and arm the deferred fallback.
                    pendingShowAfterPick = true;
                    requestShowSelf(0);
                    mainHandler.postDelayed(() -> {
                        if (pendingShowAfterPick) {
                            pendingShowAfterPick = false;
                            requestShowSelf(0);
                        }
                    }, 250);

                    // Defer the callback so any UI it mounts (ctx.showPanel) lands
                    // on a settled view tree, after the IME's first show pass.
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
                // PuzzleIntegration first so its activate(...) gets first crack
                // when a /puzzle config flow is mid-pick — it re-mounts the
                // pending panel that the registry's onInputEnd→deactivate wiped
                // when the picker activity stole focus. Returns null in the
                // common no-pending case, so other integrations still claim
                // their sessions normally.
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
                // Generic MCP integration — registers N slash commands at construction
                // time, one per user-added McpBinding from the host app's MCP Servers
                // screen. Must come last so user bindings can't shadow built-in commands.
                new UserMcpIntegration(integrationCtx));
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

        // Emoji-search mode owns every keystroke while it's open: characters
        // build up the query, backspace pops the last char (and exits on
        // empty). We swallow function keys here so e.g. SHIFT doesn't leak
        // into the host editor while the search bar is the visible target.
        if (activeEmojiPanel != null && activeEmojiPanel.isInSearchMode()) {
            if (primaryCode == Keycodes.DELETE) {
                activeEmojiPanel.backspaceQuery();
                return;
            }
            if (primaryCode > 0) {
                activeEmojiPanel.appendQueryChar((char) primaryCode);
                return;
            }
            return;
        }

        // Mic is global: works the same whether the user is mid-compose or
        // typing into the host editor. The sink picks the destination.
        if (primaryCode == Keycodes.MIC) {
            toggleVoiceInput();
            return;
        }
        if (primaryCode == Keycodes.EMOJI) {
            // The dedicated panel lives on the suggestion strip; routing the
            // hardware key here gives users the same affordance on layouts
            // that include an emoji key in their bottom row.
            toggleEmojiPanel();
            return;
        }

        if (composer.isActive() && handleComposingKey(primaryCode)) return;

        switch (primaryCode) {
            case Keycodes.DELETE:
                // Once a hold has triggered the long-press menu, drop every DELETE
                // event from that same touch sequence — even after the menu closes.
                // Otherwise a still-held finger keeps the framework's MSG_REPEAT
                // firing and chews through text the user pastes back. Cleared on
                // the next fresh ACTION_DOWN on ⌫ (see onPress).
                if (backspaceHoldConsumed) break;
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
     *  it into whichever app they're now in. If {@code originPkg} is the app the
     *  user was prompting from (e.g. WhatsApp), a one-tap "Share to <App>" action
     *  is added that routes the ACTION_SEND straight to that package — skipping
     *  the chooser. */
    private void notifyImageReady(java.io.File img, Uri uri, @Nullable String originPkg) {
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
        // One-tap share back to the originating app. Two gotchas:
        //   1. Intent.resolveActivity(pm) calls MATCH_DEFAULT_ONLY and misses
        //      non-default receivers (e.g. WhatsApp's ContactPicker), so we
        //      use queryIntentActivities(0) and pin the explicit component
        //      that handles it. With a hard ComponentName, tapping the action
        //      bypasses the system chooser entirely.
        //   2. The FileProvider URI permission grant on a PendingIntent
        //      doesn't reliably reach a setPackage target — grant it
        //      explicitly so the target can actually read the image.
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
                // Explicit URI grant — survives the PendingIntent hop into the
                // target package's process.
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
        root.post(() -> root.panel().setStagedImage(thumb, () ->
                LmStudioAiClient.stageEditImage(null, null)));
        // The listener fires from ImagePickerActivity.onActivityResult — *before*
        // the shim finishes and the host editor re-binds, so requestShowSelf is a
        // no-op at this instant. Park a flag for onStartInput to consume when the
        // editor reconnects, and schedule a delayed fallback in case the EditText
        // never lost focus (only the IME window did) and onStartInput won't fire.
        pendingShowAfterPick = true;
        mainHandler.postDelayed(() -> {
            if (pendingShowAfterPick) {
                pendingShowAfterPick = false;
                requestShowSelf(0);
            }
        }, 300);
    }

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        if (pendingShowAfterPick) {
            pendingShowAfterPick = false;
            // Editor just re-bound after the picker shim tore down — request show
            // now that the IMM has a live connection to attach the IME to.
            mainHandler.post(() -> requestShowSelf(0));
        }
    }

    /** SPI variant of {@link #launchEditImagePicker}. Passes a request id through the
     *  picker so the result routes to the in-flight {@code ctx.pickImage()} callback
     *  via {@link com.prince.turtlekeyboard.ai.PickerResultBus}, instead of staging
     *  into {@code LmStudioAiClient}. */
    private void launchSpiImagePicker(int requestId) {
        android.content.Intent i = new android.content.Intent(this,
                com.prince.turtlekeyboard.ai.ImagePickerActivity.class);
        i.putExtra(com.prince.turtlekeyboard.ai.ImagePickerActivity.EXTRA_REQUEST_ID, requestId);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            android.util.Log.w("TurtleIME", "spi picker launch failed", e);
            // Fire the callback with null so the integration doesn't hang waiting.
            com.prince.turtlekeyboard.ai.PickerResultBus.deliver(requestId, null, null);
        }
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
            // Mini banner-row bars are redundant once the stage takes over —
            // keep one voice indicator on screen, not two.
            root.voiceListening().stop();

            // The stage is a translucent overlay; we want it to occupy the
            // same vertical slot as the keyboard without growing the IME
            // window. Trick: height = keyboard.height, topMargin = -that —
            // so its contribution to the LinearLayout's height is 0 while
            // it visually overlaps the keys.
            int kh = root.keyboardView().getHeight();
            if (kh > 0) {
                android.view.ViewGroup.LayoutParams lp = root.voiceStage().getLayoutParams();
                lp.height = kh;
                if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                    ((android.widget.LinearLayout.LayoutParams) lp).topMargin = -kh;
                }
                root.voiceStage().setLayoutParams(lp);
            }

            // Mic stays put in the suggestion strip throughout listening, so
            // its window-space centre is stable; the stage resolves the local
            // coordinate inside post() once layout settles.
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
            root.banner().showAndAutoHide(userVisibleMessage, 1500);
        }
    };

    @Override
    public void onDestroy() {
        if (voice != null) { voice.destroy(); voice = null; }
        super.onDestroy();
    }

    private void refreshSuggestions() {
        // While the composer is in PROMPT mode the host editor doesn't yet
        // hold the typed text — it's all sitting in the composer buffer —
        // so reading committer.textBeforeCursor here would yield whatever
        // was in the host before the user started the slash flow. Route
        // through the prompt-context path instead so suggestions track the
        // panel's actual content.
        if (composer != null && composer.isActive()
                && composer.mode() == com.prince.turtlekeyboard.command.CommandComposer.Mode.PROMPT) {
            refreshPromptSuggestions(composer.query(), composer.promptCursor());
            return;
        }
        if (suggestionProvider == null || committer == null || root == null) return;
        List<String> list = suggestionProvider.suggest(committer.textBeforeCursor(64));
        root.strip().setSuggestions(list);
    }

    /** Sibling of {@link #refreshSuggestions} for the slash-command prompt
     *  flow. The composer's buffer holds the typed prompt (it never reaches
     *  the host editor until dispatch), so the regular committer-based path
     *  would return whatever's in the host instead of the prompt's text.
     *  This feeds the suggestion provider the prompt substring up to the
     *  caret so word predictions track what the user is actually typing
     *  inside the panel. */
    private void refreshPromptSuggestions(String query, int cursorPos) {
        if (suggestionProvider == null || root == null) return;
        String q = query == null ? "" : query;
        int safeCursor = Math.max(0, Math.min(q.length(), cursorPos));
        String context = q.substring(0, safeCursor);
        if (context.length() > 64) {
            context = context.substring(context.length() - 64);
        }
        List<String> list = suggestionProvider.suggest(context);
        root.strip().setSuggestions(list);
    }

    private void onSuggestionPicked(String suggestion) {
        // In PROMPT mode the typed text lives in the composer buffer, not in
        // the host editor. Route the pick through the composer so the prompt
        // panel updates and the committer doesn't accidentally drop the
        // suggestion straight into the host before the user hits Go.
        if (composer != null && composer.isActive()
                && composer.mode() == com.prince.turtlekeyboard.command.CommandComposer.Mode.PROMPT) {
            String q = composer.query();
            int cursor = composer.promptCursor();
            int wordStart = cursor;
            while (wordStart > 0 && !Character.isWhitespace(q.charAt(wordStart - 1))) {
                wordStart--;
            }
            // Backspace the partial word the user has typed so far so the
            // picked suggestion replaces it cleanly. We loop instead of a
            // direct buffer edit because backspace is what fires the right
            // composer Ui callback to repaint the panel.
            for (int i = cursor; i > wordStart; i--) composer.backspace();
            composer.appendString(suggestion + " ");
            if (suggestionEngine != null) suggestionEngine.learn(suggestion);
            // No explicit refresh — appendString fires onPromptChanged which
            // re-runs refreshPromptSuggestions for the next word context.
            return;
        }
        // Default path: normal text editing against the host editor.
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

    /** Top-left 😀 chip on the suggestion strip toggles the emoji panel.
     *  Reuses the same {@code quickPanelHost} slot the slash-command Quick Panel
     *  mounts into — emoji and Quick Panel are mutually exclusive UIs, both
     *  replacing the keys at the same vertical band. */
    private void toggleEmojiPanel() {
        if (isEmojiPanelVisible()) {
            hideEmojiPanel();
            return;
        }
        // Close any sibling panel that might be holding the slot.
        if (isQuickPanelVisible()) hideQuickPanel();
        showEmojiPanel();
    }

    private boolean isEmojiPanelVisible() {
        android.view.ViewGroup host = root.quickPanelHost();
        if (host.getVisibility() != View.VISIBLE || host.getChildCount() == 0) return false;
        return host.getChildAt(0) instanceof com.prince.turtlekeyboard.ime.view.EmojiPanelView;
    }

    private void showEmojiPanel() {
        android.view.ViewGroup host = root.quickPanelHost();
        final com.prince.turtlekeyboard.ime.view.EmojiPanelView panel =
                new com.prince.turtlekeyboard.ime.view.EmojiPanelView(this);
        panel.applyTheme(themes.current());
        panel.show(this::commitEmoji, this::hideEmojiPanel);
        // GIF tab tile → commit the .gif inline (image/gif) into the focused
        // host editor, then close the panel so the user sees it land. Mirrors
        // the same FileProvider + commitContent flow used right after /gif
        // generation, but sourced from history instead of a fresh encode.
        panel.setOnGifPickListener(this::insertHistoryGif);

        final android.inputmethodservice.KeyboardView keys = root.keyboardView();
        int targetHeight = keys.getHeight();
        if (targetHeight <= 0) targetHeight = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT;
        // Tell the panel what height to restore to when the user backs out of
        // search mode — without it the panel would stay at the shrunken size.
        panel.setBrowseHeightPx(targetHeight);
        // Re-show the keys while the search bar is open so the user has
        // something to type into; collapse them again on exit.
        panel.setOnSearchStateListener(new com.prince.turtlekeyboard.ime.view.EmojiPanelView.OnSearchStateListener() {
            @Override public void onEnterSearch() { keys.setVisibility(View.VISIBLE); }
            @Override public void onExitSearch()  { keys.setVisibility(View.GONE);    }
        });

        host.removeAllViews();
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, targetHeight);
        host.addView(panel, lp);
        host.setVisibility(View.VISIBLE);
        keys.setVisibility(View.GONE);
        activeEmojiPanel = panel;
    }

    private void hideEmojiPanel() {
        activeEmojiPanel = null;
        // Same teardown as the Quick Panel — they share the slot.
        hideQuickPanel();
    }

    /** Drop the picked glyph into the host input field. Skips the slash-command
     *  pipeline (emojis are never command triggers) and goes straight to the IC
     *  so EmojiCompat-rendered text lands intact even on hosts that filter
     *  custom span text. */
    private void commitEmoji(String emoji) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || emoji == null || emoji.isEmpty()) return;
        ic.commitText(emoji, 1);
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
     *  the result preview, but skips the format picker — history files are PNGs
     *  or GIFs that hosts overwhelmingly accept. */
    private void insertHistoryImage(java.io.File file) {
        if (file == null || !file.exists()) {
            root.banner().showAndAutoHide("File missing", 1500L);
            return;
        }
        Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", file);
        hideQuickPanel();
        // Pick mime by extension — ImageHistory holds both .png and .gif
        // entries now, and committing a .gif under image/png would prevent
        // the host from animating it.
        String mime = file.getName().endsWith(".gif") ? "image/gif" : "image/png";
        if (!insertImage(uri, mime)) copyToClipboard(uri, mime);
    }

    /** Inserts a previously-generated GIF (from the emoji panel's GIFs tab)
     *  into the host field. Same FileProvider + commitContent path as
     *  {@link #insertHistoryImage}, but the mime is {@code image/gif} so
     *  chat hosts know to animate it. Closes the emoji panel after commit
     *  so the user sees the result land in the chat. */
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

    private void dismissCommandSuggestions() {
        // Close ✕ pill on the strip: exit NAME mode AND hide the strip so
        // further typing goes through as plain text instead of restarting
        // the suggestion flow on the next keystroke.
        composer.cancel();
        root.cmdSuggestions().hide();
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
            // Remember which app the user was in *as the request fires* — if
            // they background the keyboard before the result lands, the
            // notification can route a one-tap share straight back to it.
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
            String originPkg = pendingSourcePkg;
            pendingSourcePkg = null;
            notifyImageReady(source, uri, originPkg);
            return;
        }
        // Result landed while the keyboard is still visible — preview path
        // takes over, so drop the pending origin so a later command doesn't
        // inherit it.
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
        pendingSourcePkg = null;
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
        if (primaryCode == Keycodes.DELETE) {
            // Fresh ACTION_DOWN on ⌫ — release any suppression latched by the prior hold.
            backspaceHoldConsumed = false;
        } else if (deleteMenu != null && deleteMenu.isShowing()) {
            // Pressing any non-delete key dismisses an open delete menu.
            deleteMenu.dismiss();
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
