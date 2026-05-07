---
name: turtle-add-command
description: Use when the user wants to add, design, or scaffold a new Turtle Keyboard slash command (e.g. "add /sum", "wire up /meme", "design /code", "spec /roast"). Turtle Keyboard is the open-source AI keyboard described in Turtle_Keyboard_PRD.md at the repo root. The skill walks the four components every command must touch (backend routing, iOS keyboard, Android keyboard, host app) and enforces the non-negotiable PRD invariants (latency budget, multi-provider fallback, privacy, model neutrality). Do not invoke for general PRD questions or unrelated keyboard work.
---

# Adding a slash command to Turtle Keyboard

The slash command is the atomic unit of Turtle Keyboard (PRD §6.3). Every new command touches the same four components, in the same order, and must honor the same invariants. Read `Turtle_Keyboard_PRD.md` at the repo root if you have not — sections referenced below are the source of truth.

## Step 1 — Lock the command spec

Before any code, fill this table. If a cell is unknown, ask the user; do not guess.

| Field | Notes |
|---|---|
| Command | e.g. `/sum` |
| What it does | One sentence, user-facing |
| Output type | `image` \| `text` \| `multi-suggest` (3 chips) |
| Input source | field text \| selection \| clipboard \| last received message |
| Default model — free | Cheap, fast (Gemini Flash, Claude Haiku, Flux Schnell) |
| Default model — Pro | Premium (Claude Sonnet, GPT-4o, Flux Pro) |
| Fallback chain | At least one alternate provider — required by PRD §8.5 |
| Latency target | ≤ 1s text · ≤ 1.5s tone-style · ≤ 2s image (PRD §8.6) |
| Free-tier counter | text command (toward 100/day) **or** image (toward 20/day) |
| Watermark on free? | Yes for `/cap`-style image output, otherwise no |

## Step 2 — Backend routing entry

Routing is **config-driven, never hardcoded in handlers** (PRD §8.5). Add to the central router config:

```yaml
command: "/sum"
free:    { provider: "google",    model: "gemini-1.5-flash" }
pro:     { provider: "anthropic", model: "claude-3-5-sonnet" }
byo_key: { provider: user_provider }
fallback:
  - { provider: "openai", model: "gpt-4o-mini" }
```

Then:
- Wire the command into the `/v1/command` dispatcher (PRD §8.4).
- Register it in `/v1/commands` so the discover endpoint and host app pick it up.
- Emit per-invocation logs of latency, cost, and success — the public model leaderboard reads from this and reinforces the neutrality brand.

## Step 3 — iOS keyboard extension (Swift / SwiftUI)

Hard constraints (PRD §8.2): 48 MB memory ceiling, no on-device ML, Full Access required for network, **no direct image insertion** into other apps.

- Extend the parser that watches `textDocumentProxy` to recognize the new command + arguments.
- Output handling:
  - `text` → cursor-aware `insertText` (replace selection if present, otherwise append).
  - `image` → write to `UIPasteboard.general`, show "Tap to paste 📋" banner.
  - `multi-suggest` → render 3 tappable chips in the above-keys row.
- Error paths must degrade gracefully (no network, server error, rate limit). Never drop the user's prompt silently.

## Step 4 — Android keyboard (Kotlin / Jetpack Compose)

Looser constraints than iOS (PRD §8.3): `commitContent()` allows direct image insertion in WhatsApp, Gmail, Messages, Slack and others. No memory ceiling.

- Same parser pattern inside `InputMethodService`.
- `image` output: prefer `InputConnection.commitContent()`; fall back to clipboard + paste banner where unsupported.

## Step 5 — Host app

The host app is not just an installer (PRD §6.5). For every new command:
- Add it to the **playground** so users can try it without keyboard setup. Also satisfies the App Store "unique functionality" review bar.
- Surface it in the **discover commands** section of Settings.
- If output is > 500 chars, persist to **history**.

## Step 6 — Tiering, limits, privacy

- Free: enforce daily count, watermark image outputs (PRD §9.4).
- Pro: unlimited, premium model from routing config, priority queue.
- Pro+ / BYO: when a user key is present, route through it and skip inference billing.
- Privacy invariants (PRD §8.7, non-negotiable):
  - Keyboard logs typed text **only when a slash command is invoked**. Verify by grep before merging.
  - Generation payloads are user-purgeable from Settings.
  - BYO keys encrypted at rest, never logged plaintext, never sent outside the targeted provider.

## Step 7 — Telemetry

- PostHog: `command.invoked` with `{command, tier, latency_ms, success, provider, model}`.
- Sentry breadcrumb around the network call.
- If p50 latency drifts past the target, the lever is the routing config — not the keyboard code.

## Step 8 — Done checklist

- [ ] Spec table filled and approved by the user
- [ ] Routing config: primary + fallback for `free`, `pro`, `byo_key`
- [ ] `/v1/command` dispatches; `/v1/commands` lists it
- [ ] iOS parser + output handler
- [ ] Android parser + output handler (with `commitContent` for images)
- [ ] Host app playground entry + Settings discover entry
- [ ] Free / Pro limits enforced; watermark applied where required
- [ ] Latency measured on real 4G against the target
- [ ] PostHog + Sentry wired
- [ ] Grep confirms no typed text is logged outside the command payload

## When the project is still PRD-only (no code yet)

This is the current state of the repo. Do **not** scaffold all four components — there is nothing to attach them to. Instead, deliver:

1. The completed Step 1 spec table.
2. The Step 2 routing config block.
3. A short note on which existing v1 command (`/cap`, `/fix`, `/tone`, `/reply`, `/tl`) this is closest to in shape, so the eventual implementation can mirror it.

Stop there and let the founder decide whether to start the build.
