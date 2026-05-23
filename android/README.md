# Turtle Keyboard — Android

Open-source, model-agnostic AI keyboard. Users type `/cap a samurai cat`,
`/edit make it sepia`, `/sticker chef hat`, etc. in any text field and get an
image or rewritten text back in ~1.5–2 s without leaving the host app.

Java + XML Views (not Kotlin/Compose). Min SDK 24, target SDK 34. The keyboard
backend (Gemini today, multi-provider later) is reached over HTTPS; image
staging stays on-device.

For product context, latency budgets, command roadmap, and trust/privacy
invariants, see [`../Readme.md`](../Readme.md) at the repo root.

---

## Module layout

The Android project is a multi-module Gradle build:

| Module | Purpose |
|---|---|
| `app/` | Keyboard service, host app, all IME views, AI client, integration host |
| `core/` | SPI surface — `CommandSpec`, `IntegrationContext`, `KeyboardIntegration`, `SheetRouter`, etc. Imported by every integration |
| `ai/` | Cloud AI helpers (`GeminiClient`, `McpClient`) implementing `core` SPI services |
| `split/` `notion/` `slack/` `web/` | First-party SPI integrations. Each declares its own commands + sheet routes |

`app` depends on every other module; integration modules depend only on `core`
(and optionally `ai`). The dependency graph fans inward so an integration can
ship without ever importing IME internals.

---

## Build & run

```bash
cd android
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # minified + shrunk release APK
./gradlew test                   # unit tests
./gradlew connectedAndroidTest   # instrumented (needs device/emulator)
```

### Required secrets

Add to `android/local.properties` (gitignored):

```properties
GEMINI_API_KEY=...
REVENUECAT_SDK_KEY=...
```

Both are optional for compilation — missing keys produce a build warning,
not an error, but runtime AI calls or paywall checks fail with a clear
upstream-service error. Open-source contributors without keys can still run
the keyboard; only the paid/AI surfaces stop working.

### First-run install

1. Build & install: `./gradlew installDebug`.
2. Open the **Turtle Keyboard** host app — it deep-links to system keyboard
   settings.
3. Enable the keyboard + select it in the IME picker.
4. Grant Full Access (required for HTTPS calls to the AI backend).
5. Optional: grant mic permission if you'll use voice input.

---

## Architecture

The IME class `TurtleInputMethodService` orchestrates everything: keyboard
input, panels, voice, slash-command dispatch, integrations, image picker
plumbing. It's deliberately the only Service in the app; almost everything
else is plain Java with single-responsibility classes.

### Top-of-keyboard view tree

`app/src/main/res/layout/keyboard_view.xml` declares a vertical stack:

```
KeyboardRootView
├─ HostAppBadgeView           # which app you're typing in
├─ AppEnrollmentBannerView    # one-shot integration onboarding
├─ IntegrationChipView        # tap chip → integration sheet
├─ integration_panel_host     # generic top slot — see PanelSlot section
├─ SuggestionStripView        # word suggestions + ✨ AI assist + mic + paste
├─ CommandPanelView           # /cap a samurai cat composer
├─ PresetChipStripView        # /us preset scenarios
├─ StylePreviewStripView      # /style thumbnails
├─ ImagePreviewView           # generated image with insert/copy/share
├─ CommandSuggestionStripView # / typing → ranked command list
├─ BannerView                 # transient text ("Rewriting…")
├─ VoiceListeningView         # mic mini-bar
├─ quick_panel_host           # full-band slot — replaces keys
├─ ShimmerView                # gradient progress bar
├─ GeneratingLoaderView       # full-band loading state
└─ TurtleKeyboardView         # the actual keys
```

Two **panel slots** decouple "what's mounted" from "how it mounts":

- `integration_panel_host` (top of keyboard, above the strip) — used by
  integration sheets (puzzle setup, web games) and the AI assist panel.
  Mounted via `PanelSlot`-style programmatic add/remove or direct visibility.
- `quick_panel_host` (replaces the keys) — used by emoji picker, history,
  more-actions, quick command grid. Mounted via the shared
  [`PanelSlot`](app/src/main/java/com/prince/turtlekeyboard/ime/view/PanelSlot.java)
  helper: `keyAreaPanel.show(panel)` / `hide()` / `isVisible()`.

### Key extension points

The IME used to grow a new `if (name.equals("X"))` branch every time a
command needed special behavior. Three registry patterns now absorb that
growth — adding a new command typically requires zero IME edits.

| Concern | Per-command registration | Lookup site |
|---|---|---|
| **What suggestions show in prompt mode** | `registry.setSuggestionSource(name, src)` — `PromptSuggestionSource.NONE` suppresses; custom sources return any list | `refreshPromptSuggestions` |
| **What image picker to pre-launch** | `registry.setImagePicker(name, ImagePickerKind.EDIT \| US \| NONE)` | `onPromptStart` switch |
| **What extra UI to show in prompt mode** | `registry.setPromptDecorator(name, dec)` — decorator implements `onStart` / `onQueryChanged` / `onEnd` and drives presets via a narrow `Ui` adapter | composer callbacks |

Built-in wiring lives in
[`BuiltinPromptUi.register`](app/src/main/java/com/prince/turtlekeyboard/command/BuiltinPromptUi.java)
next to `BuiltinAiCommands`. Integrations call the same registry methods in
their own setup code.

### Input ownership

Multiple components want to "own" typed keystrokes — the AI assist panel's
custom prompt field, the emoji panel's search bar, future panels. The
[`InputTarget`](app/src/main/java/com/prince/turtlekeyboard/input/InputTarget.java)
interface unifies this: each owner implements it, fires
`ActiveChangeListener.onActiveChanged(this, true/false)` on focus/blur, and
the IME's `onKey` + `voiceSink.onFinal` route to the single
`activeInputTarget` slot.

To add a new input-owning panel:

1. `implements InputTarget` (4 methods, 3 have defaults).
2. Expose `setOnInputActiveChangedListener(InputTarget.ActiveChangeListener)`.
3. Fire it on enter/exit of your input mode.
4. In the IME's panel construction, wire `setOnInputActiveChangedListener(inputTargetWatcher)`.

No `onKey` edits, no voice routing edits.

### Image staging pipeline

`ImagePickerActivity` (transparent shim Activity) ↔ IME ↔ AI client share
image bytes via
[`StagingPipeline`](app/src/main/java/com/prince/turtlekeyboard/ai/StagingPipeline.java),
held by `TurtleApp` (Application-scoped singleton). Both the activity and
IME look it up via `TurtleApp.from(ctx).stagingPipeline()`. No static
mutable state; listener lifecycle is per-instance and cleared in `onDestroy`.

### Voice input

`VoiceInputController` wraps Android's `SpeechRecognizer`. The
`voiceSink` in the IME routes the final transcription:

1. If an `InputTarget` is active → `activeInputTarget.appendText(text)`.
2. Else if the composer is active → into the composer buffer.
3. Else → `committer.commitText(text)` into the host editor.

Partial transcripts always render on the `VoiceStageView` overlay.

### AI dispatch

`TurtleAiClient` handles only the AI surface: HTTP calls to Gemini for
`/cap`, `/edit`, `/style`, `/us`, `/ask`, `/org`, plus the `rewrite()` entry
point used by the AI assist panel. Unknown commands fall through to the
injected `delegate` (typically `StubAiClient`), which is what SPI
integrations like `StickerIntegration`, `GifIntegration`, etc. override
via the `CommandSpec.handler` field. Per command, the integration owns
its prompt assets (`AssetPrompts.load(...)`) and decides cloud vs. local
model routing.

---

## How to add a new slash command

Pick the path that matches your command's nature.

### A) AI command with no special UI

Add to `BuiltinAiCommands.commands()`:

```java
new CommandSpec("translate", "Translate", "🌐", true, null,
                TRANSLATE_AFFINITY, "Translating")
```

`needsPrompt=true` means the composer goes into PROMPT mode after the user
types `/translate`. `handler=null` routes the command through `TurtleAiClient`;
add a branch there if the API call differs from `/ask`. For most cases, the
default `RAW_COMPLETION` path with a prompt asset works.

### B) AI command with preset chips or a special picker

Same `CommandSpec` registration, then add to `BuiltinPromptUi.register`
(or your integration's setup) **next to the existing /style and /us lines**:

```java
registry.setImagePicker("translate", ImagePickerKind.NONE);
registry.setSuggestionSource("translate", new LanguageNameSource(...));
registry.setPromptDecorator("translate", new PromptDecorator() {
    @Override public void onStart(Ui ui) {
        ui.showTextPresets(RECENT_LANGUAGES, onLanguageTap);
    }
});
```

Zero IME edits required.

### C) Local-handler command (no AI call)

Use the `handler` parameter on `CommandSpec`:

```java
registry.register(new CommandSpec(
    "history", "History", "🗂️", false,
    (prompt, ctx) -> showHistoryPanel()));
```

See `/history` registration in `TurtleInputMethodService.onCreateInputView`.

### D) Integration-owned command

Implement `KeyboardIntegration` (in your own module that depends on `core`),
return a `CommandProvider` from `commands()`, and have your `CommandSpec`
declare its handler. The handler receives an `IntegrationContext` with
`appContext`, `ai`, `mcp`, `googleAuth`, `imageBridge`, `showPanel`,
`showBanner`, etc. — see `StickerIntegration` for a worked example.

The integration is added to the IME's master list in `TurtleApp.integrations()`
(for sheet routes) and `TurtleInputMethodService.onCreateInputView` (for
command + chip dispatch). Both lists must stay in sync — a future cleanup
could share instances.

---

## Slash-command lifecycle

```
user types "/"
       │
       ▼
SlashCommandDetector.onTextChanged() sees a slash at a word boundary
       │
       ▼
CommandComposer.startName() — composer enters NAME mode
       │  every keystroke appends to the name buffer
       │
       ▼
CommandComposer.commitName() — fires on space or command match
       │  ► composerUi.onPromptStart(name)   ← IME shows CommandPanelView,
       │                                       pre-launches picker, mounts decorator
       │  ► composer enters PROMPT mode
       │
       ▼
each prompt keystroke
       │  ► composerUi.onPromptChanged(name, query, cursor)
       │      → refreshPromptSuggestions(query, cursor)
       │      → decorator.onQueryChanged(promptUi, query)
       │
       ▼
user taps Go (➤) on the composer
       │
       ▼
CommandDispatcher.dispatchComposed(slashCommand)
       │  ► aiClient.execute(cmd, callback)  — or integration handler
       │
       ▼
result arrives on the main thread
       │  ► IME inserts image / replaces text / shows error banner
       │  ► composerUi.onComposeEnd() — tear down panel + decorator
```

`SlashCommand` is just `{name, prompt, raw}`. `CommandDispatcher` picks the
right AI client (cloud TurtleAiClient, on-device Gemini Nano via
`OnDeviceAiClient` once allowlisted) and posts the callback.

---

## Where to start reading

If you're new to the codebase, in order:

1. `app/src/main/java/com/prince/turtlekeyboard/TurtleApp.java` (~110 lines) —
   Application setup, integration list, StagingPipeline holder.
2. `core/src/main/java/com/prince/kbd/core/CommandSpec.java` and
   `IntegrationContext.java` — the entire SPI shape in two files.
3. `app/src/main/java/com/prince/turtlekeyboard/command/BuiltinAiCommands.java`
   — the catalog of first-party commands.
4. `app/src/main/java/com/prince/turtlekeyboard/command/BuiltinPromptUi.java`
   — how those commands declare their UI quirks.
5. `app/src/main/java/com/prince/turtlekeyboard/ime/TurtleInputMethodService.java`
   — the IME orchestrator (large file; navigate by method).
6. `app/src/main/java/com/prince/turtlekeyboard/integration/sticker/StickerIntegration.java`
   — a complete worked example of a self-contained integration.

---

## Conventions

- **Java, not Kotlin.** Source is `.java`; only build files are `.kts`.
- **No Compose, no DataBinding.** XML Views + programmatic builders.
- **Comments**: Javadoc one-line headers; inline comments only for WHY,
  never WHAT. Don't reference past incidents, PR numbers, or removed code.
- **Tokens**: brand colors / dimens come from `design-system/tokens.json` →
  build to `app/src/main/res/values/*_tokens.xml`. Don't hand-edit the
  generated files; edit the JSON and run `node design-system/build.mjs`.
- **No paid design tools** for token authoring. Free Figma + free plugins
  only.
- **Privacy**: the keyboard never logs text outside of slash commands.
  This is a public invariant — assume any logging change has trust impact.
- **Latency**: hard budget is ≤ 2 s for `/cap`, ≤ 1.5 s for text commands.
  If a feature would add latency on the critical path, call it out.

---

## Testing

```bash
./gradlew test                   # JVM unit tests (incl. Robolectric); ~30s
./gradlew connectedDebugAndroidTest  # Espresso/UI Automator on a device or emulator
./gradlew lint                   # Android lint
```

### Layers

| Where | What runs | When |
|---|---|---|
| `app/src/test/` | JUnit4 + Robolectric. Pure Java + anything that needs `android.jar` shadows (BitmapFactory, InputConnection, ViewGroup). Fast. | Every PR via the `unit-test` job. |
| `app/src/androidTest/` | Espresso UI flows + UIAutomator. Slow (~6–10 min cold on emulator). | PRs only via the `instrumented-test` job; skipped on push to main. |

### What's covered

The IME orchestrator itself is hard to unit-test (the `InputMethodService`
framework needs a service host), so coverage targets the testable
abstractions we keep refactoring into:

- `command/CommandRegistry` — register/get, ranking, picker-kind /
  decorator / suggestion-source overrides.
- `command/CommandComposer` — NAME ↔ PROMPT state machine, caret math.
- `command/SlashCommandDetector` — slash-at-word-boundary recognition.
- `command/PromptSuggestionSource` — `NONE` + custom source contract.
- `ai/AiAssistPresets` — preset shape + `OUTPUT_RULES` discipline.
- `ai/StagingPipeline` — stage / consume / listener fire / read-and-clear
  across both edit and us slots.
- `input/InputCommitter` — null-IC safety, batch ordering for `replaceAll`,
  text-around-cursor concatenation.
- `ime/view/PanelSlot` — show/hide + keys-visibility flip.

Add a sibling test next to your new class. If you need Android types
(`Bitmap`, `View`, `InputConnection`), annotate `@RunWith(RobolectricTestRunner.class)`.
For pure Java logic, plain JUnit is enough.

### Writing an Espresso flow

`app/src/androidTest/.../MainActivitySmokeTest.java` is the seed test.
Pattern for new flows:

```java
@RunWith(AndroidJUnit4.class)
public class FooFlowTest {
    @Test public void someFlow() {
        try (ActivityScenario<FooActivity> scenario =
                 ActivityScenario.launch(FooActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);
            onView(withId(R.id.some_button)).perform(click());
            onView(withText("Expected")).check(matches(isDisplayed()));
        }
    }
}
```

Espresso doesn't drive the IME from outside its host process; for
keyboard-typing scenarios use `UiAutomator` or stand up a tiny test
Activity that hosts an `EditText` and select Turtle Keyboard as the IME
via `adb shell ime set …` in a test fixture. None of that is built yet —
contributions welcome.

---

## Common gotchas

- **`getCurrentInputConnection()` rebinds.** Always go through
  `InputCommitter.connection()` — caching the IC across an input session
  will silently no-op.
- **`InputMethodService` doesn't survive config changes well.** Theme/dark
  mode flips destroy and re-create the input view. Listeners registered in
  `onCreateInputView` must be cleared in `onDestroy` — see `onDestroy` for
  the canonical list.
- **The picker activity tears down the IME.** When you launch
  `ImagePickerActivity`, the keyboard goes away. The IME flags
  `pendingShowAfterPick=true` and posts a 300 ms fallback to re-show — see
  `requestShowSelfAfterPick()`. If you add a new flow that launches an
  Activity, use that helper.
- **Static state is the wrong default.** If you find yourself reaching for
  `private static` to share data between the activity, IME, and integrations,
  the right answer is almost always to extend `StagingPipeline` or hang a
  per-Application service off `TurtleApp`.
- **Don't hand-edit generated token files.** `colors_tokens.xml`,
  `dimens_tokens.xml`, `styles_tokens.xml` are output from
  `design-system/tokens.json`. Edit the JSON instead.

---

## License

MIT. The open-source surface is the keyboard itself; the backend routing
service (`/v1/command`, auth, model routing) is closed-source and not in
this repo.
