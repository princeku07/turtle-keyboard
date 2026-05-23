# iOS — CLAUDE.md

## What to read / what to ignore

Never open anything inside `TurtleKeyboard.xcodeproj/`. The pbxproj is hand-edited
(safe ID prefix `HH`) when adding files; everything else there is Xcode scaffolding.

Two targets in the Xcode project:

| Target | Bundle ID | Type |
|---|---|---|
| `TurtleKeyboard` | `com.samarth.turtlekeyboard` | Host app (onboarding + Connect screens + Personalization) |
| `TurtleKeyboardExtension` | `com.samarth.turtlekeyboard.keyboard` | Keyboard extension (`UIInputViewController`) |

Both targets share the App Group `group.com.samarth.turtlekeyboard.split` (see
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
| `PersonalizationViewController.swift` | Per-integration on/off + Quick Panel / voice toggles + theme picker. Writes keys defined in `Integration/PersonalizationKeys.swift`. |
| `HistoryViewController.swift` | Browser for the persistent `/cap` / `/org` image history (the same store the keyboard's in-line history panel reads from). |
| `PollSheetViewController.swift`, `WyrSheetViewController.swift` | Host-app sheets the `/poll` and `/wyr` keyboard integrations push the user into to author a poll / WYR round; result lands back in the chat field via the App Group. |
| `VoiceRecordingViewController.swift`, `VoiceSessionManager.swift` | Host-app voice capture screen + session manager. Used when the keyboard mic taps over to the host app for long-form dictation (the extension itself runs the short-form `SFSpeechRecognizer` path). Final transcript is dropped into the App Group; the keyboard picks it up on next mount. |
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
| `KeyboardViewController.swift` | The keyboard UI: dynamic-height layout, key handling, command bar, slash-autocomplete strip, word/shortcut suggestions, preview overlay, integration panel host, Quick Panel host, voice mic, draft-state persistence. Single class, ~2500 lines — see the architecture section below before reaching for it. |
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
| `KeyboardTheme.swift`, `KeyboardThemeManager.swift` | Theme descriptors + resolver that reads the user's Personalization choice from the App Group store and re-stamps `KeyboardPalette.current` on every keyboard mount / mode flip. |
| `KeyRows.swift` | Static iPhone + iPad key-row data for QWERTY / symbols / shifted symbols. **iPhone bottom row is `[?123, space, /, ↵]`** — the system globe is dropped in favour of a dedicated `/` (the slash-command trigger) so the user never has to hop into symbols mode just to start a command. |
| `CommandSuggestionStripView.swift` | The slash-autocomplete chip strip mounted above the command bar while the user is mid-draft. Stays visible for the entire draft state — disappears only when the typed body matches zero commands or the user commits with space. |
| `PresetChipStripView.swift` | Inline preset chips for prompt-needing commands (e.g. `/tone` → `formal`, `friendly`, `concise`). Shown inside the command-bar prompt slot before the user starts typing. |
| `HistoryPanelView.swift` | In-keyboard `/history` panel that browses past `/cap` / `/org` outputs via `ImageHistory` and re-runs the preview surface for each. |
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
| `Poll/` | `/poll`. Mounts a poll-config sheet in the host app, then back-fills a formatted poll into the chat field. |
| `Wyr/` | `/wyr` (Would You Rather). Same host-app-sheet pattern as Poll. |
| `Web/` | `/web`. Mounts an in-keyboard `WKWebView` panel via `IntegrationContext.showPanel`. |
| `SuggestedShortcut.swift`, `SuggestedShortcutCatalog.swift` | The chip-strip suggestions shown when the field is empty (e.g. quick-reply seeds). Powers `suggestionMode == .suggestedShortcuts`. |
| `WorkerUrls.swift` | Shared Cloudflare Worker base URLs used by integrations that proxy through the closed-source backend. |

### AI providers — `TurtleKeyboardExtension/AI/`

Pluggable model stack. Adding a model = drop a file here + register it in
`CommandRouter`.

| File | What it is |
|---|---|
| `AITypes.swift` | `AIProvider` protocol, `ProviderID` enum, `AIModel`, `ModelCapability`, `CommandPayload`, `CommandResult`, `ProviderError`. Shared `parseSuggestionsJSON(_:)`. |
| `ModelRegistry.swift` | Static catalog of known models + `compatible(with:)` filtering. |
| `CommandRouter.swift` | Maps each slash command → `AIModel` → provider. Centralises system prompts (with `PromptLoader` fallback for asset-backed prompts). `imageDefault` (Flash) drives `/cap`, `/edit`, `/style`, `/sticker`; `imageProDefault` (Gemini 3 Pro Image Preview) drives `/gif` — the sprite-sheet path needs Pro to hold the 4×N grid layout reliably. |
| `KeyStore.swift` | Subscript-based UserDefaults wrapper for API keys (uses the App Group suite). |
| `PromptLoader.swift` | Reads shared system prompts from the bundle (`<id>.txt`). The Run Script build phase copies them from `commands/prompts/` at build time. |
| `GoogleProvider.swift` | **Primary stack.** Direct HTTP to `generativelanguage.googleapis.com`. Text → `gemini-2.5-flash-lite`; image generation/edit → `gemini-2.5-flash-image` ("Nano Banana"); sprite-sheet edit → `gemini-3-pro-image-preview` ("Nano Banana Pro"). Hosts the bespoke `/sticker` and `/gif` two-pass matte pipelines (`runStickerPipeline`, `runGifPipeline`) and the shared `encodeAnimatedGIF` helper that writes disposal-method-2 GIF89a via ImageIO. |
| `AlphaMatte.swift` | Difference-matte math shared by `/sticker` and `/gif`. Given two `CGImage`s (white-bg + black-bg renders of the same scene) computes per-pixel alpha via `1 − pixelDist / sqrt(3·255²)` and back-solves true RGB from the black render. Snap thresholds at 0.95 / 0.05 clean up model-side RGB drift. |
| `SpriteSheetSlicer.swift` | Slices a sprite-sheet `CGImage` into `cols × rows` equal cells in row-major order. Locks `cols = 4` per the `/gif` prompt; `gridRows(forAspect:)` detects 4×4 / 4×2 / 4×1 from the sheet's aspect ratio; `frameDelayCentiseconds(forRows:)` returns the matching per-frame delays so each layout loops in ≈ 1 s. |
| `MattePrompts.swift` | The fixed pass-2 user prompts for the matte pipelines. `swapWhiteToBlackSubject` for `/sticker`; `swapWhiteToBlackSheet` for `/gif` (adds explicit "preserve cell grid / count / order" guardrails). |
| `StylePresets.swift` | Curated `/style` preset map (`ghibli`, `anime`, `pixar`, …). Mirrors Android's `STYLE_PRESETS` 1-to-1. `userPrompt(for:)` builds the full "Restyle this image as: …" sentence that ships in the user turn (`systemInstruction` is ignored by Gemini's image-edit models). |
| `LMStudioProvider.swift` | **Local fallback** when no Gemini key is set. POSTs to `http://192.168.1.10:1234/v1/chat/completions`, strips reasoning model `<think>` blocks. Routes here from `textDefault` when `Secrets.geminiApiKey` is empty. |
| `FalProvider.swift` | **Image fallback** when no Gemini key is set. fal.ai (Flux 2 by default; Flux Schnell + Flux Pro registered). Routes here from `imageDefault` / `imageProDefault` when `Secrets.geminiApiKey` is empty. |
| `ImageDownsizer.swift` | Caps inbound reference photos to a `maxSide` thumbnail before the request. **Two entry points**: `downsizedPNG(_ image:)` for callers that already hold a `UIImage`, and `downsizedPNG(fromData:)` which uses `CGImageSourceCreateThumbnailAtIndex` to decode-direct-to-thumbnail without ever materialising the full-resolution frame — this is what stops PHPicker from blowing the keyboard extension's ~50 MB memory ceiling. |
| `ImageVariants.swift` | Per-pasteboard-pill encoder. `make(_:variant:)` produces `(Data, UTI)` tuples for `Image` (PNG passthrough), `Sticker` (512×512 centered PNG, **alpha-preserving** — must stay this way or `/sticker`'s matte output gets flattened on white at the variant step), and `GIF` (single-frame GIF89a via ImageIO). |
| `ImageHistory.swift` | Persistent record of `/cap` / `/org` outputs for the `/history` panel + the host-app `HistoryViewController`. |
| `AnthropicProvider.swift`, `OpenAIProvider.swift` | Cloud providers registered for completeness; not on any default route yet because the host app doesn't surface UI for the user to enter their keys. Wire `KeyStore.shared[.anthropic] = "…"` from somewhere to enable. |
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
fix is to register `group.com.samarth.turtlekeyboard.split` on your Apple Developer
account and add the App Groups capability in Xcode → Signing & Capabilities;
then this flag can be removed.

---

## KeyboardViewController architecture

Single file (~2500 lines). Key sections in order:

1. **State** — `SlashCommand` use, `KeyboardMode`, shift/caps flags, double-tap timers, `slashBuffer` (single source of truth for any in-progress slash command), `previewOverlay` state, voice + Quick Panel state.
2. **Layout constants** — iPhone vs iPad dimensions (`isPad` switch). Per-device: `rowH`, `rowGap`, `commandBarH`, `previewH`. Total keyboard height is **dynamic**, computed each frame as `effectiveChromeH + rowsH`:
   - The command-bar slot (`commandBarH`) is **always** reserved at the top — even when empty — so word-suggestion / shortcut chips appearing or disappearing don't make the keys jump.
   - The slash autocomplete strip (`slashStripH = 34`) stacks above the command bar when there are matches.
   - The image-preview slot (`previewH` = 260 iPhone / 300 iPad) **displaces** the command-bar slot when `/cap`, `/org`, etc. produce a result — preview sits at the top, keys stay tappable underneath.
3. **Palette** — pulled from `Keyboard/KeyboardPalette.swift`, re-stamped from `KeyboardThemeManager` when the user changes themes in Personalization.
4. **Key row data** — pulled from `Keyboard/KeyRows.swift`. iPad has 5 rows; iPhone 4. iPhone bottom row is `[?123, space, /, ↵]` (no system globe).
5. **`setupContainers()`** — Auto Layout. `keyboardContainer` is pinned to all four sides of `view`; `view.heightAnchor` carries the dynamic `heightConstraint` that `recomputeKeyboardHeight()` writes to. Slash strip, command bar (or banner in its place), preview overlay, integration panel, Quick Panel, and listening overlay all mount inside the container.
6. **`buildKeyboard()` / `buildRow()`** — frame-based layout *inside* `keyboardContainer`. Persistent overlays (`commandBar`, `bannerContainer`, `slashStrip`, `previewOverlay`, `integrationPanelHost`, `quickPanelView`, `listeningOverlay`) are excluded from the per-rebuild teardown and `bringSubviewToFront`-ed afterwards so a rebuild triggered by a height change doesn't yank them out from under the user.
7. **`recomputeKeyboardHeight()`** — single sink for height changes. Reads `effectiveChromeH`, writes `heightConstraint.constant` + `preferredContentSize`, flushes layout, and kicks `rebuildKeyboard()` so frame-positioned key rows snap to the new y-origin. Every chrome toggle (`hideCommandBar`, `showSlashStrip`, `showImagePreview`, `dismissPreview`, etc.) routes through here.
8. **Command bar** — slash-command UI. Local commands resolve through `IntegrationRegistry.shared.command(named:)` and run inline; remote commands call `CommandRouter.shared.execute(...)` and dispatch the `CommandResult` to the right UI surface.
9. **Quick Panel** — `QuickPanelView` shown via double-tap space when the user has it enabled in Personalization. Mounted through `mountIntegrationPanel`, which anchors the panel host to the very **top** of `keyboardContainer` so it covers the always-reserved command-bar slot — otherwise the top strip of key rows would leak through above the grid.
10. **Voice mic** — `VoiceInputController` toggled from the mic key; partials drive a live banner.
11. **`/org` and `/cap` flow** — both produce a `UIImage` and call `showImagePreview(_:)`. The preview is a **fixed-height chrome slot** at the top of the keyboard (height = `previewH`) with an image + variant pill row (`Image · Sticker · GIF · ✕`). The keys remain mounted underneath at y = `previewH` and stay tappable so the user can keep typing the message they're sending the image into. Tapping a variant pill encodes the right format and drops it on `UIPasteboard.general` with the matching UTI — iOS keyboards cannot insert images directly, so the user long-presses the chat field and taps **Paste**.
12. **Word suggestions** — `UITextChecker` driven completions inside our own command bar (system shortcut bar suppressed via `inputAssistantItem.leadingBarButtonGroups = []`). The bar's slot is always reserved (see Layout constants), so chips appearing / disappearing never shift the keys.
13. **Draft state persistence** — `viewWillDisappear` stashes `slashBuffer` + a timestamp into the App Group `UserDefaults`; `viewDidAppear` reads it back if it's < 5 minutes old and replays through `updateCommandDetection()` so a user who switches apps mid-command (e.g. `/ca`, or `/cap a samurai cat`) returns to exactly the same surface. See `persistDraftState()` / `restoreDraftStateIfFresh()`.
14. **Backspace repeat** — `UILongPressGestureRecognizer` with 0.4s delay + 0.08s repeat interval.

---

## Key invariants — don't break these

- `preferredContentSize` and `heightConstraint.constant` are updated **only** inside `recomputeKeyboardHeight()`. Setting them from `viewWillAppear` / `viewDidLayoutSubviews` / individual visibility toggles causes a feedback loop that either grows the keyboard full-screen or fights the in-flight chrome animation. Every chrome show/hide site must call `recomputeKeyboardHeight()` (or a function that does).
- The command-bar slot (`commandBarH`) is **always** reserved at the top of the keyboard — `effectiveChromeH` returns at least `commandBarH` whenever the preview is not showing. Don't add a conditional to collapse it to 0; it exists so word suggestions / shortcut chips don't shove the keys.
- `effectiveChromeH` returns `previewH` (not `previewH + commandBarH`) while the image preview is up. The preview owns the chrome slot — adding the command-bar slot on top of it would push the keys off-screen on iPhone.
- The image preview overlay is a **fixed-height top slot** (height = `previewH`), not a full overlay. Don't reintroduce a `bottomAnchor` constraint or set `isHidden = true` on the keys — the user is meant to keep typing while the preview is up.
- `integrationPanelHost` is anchored to `keyboardContainer.topAnchor` (constant `0`), not `+ commandBarH`. The host must cover the always-reserved command-bar slot or the iPad number row will visibly poke out above the Quick Panel / web panel.
- `buildKeyboard()` removes everything except `commandBar`, `bannerContainer`, `slashStrip`, `previewOverlay`, `integrationPanelHost`, `quickPanelView`, `listeningOverlay`, and re-fronts every preserved overlay after the rows are rebuilt. New persistent overlays must be added to both lists or a height change will yank them out mid-display.
- `slashBuffer` is the single source of truth for any in-progress slash command — everything else (`activeCommand`, `commandPromptText`, the chip strip, the right UI surface) is re-derived by `updateCommandDetection()`. Don't persist or restore other slash-related fields independently; round-trip the buffer.
- Draft state TTL is 5 minutes (`draftBufferTTL`). Keep it short — the extension has no reliable "same field" signal across launches, and a `/cap` resurfacing in an unrelated field after a long delay is a worse UX than just losing it.
- Width is always read from `UIScreen.main.bounds.width`, never `view.bounds.width` (which is 0 at layout time).
- `keyboardContainer`'s *contents* use frames (key rows); the container itself and persistent overlays use Auto Layout. Don't mix Auto Layout into `buildRow()`.
- `RequestsOpenAccess` in the extension `Info.plist` must stay `true` for HTTP requests to LM Studio / fal *and* for `SFSpeechRecognizer` to work. iOS will additionally require the user to flip "Allow Full Access" in Settings.
- The `192.168.1.10` ATS exception in both `Info.plist` files is required for plaintext HTTP to the LAN LM Studio endpoint. Don't broaden it.
- `OrgImageRenderer` is synchronous and runs on the main thread — fine because Core Graphics on a 500×500 canvas is sub-millisecond. Do **not** reintroduce a WKWebView path inside the keyboard extension; rAF is paused on detached webviews and the process is too memory-constrained for an in-hierarchy one.
- App Group ID is `group.com.samarth.turtlekeyboard.split` in both `.entitlements` files. The extension and host must agree on this string or the shared `UserDefaults(suiteName:)` reads return defaults and OAuth tokens look "missing" from the keyboard.
- pbxproj IDs use the `HH` prefix when added by hand. Pick the next free `HHxxxxxxHHxxxxxxHHxxxxxx` triple. The pbxproj sections that need every new file: `PBXBuildFile`, `PBXFileReference`, the relevant `PBXGroup`, and the `Sources` (or `Resources`) build phase of the owning target.

---

## Adding a new slash command

1. Add a `<id>.yaml` in `commands/` and (if needed) a `<id>.txt` in `commands/prompts/` — see repo-root `commands/README.md`.
2. Add a `case` to `SlashCommand` in `TurtleKeyboardExtension/Command/SlashCommand.swift`.
3. **Remote (AI) command:** update `requiredCapability(for:)` and `defaultRoutes` in `AI/CommandRouter.swift`. If the prompt is non-trivial, drop the `.txt` in `commands/prompts/` and add it to the Run Script's `inputPaths`/`outputPaths`. `PromptLoader.load(id:)` will pick it up.
4. **Local (integration) command:** add a `CommandSpec` to the relevant integration in `Integration/<Provider>/`, or create a new `KeyboardIntegration` and register it in `IntegrationRegistry`.
5. If the command produces an image, route the `CommandResult.text(...)` (or `.image(...)`) through `showImagePreview(_:)` so the user gets the same fixed-slot preview + `Image · Sticker · GIF · ✕` variant pills as `/org` and `/cap` (image stays above the keys, keys stay tappable).

---

## Adding a new integration

1. Create `TurtleKeyboardExtension/Integration/<Name>/<Name>Integration.swift` conforming to `KeyboardIntegration` (id + `commands() -> [CommandSpec]`).
2. Add a personalization key in `Integration/PersonalizationKeys.swift` and a switch case in `isEnabled(_:store:)`.
3. Register the integration in whatever bootstraps `IntegrationRegistry` (currently in `KeyboardViewController`).
4. If the integration needs OAuth: add a `*Auth.swift` under `TurtleKeyboard/Cloud/`, an OAuth callback URL scheme in `Info.plist`, and a Connect screen under `TurtleKeyboard/`. Document the provider setup steps in `ios/OAUTH_SETUP_iOS.md`.
5. Add a tile + nav entry from `ViewController.swift` and a row in `PersonalizationViewController.swift`.
