# design-system

Single source of truth for cross-platform design tokens (brand colours, spacing,
typography). Edit `tokens.json`, regenerate, commit the outputs.

## Regenerate

```sh
cd design-system
node build.mjs
```

`build.mjs` runs a zero-dep shape check before writing — bad hex values, unknown
`$type`, and missing `$value` fail fast.

## Pre-commit hook (recommended)

Auto-regenerates platform artifacts whenever you stage a change to `tokens.json`,
and adds the regenerated files to the same commit. One-time setup per clone:

```sh
git config core.hooksPath .githooks
```

The hook lives at `.githooks/pre-commit` — tracked, transparent, no Node deps beyond
`node` itself. If `tokens.json` is unstaged the hook is a no-op; if Node isn't
installed the hook blocks the commit with a clear message.

## Pre-PR check (matches CI)

```sh
# from repo root, once
npm install --no-save ajv
node .github/scripts/design-system-check.mjs
```

This runs full JSON-Schema validation (Ajv, draft 2020-12) and regenerates the
platform artifacts, then fails if anything drifts from what's committed. CI
runs the same command on every PR touching `design-system/**` or any generated
artifact (see `.github/workflows/design-system.yml`).

Outputs (all committed, all consumed natively — no Node on platform build paths):

| Platform | File |
|---|---|
| Android | `colors_tokens.xml`, `dimens_tokens.xml`, `styles_tokens.xml` (auto-merged by aapt) |
| iOS     | `ios/TurtleKeyboard/Generated/BrandTokens.swift` (add to Xcode project when wiring) |
| Landing | `lading-app/lib/design-system.css` (`@import` from `globals.css` when wiring) |
| Docs    | `design-system/preview.html` (open in a browser to inspect every token visually) |

## Token shape

W3C Design Tokens draft format — `$value` + `$type` per leaf. Adding a token:

```json
{
  "brand": {
    "violet": { "$value": "#5B6CFF", "$type": "color" }
  }
}
```

Re-run the generator. Android picks up the new resource automatically (`brand_violet`);
iOS/landing pick up `BrandToken.brandViolet` and `--brand-violet` respectively once
each surface starts consuming.

## Migrating to Style Dictionary

The JSON is W3C-shaped, so swapping `build.mjs` for Style Dictionary v4 is a config
swap — no token edits required. Do this when we need themes, aliases, or deeper
component-level token chains.
