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
import com.prince.split.kbd.IntegrationContext;
import com.prince.split.kbd.KeyboardIntegration;
import com.prince.turtlekeyboard.input.InputCommitter;
import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.integration.IntegrationRegistry;
import com.prince.turtlekeyboard.integration.KeyboardIntegrationContextImpl;
import com.prince.turtlekeyboard.integration.PersistentAppProfileRegistry;
import com.prince.turtlekeyboard.keyboard.KeyboardController;
import com.prince.turtlekeyboard.keyboard.Keycodes;
import com.prince.turtlekeyboard.keyboard.ShiftController;
import com.prince.turtlekeyboard.settings.Prefs;
import com.prince.turtlekeyboard.suggestion.BasicSuggestionProvider;
import com.prince.turtlekeyboard.suggestion.SuggestionProvider;
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
    private ThemeManager themes;
    private AudioManager audio;
    private KeyPreviewPopup preview;
    private KeyboardView keyboardView;
    private VoiceInputController voice;
    private IntegrationRegistry integrations;

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
            }
            @Override public void onPromptChanged(String commandName, String query) {
                root.panel().update(query);
            }
            @Override public void onComposeEnd() {
                root.banner().clear();
                root.cmdSuggestions().hide();
                root.panel().hide();
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
        suggestionProvider = new BasicSuggestionProvider();
        voice = new VoiceInputController(this);

        root.panel().setOnGoListener(this::dispatchPromptPanel);
        root.strip().setOnPickListener(this::onSuggestionPicked);
        root.cmdSuggestions().setOnPickListener(this::onCommandSuggestionPicked);

        // Build the integration context off the freshly inflated views, then construct the
        // registry — its constructor pumps each integration's commands into the registry.
        Prefs prefs = new Prefs(this);
        appProfiles = new PersistentAppProfileRegistry(getApplicationContext(), prefs);
        // One AiClient drives both the slash-command dispatcher and the module-side
        // LLM service — saves duplicate construction and keeps /notion talking to the
        // same backend as /cap, /fix, etc.
        LmStudioAiClient ai = new LmStudioAiClient(this, hostProvider, new StubAiClient());
        com.prince.split.kbd.LlmService llm =
                new com.prince.turtlekeyboard.integration.AiClientLlmService(ai);
        IntegrationContext integrationCtx = new KeyboardIntegrationContextImpl(
                getApplicationContext(), root, committer, prefs, appProfiles, llm);
        java.util.List<KeyboardIntegration> integrationList = java.util.Arrays.asList(
                new SplitIntegration(),
                new NotionIntegration(),
                new SlackIntegration());
        integrations = new IntegrationRegistry(integrationList, integrationCtx, registry);

        // Replay shortcut registrations for every enrolled app. Runs after module
        // commands are registered, so per-app suggestions sit alongside (not on top of)
        // module-owned triggers like /split.
        enrolledShortcuts = new com.prince.turtlekeyboard.integration.EnrolledShortcutsManager(
                appProfiles, registry,
                new com.prince.turtlekeyboard.integration.StaticSuggestedShortcutSource());
        enrolledShortcuts.registerAllEnrolled();

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
        refreshSuggestions();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (integrations != null) integrations.onInputEnd();
        hideEnrollmentBanner();
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
        char c = (char) code;
        if (Character.isLetter(c) && shift.isUpper()) c = Character.toUpperCase(c);
        committer.commitChar(c);
        if (code == Keycodes.SPACE) spaceGesture.onSpacePressed();
        shift.onCharCommitted();
        slashDetector.onTextChanged();
        refreshSuggestions();
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
        @Override public void onListeningStarted() { root.banner().show("🎤 Listening…"); }
        @Override public void onListeningStopped() { root.banner().clear(); }
        @Override public void onPartial(String text) {
            if (text != null && !text.isEmpty()) root.banner().show("🎤 " + text);
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
            root.banner().showAndAutoHide(userVisibleMessage, 1500);
        }
    };

    @Override
    public void onDestroy() {
        if (voice != null) { voice.destroy(); voice = null; }
        super.onDestroy();
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
        if (appProfiles.statusFor(pkg) != com.prince.split.kbd.AppProfileRegistry.Status.UNKNOWN) {
            hideEnrollmentBanner(); return;
        }

        com.prince.split.kbd.AppProfile profile = appProfiles.get(pkg);
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
        // PRD §6.6: double-tap-space toggles the Quick Panel — a 2-col grid of slash
        // commands that *replaces* the key area. A tile tap never writes "/<name>" to the
        // host editor; it hands off to the same composer the typed-slash flow uses, so
        // picking from the panel and typing the command produce identical UX.
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
        // Same hand-off as a Quick Panel tap — drop into the inline composer for an
        // explicit confirmation step. Host editor never sees "/<name>".
        composer.enterPromptMode(entry.name);
    }

    private void onQuickPanelPick(CommandRegistry.Entry entry) {
        hideQuickPanel();
        // Every pick — prompt or no-prompt — drops into the in-keyboard composer for an
        // explicit confirmation step. For prompt commands the user types an argument and
        // taps Go; for no-prompt commands they tap Go on an empty input. Picking from the
        // grid never auto-fires a command, and the host editor never sees "/<name>".
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
        root.banner().showAndAutoHide(message, 2000L);
    }

    @Override public void showSuggestions(String[] suggestions) {
        root.strip().setSuggestions(java.util.Arrays.asList(suggestions));
    }

    @Override public void showImage(String imagePayload) {
        if (imagePayload == null || imagePayload.isEmpty()) {
            root.banner().showAndAutoHide("Empty result", 1500L);
            return;
        }
        java.io.File source;
        int sep = imagePayload.indexOf('|');
        if (sep > 0) {
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
