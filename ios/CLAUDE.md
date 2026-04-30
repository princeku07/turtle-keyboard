# iOS — CLAUDE.md

## What to read / what to ignore

**Only ever touch these 5 files. Never open anything inside `TurtleKeyboard.xcodeproj/`.**

| File | What it is |
|---|---|
| `TurtleKeyboard/AppDelegate.swift` | App entry point — creates the UIWindow and root ViewController. Rarely needs changes. |
| `TurtleKeyboard/ViewController.swift` | Onboarding screen shown when the user opens the host app. Two buttons that deep-link to Settings. |
| `TurtleKeyboard/Info.plist` | Host app metadata. `CFBundlePackageType = APPL`. |
| `TurtleKeyboardExtension/KeyboardViewController.swift` | **The entire keyboard.** All layout, all key logic, all state lives here. |
| `TurtleKeyboardExtension/Info.plist` | Extension metadata. `RequestsOpenAccess = false` (keep false until App Groups are wired). |

`TurtleKeyboard.xcodeproj/` is Xcode project scaffolding — never read or edit it manually.

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

To run in simulator: open `ios/TurtleKeyboard.xcodeproj` in Xcode, select the **TurtleKeyboard** scheme (not the extension), press `Cmd+R`.

---

## KeyboardViewController architecture

Single file, ~300 lines. Sections in order:

1. **State** — `KeyboardMode` enum (`.qwerty` / `.symbols` / `.symbolsShift`), shift/caps flags, double-tap timers.
2. **Layout constants** — `rowH`, `rowGap`, `keyGap`, `bottomPad`. Change these to resize the keyboard.
3. **Palette** — all colours defined as `private let`. `bgColor` = iOS light gray, `keyNormal` = white, `keySpecial` = medium gray, `keyReturn` = turtle green (`#1B5E20`), `bannerBg` = turtle green.
4. **Key row data** — `qwertyRows`, `symbolRows`, `symbolShiftRows` — plain `[[String]]` arrays. Edit these to add/remove keys.
5. **`setupContainers()`** — Auto Layout only. `keyboardContainer` is pinned to `view.bottomAnchor` with a fixed `heightConstraint`. Banner sits above it. Never add frame-based layout here.
6. **`buildKeyboard()`** / **`buildRow()`** — frame-based layout *inside* `keyboardContainer`. Bottom row proportions are hard-coded percentages; modifier row uses 13.5% side keys.
7. **`makeKey()`** — creates each `UIButton`. SF Symbols used for `⇧`, `⌫`, `↵`. Letter keys use 22pt light font. Special keys use 15pt medium. Return key gets `keyReturn` background.
8. **Key handling** — `keyTapped(_:)` switch statement. Adding a new special key = add a `case` here + add to `isSpecial(_:)`.
9. **`handleShift()`** — double-tap within 300 ms → caps lock toggle. Single tap → shift-once (auto-unshifts after next letter).
10. **`handleSpaceDoubleTap()`** — double-tap space shows banner. **This is the Quick Panel hook** — replace `showBanner(...)` with Quick Panel presentation when ready.
11. **`showBanner(_:)`** — green strip above keys, auto-hides after 1.5 s.
12. **`rebuildKeyboard()`** — call this after any state change that affects key appearance. Updates `heightConstraint` and `preferredContentSize`.

---

## Key invariants — don't break these

- `preferredContentSize` is set **once** in `viewDidLoad` and updated only inside `rebuildKeyboard()`. Never set it in `viewWillAppear` or `viewDidLayoutSubviews` — this causes a feedback loop that makes the keyboard grow full-screen.
- Width is always read from `UIScreen.main.bounds.width`, never `view.bounds.width` (which may be 0 at layout time).
- `keyboardContainer` uses Auto Layout (`bottomAnchor`) but its *contents* use frames. Don't mix Auto Layout into `buildRow()`.
- `RequestsOpenAccess` in the extension `Info.plist` must stay `false` until App Groups are configured — setting it `true` without entitlements causes SIGKILL on launch.
