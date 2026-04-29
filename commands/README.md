# Command registry

Single source of truth for every Turtle slash command. CI validates this directory on every PR — see [`Cross-platform parity`](../Readme.md#cross-platform-parity) in the root README for the full picture.

## Files

- `_schema.json` — JSON Schema every `*.yaml` is validated against.
- `<id>.yaml` — one file per command. See `fix.yaml` as a starting template.
- `<id>/fixtures/*.json` — golden request/response fixtures, loaded by both Android and iOS test suites so "passes the spec" means the same thing on both platforms.

## Adding a command

1. Pick an `id` matching `^[a-z][a-z0-9-]*$` (lowercase, kebab-case).
2. Copy an existing YAML as a template.
3. Set every platform under `support:` to `planned` (or `in-progress` if you're starting work right now).
4. Implement on at least one platform; update that platform's `support` block.
5. Open the PR. The parity bot validates the YAML and posts a parity matrix as a sticky PR comment.

## Updating support state

```yaml
support:
  android:
    state: stable                 # was: in-progress
    since: "0.4.0"                # the release tag this shipped in
    impl: android/app/src/main/java/com/prince/turtlekeyboard/commands/FixCommand.java
```

`state: beta` and `state: stable` require both `since` and `impl`. CI verifies the `impl` path actually exists in the repo.

## Running the parity check locally

```bash
npm install --no-save js-yaml ajv
node .github/scripts/parity-check.mjs
```

Exits non-zero if any YAML fails the schema or any `impl` path is missing.
