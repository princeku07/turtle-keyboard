# design-system

Single source of truth for cross-platform design tokens. Two files, one pipeline:

| File | Edited by | Role |
|---|---|---|
| `V1.tokens.json` | **Designer** (Figma → Export Variables) | Authoritative palette. Verbatim Figma DTCG export — Title Case keys, color objects, Figma round-trip metadata. Never hand-edited. |
| `tokens.json` | **`import-figma.mjs` writes it** | Build input. Same data as V1 but normalized to kebab-case + hex strings. The schema and `build.mjs` consume this. |

Flow on every Figma re-export:

```
V1.tokens.json (Figma export)
     │  import-figma.mjs   (slugifies, extracts hex)
     ▼
tokens.json
     │  build.mjs          (emits platform files)
     ▼
Android XML / iOS Swift / landing CSS / preview.html
```

The pre-commit hook chains both steps when either file is staged.

## Regenerate

```sh
node design-system/import-figma.mjs   # V1.tokens.json → tokens.json
node design-system/build.mjs          # tokens.json → platform files
```

The import is destructive — `tokens.json` is overwritten verbatim from V1. The
build runs a zero-dep shape check before writing (bad hex values, unknown
`$type`, missing `$value`).

## Pre-commit hook (recommended)

Auto-chains the import + build pipeline whenever you stage a change to
`V1.tokens.json` or `tokens.json`, and adds the regenerated files to the same
commit. One-time setup per clone:

```sh
git config core.hooksPath .githooks
```

The hook lives at `.githooks/pre-commit` — tracked, transparent, no Node deps
beyond `node` itself. If neither file is staged the hook is a no-op; if Node
isn't installed the hook blocks the commit with a clear message.

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

## Figma ↔ repo workflow

Designers own the palette in native Figma Variables; the repo follows. No
Tokens Studio, no paid plan.

### Designer flow

1. Edit colors in Figma's **Local Variables** panel.
2. Export Variables to JSON (Figma's native Variables export or any free plugin
   that emits the W3C DTCG shape with the `{ hex, colorSpace, components }`
   color-object form).
3. Save the export over `design-system/V1.tokens.json`. Open a PR.
   - The `design-tokens` branch is kept fast-forwarded to `main` automatically
     (`.github/workflows/design-system-branch-sync.yml`), so the GitHub web UI
     "edit this file" flow can target it without creating a fresh branch first.
   - The pre-commit hook re-imports V1 → tokens.json + regenerates platform
     files in the same commit, so a PR that only changes V1.tokens.json still
     lands a fully consistent diff.

### Auto-published URLs

The `Design system · Pages` workflow deploys on every merge to `main`:

| URL | Use |
|---|---|
| `https://<owner>.github.io/turtle-keyboard/` | Live preview gallery (rendered `preview.html`) |
| `https://<owner>.github.io/turtle-keyboard/tokens.json` | Normalized tokens (kebab-case + hex), public reference |
| `https://<owner>.github.io/turtle-keyboard/V1.tokens.json` | Raw Figma export, public reference |

Replace `<owner>` with the GitHub user/org once Pages is enabled in repo settings.

## Token shape

After import, `tokens.json` follows the W3C Design Tokens draft — `$value` +
`$type` per leaf, nested groups for organisation:

```json
{
  "brown": {
    "500": { "$value": "#AE8A7C", "$type": "color" }
  }
}
```

Slug naming is mechanical: Figma's `Brown 500` → `brown.500`, `Primary Colors`
→ `primary-colors`, `Sea Breeze` → `sea-breeze`. The build emits
`brown_500`/`brownColors500`/`--brown-500` accordingly across platforms.

Hand-edits to `tokens.json` survive only until the next Figma re-export
(`import-figma.mjs` overwrites). If you need a non-Figma token, add it to V1 in
Figma and re-export, or extend `import-figma.mjs` to merge an additional file.

### Adding tokens from the primitive layer

Primitive (V1) tokens come from a Figma re-export — re-run **Export Variables**
on the Figma file, save over `design-system/V1.tokens.json`, regenerate. Slugs
follow Figma names mechanically: `Brown 500` → `brown.500`, `Sea Breeze` →
`sea-breeze` under whatever group it sits in.

## Migrating to Style Dictionary

The JSON is W3C-shaped, so swapping `build.mjs` for Style Dictionary v4 is a config
swap — no token edits required. Do this when we need themes, aliases, or deeper
component-level token chains.
