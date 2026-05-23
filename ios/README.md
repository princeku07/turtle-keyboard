# Turtle Keyboard — iOS

Native iOS implementation of the Turtle Keyboard: an open-source, model-agnostic AI keyboard. Users type slash commands (`/cap`, `/edit`, `/fix`, `/reply`, …) inside any text field and get AI-generated images or text back in ~1.5–2 seconds without leaving the host app.

This directory ships **two targets** that share a single Apple Developer App Group:

| Target | Bundle ID | What it is |
|---|---|---|
| `TurtleKeyboard` | `com.samarth.turtlekeyboard` | Host app — onboarding, OAuth Connect screens (Notion / Slack / Google), Personalization, image history. |
| `TurtleKeyboardExtension` | `com.samarth.turtlekeyboard.keyboard` | The keyboard itself — a `UIInputViewController` subclass with slash-command routing, multi-provider AI stack, in-keyboard integration panels, voice input. |

App Group: `group.com.samarth.turtlekeyboard.split` — tokens, splits, theme choice, and personalization toggles live in a shared `UserDefaults` suite the host app writes and the extension reads.

---

## Quick start

**Requirements:** Xcode 15+, an iOS 15+ device or simulator, an Apple Developer team for code signing.

**Build (CI / sanity check):**

```bash
xcodebuild build \
  -project ios/TurtleKeyboard.xcodeproj \
  -scheme TurtleKeyboard \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO
```

**Run on device:**

1. Open `ios/TurtleKeyboard.xcodeproj` in Xcode.
2. Pick the **TurtleKeyboard** scheme (not the extension), sign with your team, `⌘R`.
3. On the device: **Settings → General → Keyboard → Keyboards → Add New Keyboard → Turtle Keyboard**, then **toggle Allow Full Access** (required for network calls and on-device speech recognition).

**First-run env:** the keyboard extension's build phase reads a repo-root `.env` and generates `ios/Generated/Secrets.swift` + `Secrets.xcconfig`. Copy `.env.example` to `.env` and fill in the keys before the first build (see `Scripts/load-env.sh` for the key list).

---

## Features

- **Slash commands** — `/cap`, `/edit`, `/fix`, `/tone`, `/reply`, `/tl`, `/ask`, `/org`, `/split`, `/splits`, `/notion`, `/note`, `/slack`, `/msg`, `/poll`, `/wyr`, `/web`, `/history`. Defined in `Command/SlashCommand.swift`; remote commands route through `AI/CommandRouter`, local ones through an integration in `Integration/`.
- **Multi-provider AI stack** — LM Studio (LAN) by default; Anthropic, Google, OpenAI providers implemented and ready to drop into `CommandRouter.providers`. Image generation via fal.ai.
- **Inline image preview** — `/cap` and `/org` render an image directly into a fixed slot above the keys. Tap **Image / Sticker / GIF** to encode the right format and drop it on `UIPasteboard` with the matching UTI; long-press in the chat field and **Paste** to insert. Keys stay visible and tappable underneath.
- **Slash autocomplete strip** — chip strip above the prompt bar showing every still-matching command. Stays mounted for the entire draft state.
- **Quick Panel** — double-tap space opens a tap-to-fire grid of every registered command, honouring the user's Personalization toggles.
- **Voice mic** — `SFSpeechRecognizer`-backed dictation directly into the prompt area (short form) or a host-app capture screen (long form).
- **Integrations with OAuth** — Notion, Slack, and Google (for Splits). All OAuth runs in the host app; the keyboard reads tokens from the shared App Group.
- **Draft persistence** — switch apps mid-command (e.g. `/ca`, `/cap a samurai cat`) and the keyboard restores the same surface on return (5-minute TTL).
- **Theming** — picker on the Personalization screen; the extension re-stamps `KeyboardPalette.current` on mount and theme change.

---

## Project layout

```
ios/
├── README.md                    # this file
├── CLAUDE.md                    # detailed dev guide for AI-assisted contribution
├── OAUTH_SETUP_iOS.md           # provider setup steps (Google / Notion / Slack)
├── TurtleKeyboard.xcodeproj/    # Xcode project (hand-edited pbxproj — see CLAUDE.md)
├── Scripts/
│   └── load-env.sh              # build-phase script: .env → Generated/Secrets.swift
├── TurtleKeyboard/              # host-app target
│   ├── AppDelegate.swift
│   ├── ViewController.swift     # onboarding + nav
│   ├── PersonalizationViewController.swift
│   ├── HistoryViewController.swift
│   ├── *ConnectViewController.swift     # OAuth flows
│   ├── *SheetViewController.swift       # /poll, /wyr host sheets
│   ├── VoiceRecording*.swift            # long-form dictation
│   └── Cloud/                           # Google / Notion / Slack clients
└── TurtleKeyboardExtension/     # keyboard extension target
    ├── KeyboardViewController.swift     # the keyboard (everything routes through here)
    ├── Command/SlashCommand.swift       # canonical command enum
    ├── Keyboard/                        # key rows, palette, theme, autocomplete strip,
    │                                    # quick panel, preset chips, history panel
    ├── AI/                              # provider stack + CommandRouter
    ├── Integration/                     # Split, Notion, Slack, Poll, Wyr, Web
    └── Voice/VoiceInputController.swift # SFSpeechRecognizer integration
```

---

## Going deeper

- **`CLAUDE.md`** — file-by-file map of every directory above, the `KeyboardViewController` architecture (dynamic-height layout, chrome slots, preview slot, draft persistence), and the **key invariants** that protect the layout from regressions. Read this before touching layout, persistence, or panel mounting code.
- **`OAUTH_SETUP_iOS.md`** — provider registration steps and the URL schemes / redirect URIs that need to land in `Info.plist`.
- **`../Readme.md`** — repo-root product requirements (PRD): latency budget, privacy invariants, model-neutrality, the Quick Panel spec, etc.
- **`../commands/`** — per-command YAML + prompt files shared with the Android target. iOS reads prompts from the bundle at runtime via `PromptLoader`.

---

## Adding things

Brief checklists are in `CLAUDE.md`:

- **A new slash command** — `CLAUDE.md` → *Adding a new slash command*.
- **A new integration** (a new local command provider) — `CLAUDE.md` → *Adding a new integration*.
- **A new AI provider** — drop a file in `TurtleKeyboardExtension/AI/` implementing `AIProvider`, register it in `CommandRouter.providers`.
- **A new shared prompt** — drop the `.txt` in repo-root `commands/prompts/`, add it to the keyboard extension's *Copy shared command prompts* Run Script phase (`inputPaths` + `outputPaths`), then `PromptLoader.load(id:)` will pick it up.

---

## License

MIT — see repo root.
