# iOS — CLAUDE.md

## What to read / what to ignore

Never open anything inside `TurtleKeyboard.xcodeproj/`. The pbxproj is hand-edited
(safe ID prefix `HH`) when adding files; everything else there is Xcode scaffolding.

Two targets in the Xcode project:

| Target | Bundle ID | Type |
|---|---|---|
| `TurtleKeyboard` | `com.turtlekeyboard` | Host app (onboarding + Connect screens + Personalization) |
| `TurtleKeyboardExtension` | `com.turtlekeyboard.keyboard` | Keyboard extension (`UIInputViewController`) |

Both targets share the App Group `group.com.turtlekeyboard.split` (see
`*.entitlements` files at the target root). Tokens, splits, and personalization
toggles live in the shared `UserDefaults` suite — the extension reads what the
host app writes.

---

## Host app — `TurtleKeyboard/`

| File | What it is |
|---|---|
| `AppDelegate.swift` | App entry. Routes `turtlekeyboard://join?...` deep links into `JoinSplitViewController`. |
| `ViewController.swift` | Onboarding screen — Enable / Switch keyboard, plus tiles into Split, Notion, Slack, Personalization. |
| `SplitDetailViewController.swift` | Split history list + cloud card (sign in, sync, invite/QR, stop sharing). |
| `JoinSplitViewController.swift` | Receives the joiner side of the `turtlekeyboard://join` deep link and points the local Split store at the owner's sheet. |
| `NotionConnectViewController.swift` | Notion OAuth + parent-page picker. |
| `SlackConnectViewController.swift` | Slack OAuth + default-channel picker. |
| `PersonalizationViewController.swift` | Per-integration on/off + Quick Panel / voice toggles. Writes keys defined in `Integration/PersonalizationKeys.swift`. |
| `Info.plist` | Host metadata + `CFBundleURLTypes` for all three OAuth callbacks (`com.googleusercontent.apps.*`, `turtleknotionoauth`, `turtleslackoauth`) + `NSLocalNetworkUsageDescription` + ATS exception for LM Studio LAN. |
| `TurtleKeyboard.entitlements` | App Group membership. |

### Host-app cloud module — `TurtleKeyboard/Cloud/`

Everything that talks to a cloud provider from the host app. The keyboard
extension *reads* the tokens these flows produce; it never runs OAuth itself.

| File | What it is |
|---|---|
| `SplitOAuth.swift` | Google PKCE OAuth via `ASWebAuthenticationSession`. Stores access + refresh tokens in `SplitKeychain`. |
| `SplitKeychain.swift` | Keychain wrapper for Google tokens (`kSecAttrAccessibleAfterFirstUnlock`). |
| `SplitSheetsClient.swift` | Sheets API: create the Turtle Splits spreadsheet, append rows, read sheet. |
| `SplitDriveClient.swift` | Drive API: create-permission for owner share, revoke permission for stop-sharing. |
| `SplitCloudSync.swift` | Glue between the local Split store and the cloud — migrate-on-first-signin, sync-now, joinSharedSheet. |
| `NotionAuth.swift` | Notion OAuth (auth code flow) + parent-page list cache. |
| `SlackAuth.swift` | Slack user-token OAuth + channel list cache. |
| `QrRenderer.swift` | `CIFilter.qrCodeGenerator()` → `UIImage`. Used by the Split invite share sheet. |

OAuth setup steps for Google / Notion / Slack live in `ios/OAUTH_SETUP_iOS.md`.

---

## Keyboard extension — `TurtleKeyboardExtension/`

| File | What it is |
|---|---|
| `KeyboardViewController.swift` | The keyboard UI: layout, key handling, command bar, suggestions, preview overlay, Quick Panel host, voice mic. Single class, ~1000 lines. |
| `Info.plist` | `RequestsOpenAccess = true` (required for network + speech), `NSMicrophoneUsageDescription`, `NSSpeechRecognitionUsageDescription`, ATS + Local Network keys for LM Studio. |
| `Models.swift` | Shared data types used outside the AI module. |
| `APIClient.swift` | Legacy HTTP client; current providers in `AI/` each use their own `URLSession`. Kept for reference. |
| `TurtleKeyboardExtension.entitlements` | App Group membership (must match host). |

### Slash commands — `TurtleKeyboardExtension/Command/`

| File | What it is |
|---|---|
| `SlashCommand.swift` | Canonical `enum SlashCommand` (`cap`, `fix`, `tone`, `reply`, `tl`, `ask`, `org`, `split`, `splits`, `notion`, `note`, `slack`, `msg`) + emoji + `needsPrompt` flag. Mirrors Android's `command/SlashCommand`. |

Remote commands (AI-backed) flow through `AI/CommandRouter`. Local commands
(`/split`, `/splits`, `/notion`, `/slack`, …) are owned by an integration in
`Integration/` and never hit the model.

### Keyboard UI — `TurtleKeyboardExtension/Keyboard/`

| File | What it is |
|---|---|
| `KeyboardPalette.swift` | Brand colours (turtle green bg, key gray, return-key green, command-bar bg). Pulled out so integration views can reuse them. |
| `KeyRows.swift` | Static iPhone + iPad key-row data for QWERTY / symbols / shifted symbols. |
| `QuickPanelView.swift` | Tap-driven grid of every registered slash command, opened by double-tap space. Tap a command: needs-prompt → opens command bar; otherwise fires immediately. |

### Voice — `TurtleKeyboardExtension/Voice/`

| File | What it is |
|---|---|
| `VoiceInputController.swift` | `SFSpeechRecognizer` + `AVAudioEngine`. `toggle(sink:)` on mic key, streams partial transcripts, fires final transcript on stop/silence/error. |

### Integrations — `TurtleKeyboardExtension/Integration/`

iOS keyboards can't detect the host app, so Android's per-package activation
doesn't translate. Each integration here contributes **slash commands only**;
there's no "this app is GPay, arm Split" logic. The user invokes integrations
explicitly via `/cmd`.

| File | What it is |
|---|---|
| `IntegrationKit.swift` | Shared protocols (`KeyboardIntegration`, `CommandSpec`) + `InputContext` (field traits read from `UITextDocumentProxy`). |
| `IntegrationRegistry.swift` | Lookup table from slash-command name → `CommandSpec`. Honours the Personalization on/off toggles when constructed with a `SplitStore`. |
| `PersonalizationKeys.swift` | Single source of truth for the personalization `UserDefaults` keys. Read by the registry, written by `PersonalizationViewController`. |
| `Split/` | `/split` + `/splits`. `SplitIntegration`, `SplitPanelView`, `SplitHistoryView`, `SplitTypes`. Local-only; no AI hop. |
| `Notion/` | `/notion` + `/note`. `NotionIntegration`, `NotionClient`, `NotionLlmBridge` (LLM-structures the prompt into Notion blocks), `NotionTypes`. |
| `Slack/` | `/slack` + `/msg`. `SlackIntegration`, `SlackClient`, `SlackTypes`. `#channel` prefix overrides the default channel. |

### AI providers — `TurtleKeyboardExtension/AI/`

Pluggable model stack. Adding a model = drop a file here + register it in
`CommandRouter`.

| File | What it is |
|---|---|
| `AITypes.swift` | `AIProvider` protocol, `ProviderID` enum, `AIModel`, `ModelCapability`, `CommandPayload`, `CommandResult`, `ProviderError`. Shared `parseSuggestionsJSON(_:)`. |
| `ModelRegistry.swift` | Static catalog of known models + `compatible(with:)` filtering. |
| `CommandRouter.swift` | Maps each slash command → `AIModel` → provider. Centralises system prompts (with `PromptLoader` fallback for `ask`/`org`). |
| `KeyStore.swift` | Subscript-based UserDefaults wrapper for API keys (uses the App Group suite). |
| `PromptLoader.swift` | Reads shared system prompts from the bundle (`<id>.txt`). The Run Script build phase copies them from `commands/prompts/` at build time. |
| `LMStudioProvider.swift` | **Current default for all text commands.** POSTs to `http://192.168.1.10:1234/v1/chat/completions`, strips reasoning model `<think>` blocks. |
| `FalProvider.swift` | Image gen via fal.ai (direct or Spark gateway). Used by `/cap`. |
| `AnthropicProvider.swift`, `GoogleProvider.swift`, `OpenAIProvider.swift` | Cloud providers. Implemented but **not registered** in `CommandRouter.providers` while we test against LM Studio. Re-add their entries to enable. |
| `OrgImageRenderer.swift` | **Core Graphics renderer** for `/org`. Decodes the JSON the model returns and rasterises a 500×500 PNG synchronously — no WebView. |
| `HTMLImageRenderer.swift`, `org_template.html`, `html-to-image.js` | **Legacy.** Earlier WKWebView + bubkoo/html-to-image path for `/org`. Kept in target but no longer called. Safe to delete. |

---

## Build

```bash
# From repo root — verify CI build passes before pushing
xcodebuild build \
  -project ios/TurtleKeyboard.xcodeproj \
  -scheme TurtleKeyboard \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO
```

To run on device: open `ios/TurtleKeyboard.xcodeproj` in Xcode, sign with your
team, select the **TurtleKeyboard** scheme (not the extension), `Cmd+R`. After
install, enable in **Settings → General → Keyboard → Keyboards → Add New
Keyboard → Turtle Keyboard**, then **toggle Allow Full Access**.

### Run Script build phases

The keyboard extension target has two pre-compile Run Script phases:

1. **`Scripts/load-env.sh`** — reads the repo-root `.env` and writes
   `ios/Generated/Secrets.swift` (typed constants) + `Secrets.xcconfig`. The
   key list lives at the top of the script; add new env keys there and in
   `.env.example`.
2. **Copy shared command prompts** — copies `commands/prompts/*.txt` from the
   repo root into the appex bundle. **When you add a new prompt file, also
   add its `inputPaths` and `outputPaths` entries** in the build phase or it
   won't be copied. Then route the command through `PromptLoader.load(id:)`
   in `CommandRouter`.

### Entitlements / signing note

Automatic signing may rewrite the App Group entry during a build before the
group is registered to your team, which trips the "Entitlements file was
modified during the build" error. Both targets currently ship with
`CODE_SIGN_ALLOW_ENTITLEMENTS_MODIFICATION = YES` to suppress this. The proper
fix is to register `group.com.turtlekeyboard.split` on your Apple Developer
account and add the App Groups capability in Xcode → Signing & Capabilities;
then this flag can be removed.

---

## KeyboardViewController architecture

Single file. Key sections in order:

1. **State** — `SlashCommand` use, `KeyboardMode`, shift/caps flags, double-tap timers, `previewOverlay` state, voice + Quick Panel state.
2. **Layout constants** — iPhone vs iPad dimensions (`isPad` switch). iPad: `rowH=42, rowGap=6, commandBarH=40, totalH=290` (constrained by iPad portrait input view height).
3. **Palette** — pulled from `Keyboard/KeyboardPalette.swift`.
4. **Key row data** — pulled from `Keyboard/KeyRows.swift`. iPad has 5 rows; iPhone 4.
5. **`setupContainers()`** — Auto Layout. `keyboardContainer` pinned to `view.bottomAnchor` with fixed `heightConstraint`. Banner + command bar + preview overlay + Quick Panel all live inside.
6. **`buildKeyboard()` / `buildRow()`** — frame-based layout *inside* `keyboardContainer`.
7. **Command bar** — slash-command UI. Local commands resolve through `IntegrationRegistry.shared.command(named:)` and run inline; remote commands call `CommandRouter.shared.execute(...)` and dispatch the `CommandResult` to the right UI surface.
8. **Quick Panel** — `QuickPanelView` shown via double-tap space when the user has it enabled in Personalization.
9. **Voice mic** — `VoiceInputController` toggled from the mic key; partials drive a live banner.
10. **`/org` and `/cap` flow** — both produce a `UIImage` and call `showImagePreview(_:)`, which surfaces the image in the preview overlay with **Copy** + **Close** buttons. iOS keyboards cannot insert images directly; user long-presses the chat field and taps **Paste**.
11. **Word suggestions** — `UITextChecker` driven completions inside our own command bar (system shortcut bar suppressed via `inputAssistantItem.leadingBarButtonGroups = []`).
12. **Backspace repeat** — `UILongPressGestureRecognizer` with 0.4s delay + 0.08s repeat interval.

---

## Key invariants — don't break these

- `preferredContentSize` is set **once** in `viewDidLoad` and updated only inside `rebuildKeyboard()`. Setting it in `viewWillAppear` / `viewDidLayoutSubviews` causes a feedback loop that grows the keyboard full-screen.
- Width is always read from `UIScreen.main.bounds.width`, never `view.bounds.width` (which is 0 at layout time).
- `keyboardContainer` uses Auto Layout (`bottomAnchor`); its *contents* use frames. Don't mix Auto Layout into `buildRow()`.
- `RequestsOpenAccess` in the extension `Info.plist` must stay `true` for HTTP requests to LM Studio / fal *and* for `SFSpeechRecognizer` to work. iOS will additionally require the user to flip "Allow Full Access" in Settings.
- The `192.168.1.10` ATS exception in both `Info.plist` files is required for plaintext HTTP to the LAN LM Studio endpoint. Don't broaden it.
- `OrgImageRenderer` is synchronous and runs on the main thread — fine because Core Graphics on a 500×500 canvas is sub-millisecond. Do **not** reintroduce a WKWebView path inside the keyboard extension; rAF is paused on detached webviews and the process is too memory-constrained for an in-hierarchy one.
- App Group ID is `group.com.turtlekeyboard.split` in both `.entitlements` files. The extension and host must agree on this string or the shared `UserDefaults(suiteName:)` reads return defaults and OAuth tokens look "missing" from the keyboard.
- pbxproj IDs use the `HH` prefix when added by hand. Pick the next free `HHxxxxxxHHxxxxxxHHxxxxxx` triple. The pbxproj sections that need every new file: `PBXBuildFile`, `PBXFileReference`, the relevant `PBXGroup`, and the `Sources` (or `Resources`) build phase of the owning target.

---

## Adding a new slash command

1. Add a `<id>.yaml` in `commands/` and (if needed) a `<id>.txt` in `commands/prompts/` — see repo-root `commands/README.md`.
2. Add a `case` to `SlashCommand` in `TurtleKeyboardExtension/Command/SlashCommand.swift`.
3. **Remote (AI) command:** update `requiredCapability(for:)` and `defaultRoutes` in `AI/CommandRouter.swift`. If the prompt is non-trivial, drop the `.txt` in `commands/prompts/` and add it to the Run Script's `inputPaths`/`outputPaths`. `PromptLoader.load(id:)` will pick it up.
4. **Local (integration) command:** add a `CommandSpec` to the relevant integration in `Integration/<Provider>/`, or create a new `KeyboardIntegration` and register it in `IntegrationRegistry`.
5. If the command produces an image, route the `CommandResult.text(...)` (or `.image(...)`) through `showImagePreview(_:)` so the user gets the same Copy/Close UX as `/org` and `/cap`.

---

## Adding a new integration

1. Create `TurtleKeyboardExtension/Integration/<Name>/<Name>Integration.swift` conforming to `KeyboardIntegration` (id + `commands() -> [CommandSpec]`).
2. Add a personalization key in `Integration/PersonalizationKeys.swift` and a switch case in `isEnabled(_:store:)`.
3. Register the integration in whatever bootstraps `IntegrationRegistry` (currently in `KeyboardViewController`).
4. If the integration needs OAuth: add a `*Auth.swift` under `TurtleKeyboard/Cloud/`, an OAuth callback URL scheme in `Info.plist`, and a Connect screen under `TurtleKeyboard/`. Document the provider setup steps in `ios/OAUTH_SETUP_iOS.md`.
5. Add a tile + nav entry from `ViewController.swift` and a row in `PersonalizationViewController.swift`.
