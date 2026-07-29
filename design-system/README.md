# design-system

Single source of truth for cross-platform design tokens. One pipeline, three roles of file:

| File(s) | Edited by | Role |
|---|---|---|
| `Value.tokens.json`, `Value.tokens 2.json`, `Value.tokens 3.json` | **Designer** (Figma → Export Variables, per collection) | Authoritative palette + typography + radius. Verbatim Figma DTCG exports with Title Case keys, color objects, and Figma round-trip metadata. Never hand-edited. |
| `token.json` | **`merge-figma.mjs` writes it** | Build input. Merges the three Value exports into a single tree, wrapping the bare-number radius file under `Radius` and the deep typography file under `Typography`. |
| `build.mjs` outputs | **`build.mjs` writes them** | Platform-native files: Android XML, iOS Swift, landing CSS, plus `preview.html`. Slugifies Title Case keys and extracts hex from color objects at emit time. |

Flow on every Figma re-export:

```
Value.tokens*.json   (Figma exports)
     │  merge-figma.mjs   (wraps + concatenates)
     ▼
token.json           (W3C DTCG)
     │  build.mjs         (slugifies, emits platforms)
     ▼
Android XML / iOS Swift / landing CSS / preview.html
```

The pre-commit hook chains both steps when any of those files is staged.

## Regenerate

```sh
node design-system/merge-figma.mjs   # Value.tokens*.json → token.json
node design-system/build.mjs         # token.json → platform files
```

The merge is destructive — `token.json` is overwritten verbatim from the
Value exports. The build runs a zero-dep shape check before writing (bad hex
values, unknown `$type`, missing `$value`).

## Pre-commit hook (recommended)

Auto-chains the merge + build pipeline whenever you stage a change to any
`Value.tokens*.json` or `token.json`, and adds the regenerated files to the
same commit. One-time setup per clone:

```sh
git config core.hooksPath .githooks
```

The hook lives at `.githooks/pre-commit` — tracked, transparent, no Node deps
beyond `node` itself. If none of the watched files are staged the hook is a
no-op; if Node isn't installed the hook blocks the commit with a clear message.

## Pre-PR check (matches CI)

```sh
node .github/scripts/design-system-check.mjs
```

This re-merges and rebuilds, then fails if anything drifts from what's
committed. CI runs the same command on every PR touching `design-system/**`
or any generated artifact (see `.github/workflows/design-system.yml`).

Outputs (all committed, all consumed natively — no Node on platform build paths):

| Platform | File |
|---|---|
| Android | `colors_tokens.xml`, `dimens_tokens.xml`, `styles_tokens.xml` (auto-merged by aapt) |
| iOS     | `ios/TurtleKeyboard/Generated/BrandTokens.swift` (add to Xcode project when wiring) |
| Landing | `lading-app/lib/design-system.css` (`@import` from `globals.css` when wiring) |
| Docs    | `design-system/preview.html` (open in a browser to inspect every token visually) |

## Figma ↔ repo workflow

Designers own the palette in native Figma Variables; the repo follows. No
Tokens Studio, no paid plan.

### Designer flow

1. Edit values in Figma's **Local Variables** panel.
2. Export each Variables collection to JSON (Figma's native export or any free
   plugin that emits the W3C DTCG shape — `{ hex, colorSpace, components }`
   for colors, plain numbers for radius/typography metrics, plain strings for
   font families/weights).
3. Save each export over its matching `design-system/Value.tokens*.json`
   (one file per collection). Open a PR.
   - The `design-tokens` branch is kept fast-forwarded to `main` automatically
     (`.github/workflows/design-system-branch-sync.yml`), so the GitHub web UI
     "edit this file" flow can target it without creating a fresh branch first.
   - The pre-commit hook re-merges into `token.json` and regenerates platform
     files in the same commit, so a PR that only changes a Value export still
     lands a fully consistent diff.

### Auto-published URLs

The `Design system · Pages` workflow deploys on every merge to `main`:

| URL | Use |
|---|---|
| `https://<owner>.github.io/turtle-keyboard/` | Live preview gallery (rendered `preview.html`) |
| `https://<owner>.github.io/turtle-keyboard/token.json` | Merged W3C DTCG tokens, public reference |
| `https://<owner>.github.io/turtle-keyboard/Value.tokens*.json` | Raw per-collection Figma exports |

Replace `<owner>` with the GitHub user/org once Pages is enabled in repo settings.

## Token shape

After the merge, `token.json` follows the W3C Design Tokens draft — `$value`
+ `$type` per leaf, with the Figma round-trip metadata kept under
`$extensions` on every leaf. Example color leaf:

```json
{
  "Brand": {
    "Main Brand": {
      "$type": "color",
      "$value": { "hex": "#009F69", "colorSpace": "srgb", "components": [0, 0.624, 0.412], "alpha": 1 },
      "$extensions": { "com.figma.variableId": "VariableID:17:520", "com.figma.scopes": ["ALL_FILLS"] }
    }
  }
}
```

Slug naming at emit time is mechanical: Figma's `Brand / Main Brand` →
`brand.main-brand`, `Brown / 500` → `brown.500`. The build emits
`brand_main_brand` / `brandMainBrand` / `--brand-main-brand` across platforms.

Hand-edits to `token.json` survive only until the next merge — designer-led
changes should go in Figma and flow through `Value.tokens*.json`.

## Migrating to Style Dictionary

The JSON is W3C-shaped, so swapping `build.mjs` for Style Dictionary v4 is a config
swap — no token edits required. Do this when we need themes, aliases, or deeper
component-level token chains.
