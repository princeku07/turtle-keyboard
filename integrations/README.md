# Integration registry

Single source of truth for every Turtle keyboard integration — features that go beyond a single slash command (chips, in-keyboard panels, deep screens, host-app awareness). CI validates this directory on every PR — see [`Integrations`](../Readme.md#integrations--composing-features-beyond-slash-commands) in the root README for the architectural picture.

## Files

- `_schema.json` — JSON Schema every `*.yaml` is validated against.
- `<id>.yaml` — one file per integration. See `split.yaml` as a starting template.

## What an integration registers

Each YAML declares which **surfaces** the integration uses (chip, panel, banner, slash-command, deep-screen, storage), what it **activates on** (host package allowlist, field types), the **commands** it ships (linking to entries in `commands/` if they exist), the **deep screens** it can open via `openScreen(id)`, and a per-platform **support** block.

The combination is everything CI and reviewers need to spot:

- Two integrations both targeting the same host package (potential conflict).
- A `beta` / `stable` integration with an `impl` path that doesn't exist on disk.
- Capability creep — an integration that quietly grew from "chip-only" to "chip + panel + storage + deep-screen" without anyone noticing.

## Adding an integration

1. Pick an `id` matching `^[a-z][a-z0-9-]*$` (lowercase, kebab-case).
2. Copy `split.yaml` as a template.
3. Set every platform under `support:` to `planned` (or `in-progress` if you're starting work right now).
4. Implement on at least one platform; update that platform's `support` block to point at the file containing the `KeyboardIntegration` implementation.
5. Open the PR. The parity bot validates the YAML and posts a parity matrix as a sticky PR comment.

## Updating support state

```yaml
support:
  android:
    state: stable                 # was: in-progress
    since: "0.5.0"                # the release tag this shipped in
    impl: android/split/src/main/java/com/prince/split/SplitIntegration.java
```

`state: beta` and `state: stable` require both `since` and `impl`. CI verifies the `impl` path actually exists in the repo.

## Cross-references with `commands/`

If your integration ships a slash command:

- The command name goes under the integration's `commands:` list here.
- If the command also has a `commands/<name>.yaml` (typically because it round-trips to a backend), the parity script auto-links them in the report.
- Locally-handled commands (those that run via a `CommandSpec.Handler` with no backend hop) don't need a `commands/<name>.yaml`. Listing them here is enough for the parity matrix to surface them.

## Running the parity check locally

```bash
npm install --no-save js-yaml ajv
node .github/scripts/parity-check.mjs
```

Exits non-zero if any YAML fails the schema or any `impl` path is missing.
