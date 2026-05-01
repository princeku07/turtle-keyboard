# iOS — CLAUDE.md

## What to read / what to ignore

Never open anything inside `TurtleKeyboard.xcodeproj/`. The pbxproj is hand-edited
(safe ID prefix `HH`) when adding files; everything else there is Xcode scaffolding.

### Host app — `TurtleKeyboard/`

| File | What it is |
|---|---|
| `AppDelegate.swift` | App entry point. Rarely changes. |
| `ViewController.swift` | Onboarding screen. Two buttons that deep-link to Settings. |
| `Info.plist` | Host app metadata. Holds `NSLocalNetworkUsageDescription` and ATS exception for the LM Studio LAN endpoint. |

### Keyboard extension — `TurtleKeyboardExtension/`

| File | What it is |
|---|---|
| `KeyboardViewController.swift` | The whole keyboard UI: layout, key handling, command bar, suggestions, preview overlay. ~1000 lines, single class. |
| `Info.plist` | `RequestsOpenAccess = true` (required for network), ATS + Local Network keys for LM Studio. |
| `Models.swift` | Shared data types used outside the AI module. |
| `APIClient.swift` | Legacy HTTP client (kept for reference; current providers in `AI/` use their own URLSession). |

### AI module — `TurtleKeyboardExtension/AI/`

The pluggable provider stack. Adding a new model = drop a file here + register in `CommandRouter`.

| File | What it is |
|---|---|
| `AITypes.swift` | `AIProvider` protocol, `ProviderID` enum, `AIModel`, `ModelCapability`, `CommandPayload`, `CommandResult`, `ProviderError`. Shared `parseSuggestionsJSON(_:)`. |
| `ModelRegistry.swift` | Static catalog of all known models (flux2, ministral3B, claudeHaiku, etc.) and `compatible(with:)` filtering. |
| `CommandRouter.swift` | Maps each slash command → `AIModel` → provider. Centralises system prompts (with `PromptLoader` fallback for `ask`/`org`). |
| `KeyStore.swift` | Subscript-based UserDefaults wrapper for API keys. TODO: migrate to App Group when groups are wired. |
| `PromptLoader.swift` | Reads shared system prompts from the bundle (`<id>.txt`). The Run Script build phase copies them from `commands/prompts/` at build time. |
| `LMStudioProvider.swift` | **Current default for all text commands.** POSTs to `http://192.168.1.10:1234/v1/chat/completions`, strips reasoning model `<think>` blocks. |
| `FalProvider.swift` | Image gen via fal.ai (direct or Spark gateway). Used by `/cap`. |
| `AnthropicProvider.swift`, `GoogleProvider.swift`, `OpenAIProvider.swift` | Cloud providers. Implemented but **not registered** in `CommandRouter.providers` while we test against LM Studio. Re-add their entries to the dictionary to enable. |
| `OrgImageRenderer.swift` | **Core Graphics renderer** for `/org`. Decodes the JSON document the model returns and rasterises a 500×500 PNG synchronously — no WebView. Covers all block types in `commands/prompts/org.txt`. |
| `HTMLImageRenderer.swift`, `org_template.html`, `html-to-image.js` | **Legacy.** Earlier WKWebView + bubkoo/html-to-image path for `/org`. Kept in the target but no longer called from `KeyboardViewController`. Safe to delete if you want a smaller appex. |

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

To run on device: open `ios/TurtleKeyboard.xcodeproj` in Xcode, sign with your team, select the **TurtleKeyboard** scheme (not the extension), `Cmd+R`. After install, enable in **Settings → General → Keyboard → Keyboards → Add New Keyboard → Turtle Keyboard**, then **toggle Allow Full Access**.

### Run Script build phase

The keyboard extension target has a `Copy shared command prompts` shell phase that copies `commands/prompts/*.txt` from the repo root into the appex bundle. **When you add a new prompt file, also add its `inputPaths` and `outputPaths` entries** in the build phase or it won't be copied. Then route the command through `PromptLoader.load(id:)` in `CommandRouter`.

---

## KeyboardViewController architecture

Single file. Key sections in order:

1. **State** — `SlashCommand` enum, `KeyboardMode`, shift/caps flags, double-tap timers, `previewOverlay` state.
2. **Layout constants** — separate iPhone vs iPad dimensions (`isPad` switch). iPad: `rowH=42, rowGap=6, commandBarH=40, totalH=290` (constrained by iPad portrait input view height).
3. **Palette** — turtle green brand colour, key gray, return-key green, banner.
4. **Key row data** — `qwertyRows/Pad`, `symbolRows/Pad`, `symbolShiftRows/Pad`. iPad has 5 rows (number row + 3 letters + modifier); iPhone 4.
5. **`setupContainers()`** — Auto Layout. `keyboardContainer` pinned to `view.bottomAnchor` with fixed `heightConstraint`. Banner + command bar + preview overlay all live inside.
6. **`buildKeyboard()` / `buildRow()`** — frame-based layout *inside* `keyboardContainer`.
7. **Command bar** — slash-command UI. `executeCommand(_:prompt:)` calls `CommandRouter.shared.execute(...)` and dispatches `CommandResult` to the right UI surface.
8. **`/org` and `/cap` flow** — both produce a `UIImage` and call `showImagePreview(_:)`, which surfaces the image in the preview overlay with **Copy** + **Close** buttons. iOS keyboards cannot insert images directly; user must long-press the chat field and tap **Paste**.
9. **Word suggestions** — `UITextChecker` driven completions inside our own command bar (system shortcut bar is suppressed via `inputAssistantItem.leadingBarButtonGroups = []`).
10. **Backspace repeat** — `UILongPressGestureRecognizer` with 0.4s delay + 0.08s repeat interval.

---

## Key invariants — don't break these

- `preferredContentSize` is set **once** in `viewDidLoad` and updated only inside `rebuildKeyboard()`. Setting it in `viewWillAppear` / `viewDidLayoutSubviews` causes a feedback loop that grows the keyboard full-screen.
- Width is always read from `UIScreen.main.bounds.width`, never `view.bounds.width` (which is 0 at layout time).
- `keyboardContainer` uses Auto Layout (`bottomAnchor`); its *contents* use frames. Don't mix Auto Layout into `buildRow()`.
- `RequestsOpenAccess` in the extension `Info.plist` must stay `true` for HTTP requests to LM Studio / fal to work. iOS will additionally require the user to flip "Allow Full Access" in Settings.
- The `192.168.1.10` ATS exception in both `Info.plist` files is required for plaintext HTTP to the LAN LM Studio endpoint. Don't broaden it.
- `OrgImageRenderer` is synchronous and runs on the main thread — fine because Core Graphics on a 500×500 canvas is sub-millisecond. Do **not** reintroduce a WKWebView path inside the keyboard extension; rAF is paused on detached webviews and the process is too memory-constrained for an in-hierarchy one.
- pbxproj IDs use the `HH` prefix when added by hand. Pick the next free `HHxxxxxxHHxxxxxxHHxxxxxx` triple. The pbxproj sections that need every new file: `PBXBuildFile`, `PBXFileReference`, the AI `PBXGroup`, and the `Sources` (or `Resources`) build phase of the extension target.

---

## Adding a new slash command

1. Add a `<id>.yaml` in `commands/` and (if needed) a `<id>.txt` in `commands/prompts/` — see repo-root `commands/README.md`.
2. Add a `case` to `SlashCommand` in `KeyboardViewController.swift` and update `requiredCapability(for:)` and `defaultRoutes` in `CommandRouter.swift`.
3. If the command needs a non-trivial system prompt, drop the `.txt` in `commands/prompts/` and add it to the Run Script's `inputPaths`/`outputPaths`. `PromptLoader.load(id:)` will pick it up.
4. If the command produces an image, route the `CommandResult.text(...)` (or `.image(...)`) through `showImagePreview(_:)` so the user gets the same Copy/Close UX as `/org` and `/cap`.
