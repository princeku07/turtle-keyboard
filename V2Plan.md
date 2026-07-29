# Turtle Keyboard — V2 Plan

Companion to `Prd.md` (product) and `CLAUDE.md` (engineering). Captures the work
between v1 GA and the next major release: scale-out for the games platform,
iOS keyboard parity, paid tier, and the user-authored mini-app engine.

## Scope

In: changes to `android/`, `ios/`, `games/`, and Firebase project that ship in
the v2 milestone. Out: marketing site beyond bug fixes (lives in `lading-app/`),
backend routing service (separate closed-source repo), unannounced features.

## Themes

1. [Games platform scale-out](#1-games-platform-scale-out)
2. [iOS keyboard parity](#2-ios-keyboard-parity)
3. [Subscription + entitlements](#3-subscription--entitlements)
4. [User-authored mini-app engine](#4-user-authored-mini-app-engine)
5. [Reliability + observability](#5-reliability--observability)

---

## 1. Games platform scale-out

Reaches the cliffs identified during the v1 architecture review. Ordered by
blast radius — first item is the only one that's painful to defer.

### 1.1 Flip game shells to Firebase Hosting

**Problem.** `WebGameSheetView` loads `file:///android_asset/games/<name>/index.html`.
A new game version requires an APK ship. No hotfix path.

**Plan.** Two-line change in `WebGameSheetView`: load from
`https://<hosting-host>/games/<name>/index.html` instead, with `file://`
fallback when offline (cache via `WebViewAssetLoader` or a service-worker shell).
Keep the `copyGamesHtml` Gradle task as the fallback source.

**Success.** Ship a game fix without an APK release. `WebGameSheetView` opens
in ≤ 800 ms cold, ≤ 200 ms warm. Offline open of an already-played game still
works.

**Do this before game #3 ships** — once users have offline expectations for
two games, flipping is a behavior change.

### 1.2 Parallel game builds

**Problem.** `pnpm build = build:wyr && build:puzzle`. Linear. At 20 games,
~1–2 min.

**Plan.** `pnpm -r --parallel build` after restructuring each game as its own
workspace package (`games/packages/<name>/package.json`), or
`concurrently` over the existing scripts. Pick whichever lands fewer config
changes.

**Success.** N games build in ~max(per-game time), not Σ.

### 1.3 Drop the bridge shim from each bundle

**Problem.** `src/shared/bridge.ts` is inlined into every game (~5 KB each).
At 50 games, 250 KB APK bloat plus duplicate parse cost in the WebView.

**Plan.** Native injects the shim before page load alongside
`TurtleGame_native` (`evaluateJavaScript(bridgeJs)` from `GameBridge.attach`).
Games drop the `import './shared/bridge'` line.

**Success.** `dist/<game>/index.html` no longer contains `TurtleGameApi`.

### 1.4 Puzzle image TTL + Drive cleanup

**Problem.** Drive image lives in the creator's 15 GB quota forever. Power
user → hits the ceiling. Flagged in `PuzzleIntegration` javadoc since v1.

**Plan.** Cloud Function on `games/<id>` delete (or a daily sweep over
`createdAt < now - 30d`) calls `DriveFilesClient.deleteFile` using a
delegated token from the creator's stored refresh credential. Requires moving
the refresh token to a secure server-side store; today it lives in
SharedPrefs on-device only.

**Success.** Drive usage per power user stays flat over 30 days.

### 1.5 RTDB write-rate ceiling for live games

**Problem.** Freeform multiplayer at 30 Hz tile drag × 50 simultaneous
players approaches RTDB's per-path write-rate ceiling.

**Plan.** Client-side throttle in `bridge.ts.writePlayerState` — debounce to
5 Hz, coalesce same-key writes. Optional v2.5: move hot live state to
Firestore + per-game Cloud Function fanout for ≥ 100-player rooms.

**Success.** P95 tile-drag-to-peer-render ≤ 200 ms at 50 concurrent
players per puzzle.

---

## 2. iOS keyboard parity

`ios/` exists as a structural mirror of `android/` but the integration layer,
slash-command dispatch, and WebView game shell aren't wired. Single biggest
shipping gap.

**Plan.** Three ordered slices, each independently shippable to TestFlight:

1. **Slash-command dispatch.** Port `CommandComposer` / `CommandDispatcher` /
   `CommandRegistry` to Swift. Start with `/cap` and `/sticker` (no auth,
   no panels). Keeps text-only commands moving while panels lag.
2. **Sheet router + panels.** Port the panel-mount lifecycle so
   `PuzzleSetupPanel`-equivalents work above the keys. iOS doesn't have
   `InputMethodService.onFinishInputView` so the re-mount-after-picker dance
   is different — likely simpler (extension stays alive across picker
   activations).
3. **WebView game bridge.** Wire `WKScriptMessageHandler` +
   `__TurtleGame_initial` injection per the iOS branch already stubbed in
   `games/src/shared/bridge.ts`. Lets `/puzzle` and `/wyr` work end-to-end.

**Success.** Every v1 Android slash command works on iOS. CI builds both
targets on every PR.

**Open question.** OAuth for Drive/Google sign-in from a keyboard extension
on iOS has historically been hostile (no UIApplication open). Plan to push
the consent flow to the host app exactly as Android does today via
`GoogleAuth.ERROR_NEEDS_UI` → "Open Turtle and link Drive".

---

## 3. Subscription + entitlements

Identity model already locked: Firebase Auth (Google), entitlements written
to `users/{uid}` server-side, never client-set
(memory: `project_paid_subscription`).

**Plan.**

- Google Play billing wired in `MainActivity` host app — `BillingClient`,
  subscription SKU, RTDN webhook to a Cloud Function that mutates
  `users/{uid}.entitlements`.
- Entitlement read path: keyboard reads `users/{uid}` via Firestore listener
  on enable; caches `entitlements.tier` in SharedPrefs for offline gating.
- Gate v2 features (image-history beyond N items, multi-binding MCP, premium
  game packs) behind `entitlements.tier == "plus"`. Free tier keeps every v1
  feature unrestricted — never regress.
- iOS uses RevenueCat for App Store receipt validation, writes to the same
  `users/{uid}` doc.

**Open question.** Free-vs-paid line for `/puzzle` images. Free: 30-day TTL +
Drive quota. Plus: longer TTL? Or no quota relief because images stay in the
user's own Drive anyway?

---

## 4. User-authored mini-app engine

Deferred from v1 (`project_ui_engine_later`). v2 introduces it as a tier-gated
beta; first-party `/command`s already use the bridge to dogfood.

**Plan.**

- Web builder at `builder.turtlekeyboard.com` outputs an HTML bundle +
  `manifest.json` (route key, icon, default panel size, requested
  capabilities).
- Install path: builder produces a deep link with a signed manifest; the
  Android host app verifies the signature and writes
  `users/{uid}/installed_apps/<route>`.
- Runtime: `IntegrationRegistry` learns to load installed mini-apps as
  `KeyboardIntegration` adapters that mount the manifest's HTML in
  `WebGameSheetView` (rename to `WebSheetView` since "game" is no longer
  accurate).
- Capabilities: start with `bridge.subscribe`, `bridge.writeState`,
  `bridge.commitText`. No Drive, no MCP in v2 beta — additive in v2.x.

**Success.** A non-Turtle developer ships a working mini-app without forking
the repo.

---

## 5. Reliability + observability

The v1 codebase has plenty of `Log.w(TAG, ...)` and zero structured telemetry.
v2 introduces just enough to debug field issues without violating the
"keyboard never logs typed text" invariant.

**Plan.**

- Firebase Crashlytics in both `android/` and `ios/`. Already a dep candidate.
- Structured event log to `users/{uid}/events` capped at 200 rows, append-only,
  client-side trim. Event names only — never user text. Used for
  "open Turtle and look at the last 10 events" support workflow.
- Latency budgets enforced as asserts in debug builds: `/cap` ≤ 2 s end-to-end,
  text commands ≤ 1.5 s. Crash debug builds when exceeded; warn-log in
  release.

**Success.** When a user reports a `/puzzle` failure, support can reproduce
the chain (pick → upload → write → commit) from the event log without asking
for repro steps.

---

## Non-goals (call out explicitly)

- **Paired-keyboard sync for couples.** Marketing wedge only; no partner
  account linking, no shared dictionary. (memory: `project_couples_wedge`.)
- **Server proxy for shared state.** Firebase direct from clients; Cloud
  Functions only where rules can't express the operation. (memory:
  `feedback_worker_scope`.)
- **Kotlin / Compose migration.** Android stays Java + XML.
  (memory: `feedback_android_java`.)
- **LmStudioAiClient command branches.** Retired in v1 phase 3; new AI
  features live in their integration's local handler.
  (memory: `feedback_integrations_own_ai`.)

## Sequencing

Approximate order. Each row is independently shippable.

| Order | Work | Why first |
|---|---|---|
| 1 | 1.1 Hosting flip | Hardest to defer; behavior change later |
| 2 | 2.1 iOS slash-command dispatch | Unblocks iOS TestFlight track |
| 3 | 3 Subscription plumbing | Revenue gate for everything below |
| 4 | 1.4 Drive TTL + 1.5 RTDB throttle | Operational ceilings, hit at scale |
| 5 | 2.2/2.3 iOS panels + game bridge | Full iOS parity |
| 6 | 4 Mini-app engine beta | Builds on stable bridge + entitlements |
| 7 | 5 Reliability / observability | Cross-cutting; can land anytime but anchored last so it covers v2 features |
| 8 | 1.2 Parallel builds + 1.3 Shim drop | Pure quality-of-life; no user impact |

## Open questions

- Hosting flip (1.1) — service worker vs `WebViewAssetLoader` for offline?
- Mini-app signing key custody — Firebase Functions secret or per-developer
  uploaded public key?
- iOS Drive OAuth — confirm host-app bounce path still passes Apple review
  for keyboard extensions in 2026.
