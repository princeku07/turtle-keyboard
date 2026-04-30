# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Turtle Keyboard is an open-source, model-agnostic AI keyboard for iOS and Android. Users type slash commands (e.g. `/cap a samurai cat`) inside any text field and get AI-generated images or text back in ~1.5–2 seconds without leaving the app. The keyboard is MIT-licensed; the backend routing service is closed-source.

The repo currently contains three sub-projects:

| Directory | What it is |
|---|---|
| `android/` | Native Android keyboard (Java, `InputMethodService`) |
| `ios/` | Native iOS keyboard (Swift, `UIInputViewController`) |
| `lading-app/` | Marketing landing page (Next.js 16, React 19, Tailwind v4) |

See `ios/CLAUDE.md` for the full iOS breakdown — only 5 Swift/plist files matter; never touch `TurtleKeyboard.xcodeproj/`.

---

## Android keyboard (`android/`)

**Language:** Java (not Kotlin — the build files are `.kts` but the source is `.java`)  
**Min SDK:** 24 · Target SDK: 34  
**Build:** Gradle with version catalog at `android/gradle/libs.versions.toml`

```bash
cd android
./gradlew assembleDebug          # build debug APK
./gradlew assembleRelease        # build release APK (minified + shrunk)
./gradlew test                   # unit tests
./gradlew connectedAndroidTest   # instrumented tests (needs device/emulator)
```

### Architecture

The keyboard is a single `InputMethodService` subclass:

- **`TurtleInputMethodService.java`** — the entire keyboard logic. Inflates `keyboard_view.xml`, handles all key events via `KeyboardView.OnKeyboardActionListener`.
  - Three keyboard layouts: QWERTY (`qwerty.xml`), symbols (`symbols.xml`), shifted symbols (`symbols_shift.xml`) — all in `res/xml/`.
  - Double-tap shift → caps lock (300 ms window).
  - Double-tap space → `detectSpaceDoubleTap()` which currently shows a banner ("🐢 Double-tap detected"). **This is the hook for the Quick Panel** — the PRD calls for this gesture to open the slash-command grid; the detection is wired but the panel UI is not built yet.
  - `banner` TextView in `keyboard_view.xml` auto-hides after 1500 ms via a `Handler`.

- **`MainActivity.java`** — host app entry point (setup/onboarding placeholder).

The slash command parser, API calls, and command routing are **not yet implemented** in the Android codebase. The keyboard types characters but does not yet detect `/` prefixes or call the backend.

---

## iOS keyboard (`ios/`)

**Language:** Swift 5 · UIKit · no storyboards (all programmatic)  
**Deployment target:** iOS 15.0  
**Build:** Open `ios/TurtleKeyboard.xcodeproj` in Xcode; sign with your Apple Developer team, then build & run.

Two targets in the Xcode project:

| Target | Bundle ID | Type |
|---|---|---|
| `TurtleKeyboard` | `com.turtlekeyboard` | Host app (onboarding) |
| `TurtleKeyboardExtension` | `com.turtlekeyboard.keyboard` | Keyboard extension |

**Full iOS details → `ios/CLAUDE.md`** (file-by-file breakdown, invariants, build command). Do not read `TurtleKeyboard.xcodeproj/` — only the 5 Swift/plist source files matter.

The slash command parser, API calls, and command routing are **not yet implemented** in the iOS codebase.

---

## Landing page (`lading-app/`)

**Stack:** Next.js 16.2.4 · React 19 · Tailwind CSS v4 · Three.js / `@react-three/fiber` for the hero 3D scene

```bash
cd lading-app
npm install
npm run dev      # dev server at localhost:3000
npm run build    # production build
npm run lint     # ESLint
```

> **Note:** This uses Next.js 16 — a breaking-change release. Before editing routing, layouts, or data-fetching patterns, check `node_modules/next/dist/docs/` for current API conventions.

### Structure

- `app/page.tsx` — the entire single-page site (hero, features, commands grid, pricing, FAQ, waitlist CTA, footer). All copy and section data live as `const` arrays at the top of this file.
- `app/components/SceneWrapper.tsx` — dynamically imports `Scene.tsx` (SSR disabled) to avoid hydration errors with Three.js.
- `app/components/Scene.tsx` — the `@react-three/fiber` 3D scene rendered in the hero panel.
- `app/globals.css` — Tailwind v4 import + CSS custom properties for the design system.

### Design system

Colors are CSS variables defined in `globals.css` and exposed as Tailwind tokens:

| Token | Hex | Use |
|---|---|---|
| `cream` | `#f4efe4` | Background |
| `ink` | `#0c0c0c` | Text, borders |
| `lime` (also `[#15803d]`) | `#15803d` | Primary green / CTA |
| `pink` | `#ff4fa3` | Accent, image commands |
| `blue` | `#5b6cff` | Accent |
| `orange` | `#ff7a1a` | Accent |

Typography is Geist Sans + Geist Mono. The `.outline-text` utility class renders hollow/stroke text. `.grain` adds a dot-grid texture. Borders are consistently `border-2 border-ink`. Cards use `shadow-[4px_4px_0_0_var(--ink)]` for the neo-brutalist offset shadow.

---

## Product context (important for making correct changes)

- **v1 ships image-only commands:** `/cap`, `/sticker`, `/edit`, `/avatar`, `/scene`, `/meme`. Text commands (`/fix`, `/tone`, `/reply`, `/tl`) are roadmap.
- **Latency is a hard constraint:** ≤ 2 s end-to-end for `/cap`, ≤ 1.5 s for text. If a feature would add latency on the critical path, note it.
- **The keyboard never logs text outside of slash commands** — this is a stated trust/privacy invariant enforced in the open-source code.
- **Quick Panel:** triggered by double-tap space, renders a grid of slash commands above the keys. Android detection exists (`detectSpaceDoubleTap()`); full panel UI is not yet built.
- **Backend API** (`/v1/command`, auth, model routing) is a separate closed-source service — not in this repo. The keyboard talks to it via HTTP POST.
- **PRD is `Readme.md` at repo root** — the full product requirements live there (not a typical readme). Read it for context on any feature decision.
