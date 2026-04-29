# Turtle Keyboard

> The Universal AI Input Layer — an open-source AI keyboard for iOS and Android that puts every model one slash command away, inside any app you already use.

Type `/cap`, `/fix`, `/reply` (or any custom command) inside any text field to trigger an AI action and paste the result back into the conversation you are already in. The keyboard clients are open source; the routing, premium models, and custom commands live in a closed-source backend.

See [`Prd.md`](./Prd.md) for the full product spec.

---

## Repository layout

```
turtle-keyboard/
├── android/        # Android keyboard app (Java + XML Views, Gradle)
├── ios/            # iOS keyboard app (coming soon, Swift)
├── lading-app/     # Marketing landing page (Next.js 16, React 19, Tailwind v4)
├── commands/       # Command registry — single source of truth for every slash command
├── .github/        # CI workflows (parity check, per-component build/test) and scripts
├── Prd.md          # Product requirements document
└── Readme.md
```

Each component ships independently and has its own build/test pipeline. Contributors are welcome to focus on a single surface.

---

## Prerequisites

| Component   | Toolchain                                                   |
| ----------- | ----------------------------------------------------------- |
| `android/`  | JDK 17, Android Studio Hedgehog+ (or Gradle 8.x), Android SDK 34 |
| `ios/`      | macOS, Xcode 15+, Swift 5.9+ (once the directory lands)     |
| `lading-app/` | Node 20+, pnpm 9+                                         |

Clone the repo:

```bash
git clone https://github.com/<org>/turtle-keyboard.git
cd turtle-keyboard
```

---

## Running each component

### Landing page (`lading-app/`)

```bash
cd lading-app
pnpm install
pnpm dev          # http://localhost:3000
```

Other scripts:

```bash
pnpm build        # production build
pnpm start        # serve the production build
pnpm lint         # eslint
```

### Android keyboard (`android/`)

Open `android/` in Android Studio, let Gradle sync, then run the `app` configuration on a device or emulator. From the command line:

```bash
cd android
./gradlew assembleDebug          # build debug APK
./gradlew installDebug           # install on a connected device
./gradlew test                   # unit tests
./gradlew connectedAndroidTest   # instrumented tests (needs a device)
```

After install, enable the keyboard: **Settings → System → Languages & input → On-screen keyboard → Turtle Keyboard**, then switch to it from the input switcher.

> Note: the Android client is intentionally written in **Java + XML Views** (not Kotlin / Compose). Keep new code consistent with that choice.

### iOS keyboard (`ios/`)

The iOS keyboard extension is under active development. Once the `ios/` directory lands:

```bash
cd ios
open TurtleKeyboard.xcodeproj
# build the host app target, then enable the keyboard extension in Settings → General → Keyboard
```

---

## Cross-platform parity

Two native codebases (Java for Android, Swift for iOS) with no shared code means parity is a process, not a fact. The process is built around a single source of truth: the **command registry** in [`commands/`](./commands).

### How it works

1. **Every slash command is a YAML file** at `commands/<id>.yaml`. It defines the contract (endpoint, request/response schema), UX rules (latency budget, loading state, result action), golden test fixtures, and the **support matrix** — the state on each platform: `planned`, `in-progress`, `beta`, `stable`, or `deprecated`. The schema lives at [`commands/_schema.json`](./commands/_schema.json).

2. **The registry is the contract.** CI rejects a PR that:
   - Adds a command implementation file without a matching `commands/<id>.yaml`.
   - Marks a command `beta` or `stable` while its `impl` path points to a missing file.
   - Breaks the YAML schema.

3. **The same fixtures test both platforms.** Android JUnit and iOS XCTest both load `commands/<id>/fixtures/*.json` and assert identical request/response shape. "Passes the spec" means the same thing on both platforms — even with zero shared code.

4. **The parity bot comments on every PR** with the full `command × platform × state` matrix. Gaps are highlighted (e.g. `/cap` is `stable` on Android but `planned` on iOS). Gaps are non-blocking on the PR but auto-spawn `parity-gap` tracking issues — those become the contributor on-ramp under the `good-first-parity` label.

5. **The landing page renders `/features` and `/releases`** at build time straight from the registry. Public, filterable, and always honest about what's actually shipped where.

### Adding a command

1. Copy an existing YAML in `commands/` as a template.
2. Set every platform under `support:` to `planned` (or `in-progress` if you're starting work right now).
3. Implement on at least one platform, update its `support` block.
4. Open the PR. The parity bot validates the YAML and reports.
5. On merge, `parity-gap` issues open automatically for the still-`planned` platforms.

### Status states

| State         | Meaning                                                            |
| ------------- | ------------------------------------------------------------------ |
| `planned`     | In the registry, no implementation yet                             |
| `in-progress` | Active branch or open PR                                           |
| `beta`        | Behind a flag, available to TestFlight / internal builds           |
| `stable`      | Shipped to production users on this platform                       |
| `deprecated`  | Still works but slated for removal; new clients should not use it  |

`beta` and `stable` require both `since` (release tag) and `impl` (path to the implementation file). CI verifies the path exists.

### Running the parity check locally

```bash
npm install --no-save js-yaml ajv
node .github/scripts/parity-check.mjs
```

Exits non-zero if any YAML fails the schema or any `impl` path is missing.

---

## Branching & release model

We optimize for **fast OSS contribution** without shipping untested code to users.

```
main          ← stable, what users get; protected, only fast-forward from release/*
 └── develop  ← integration branch, all PRs land here first
      ├── feat/<short-name>     ← new features
      ├── fix/<short-name>      ← bug fixes
      └── chore/<short-name>    ← tooling, docs, deps
release/x.y.z ← cut from develop when ready to ship; stabilization only
hotfix/<name> ← branched from main for urgent prod fixes; merged to both main and develop
```

Rules:

- **All work happens in a feature branch off `develop`.** Never push directly to `main` or `develop`.
- **PRs target `develop`.** A PR to `main` is rejected by CI unless it comes from `release/*` or `hotfix/*`.
- **`main` is always shippable.** Anything merged to `main` has passed CI and at least one human review.
- **Releases are tagged** (`v0.3.1`) and produce signed artifacts (APK, IPA, Vercel deploy).

---

## Contribution workflow

1. **Open an issue first** for anything non-trivial — a feature, a refactor, or a behavioral change. Bug fixes can skip straight to a PR.
2. **Fork and branch** off `develop`:
   ```bash
   git checkout develop && git pull
   git checkout -b feat/slash-summarize
   ```
3. **Make your change.** Keep PRs focused — one concern per PR. Include tests where the surface allows.
4. **Run the local checks** for the component(s) you touched (see [Local checks](#local-checks) below). Don't open a PR red.
5. **Open the PR against `develop`.** Fill out the PR template. Link the issue.
6. **Reviews:** at least one maintainer approval is required. Address review comments with new commits (do not force-push during review — squash on merge).
7. **Merge:** maintainers squash-merge once CI is green and the PR is approved.

### Commit messages

Conventional Commits, lowercase, imperative:

```
feat(android): add /reply command parser
fix(landing): correct hero copy on mobile
chore(deps): bump next to 16.2.4
```

### What makes a PR easy to merge

- Small (under ~400 lines diff where possible).
- One concern.
- Clear "why" in the description; "what" is in the diff.
- Screenshots / screen recordings for UI changes.
- Tests for logic changes; manual test notes for keyboard UX changes.
- No unrelated formatting churn.

---

## Local checks

Run before opening a PR. CI runs the same set.

| Component   | Commands                                                           |
| ----------- | ------------------------------------------------------------------ |
| `lading-app/` | `pnpm lint && pnpm build`                                       |
| `android/`  | `./gradlew lint test assembleDebug`                                |
| `ios/`      | `xcodebuild -scheme TurtleKeyboard test` (once available)          |
| `commands/` | `node .github/scripts/parity-check.mjs` (validates the registry)   |

Use **`only-the-component-you-changed`** as a baseline; CI will run all three on every PR.

---

## CI / CD

Every PR runs through GitHub Actions. The pipeline is the gate that keeps untested code out of users' hands. **Each workflow is path-filtered** ([`.github/workflows/`](./.github/workflows)) — a PR that only touches `commands/` skips the `lading-app` workflow, a PR that only touches `lading-app/` skips Android/iOS, and so on. Only the `parity` workflow runs across `commands/`, `android/`, and `ios/` since it validates them as a unit.

**On PR to `develop`:**
- `lading-app`: lint, typecheck, `next build`.
- `android`: `./gradlew lint test assembleDebug`.
- `ios`: `xcodebuild test` on the keyboard scheme.
- **Parity check**: validates `commands/*.yaml` against the schema, verifies `impl` paths, posts the parity matrix as a sticky PR comment, and fails on schema violations or orphaned implementations.
- Required to be green before merge.

**On merge to `develop`:**
- All of the above, plus a preview deploy of `lading-app` to a Vercel preview URL — **only if the merge actually changed files under `lading-app/`**. Otherwise Vercel skips the build (see [`lading-app/vercel.json`](./lading-app/vercel.json)).
- Debug APK uploaded as a build artifact for QA.

**On merge to `main` (via `release/*` or `hotfix/*`):**
- Production deploy of `lading-app` to Vercel — **only if the merge changed files under `lading-app/`**. A merge that touches only `android/`, `ios/`, `commands/`, or root docs will not redeploy the landing page.
- Signed release APK built with the maintainer's keystore stored in GitHub Actions secrets — never committed. Contributors don't need a keystore for local dev: `./gradlew assembleDebug` auto-generates a debug keystore on first run.
- iOS archive + TestFlight upload.
- Tag the release (`vX.Y.Z`) and publish a GitHub Release with changelog.

**Branch protection on `main`:**
- Require PR + 1 approval.
- Require all status checks to pass.
- Require linear history.
- No force-push, no deletion.

Secrets (signing keys, Vercel tokens, App Store keys, backend API keys) live only in GitHub Actions secrets. The root `.gitignore` excludes `KEYSTORE`, `*.keystore`, `*.jks`, and `local.properties` — **never commit signing material or paths to your local SDK install**.

**Vercel project setup (one-time, for maintainers):** in the Vercel dashboard, set the project's **Root Directory** to `lading-app`. The committed [`lading-app/vercel.json`](./lading-app/vercel.json) handles path filtering via `ignoreCommand` (`git diff --quiet HEAD^ HEAD -- .`) — Vercel cancels any deploy where nothing under `lading-app/` changed. This applies to both preview and production deploys, so a PR that only touches `android/` or `commands/` won't ever spin up a Vercel build.

---

## Testing expectations

We don't gate on coverage numbers, but every PR should answer: *how do I know this works?*

- **Pure logic** (command parser, routing, formatters): unit tests.
- **UI / keyboard interaction:** manual test notes in the PR (steps + device/OS).
- **Landing page:** visual check + Lighthouse for perf-affecting changes.
- **Regression risk:** if you fix a bug, add a test that would have caught it.

---

## Reporting issues

- **Bugs:** use the bug template. Include device/OS, repro steps, expected vs actual, logs if you have them.
- **Security issues:** do **not** open a public issue. Email `security@<domain>` (or the address listed in `SECURITY.md` once published).
- **Feature ideas:** open a discussion first; it's cheaper than building the wrong thing.

---

## Code style

- **Android:** Java + XML Views. Follow the existing package layout. AndroidX only.
- **iOS:** Swift, SwiftUI for host app, UIKit for the keyboard extension (extension API constraints).
- **Web:** TypeScript strict, Tailwind v4 utilities, React Server Components by default, client components only when needed.
- Run the formatters/linters that ship with each component before pushing.

---

## License

To be finalized — see `LICENSE` (planned: Apache-2.0 for the keyboard clients). The backend service is closed source and not part of this repository.

---

## Getting help

- Open a [GitHub Discussion](../../discussions) for questions.
- Tag maintainers in your PR if it's been sitting for >5 days.
- For commercial / partnership inquiries, see contacts on the landing page.

Welcome aboard — we're glad you're here.
