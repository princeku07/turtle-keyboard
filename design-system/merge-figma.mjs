#!/usr/bin/env node
// Merges the per-collection Figma exports (Value.tokens*.json) plus the
// manually-maintained Spacing scale into a single token.json that build.mjs
// consumes. Destructive: token.json is rewritten.
//
// Wrapping rules:
//   Value.tokens.json     (bare-number radius values) → wrapped under "Radius"
//   Value.tokens 2.json   (deep typography tree)      → wrapped under "Typography"
//   Value.tokens 3.json   (color collections)         → top-level groups passed through
//   Spacing.tokens.json   (bare-number spacing scale) → wrapped under "Space"
//   Semantics.tokens.json (legacy semantic colors)    → top-level groups passed through
//
// Re-run after each Figma re-export:
//   node design-system/merge-figma.mjs

import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));

function load(name) {
  const path = join(__dirname, name);
  if (!existsSync(path)) return null;
  return JSON.parse(readFileSync(path, "utf8"));
}

function stripExt(obj) {
  if (!obj) return {};
  const out = {};
  for (const [k, v] of Object.entries(obj)) {
    if (k === "$extensions" || k === "$schema") continue;
    out[k] = v;
  }
  return out;
}

const radius     = load("Value.tokens.json");
const typography = load("Value.tokens 2.json");
const colors     = load("Value.tokens 3.json");
const spacing    = load("Spacing.tokens.json");
const semantics  = load("Semantics.tokens.json");

if (!colors && !typography && !radius && !spacing && !semantics) {
  console.error("merge-figma: no source token files found in design-system/");
  process.exit(1);
}

const merged = {
  ...stripExt(colors),
  ...stripExt(semantics),
  ...(typography ? { Typography: stripExt(typography) } : {}),
  ...(radius     ? { Radius:     stripExt(radius)     } : {}),
  ...(spacing    ? { Space:      stripExt(spacing)    } : {}),
};

writeFileSync(join(__dirname, "token.json"), JSON.stringify(merged, null, 2) + "\n");

const counts = {};
(function walk(o) {
  for (const [k, v] of Object.entries(o)) {
    if (k.startsWith("$")) continue;
    if (v && typeof v === "object") {
      if ("$type" in v) counts[v.$type] = (counts[v.$type] || 0) + 1;
      else walk(v);
    }
  }
})(merged);

const summary = Object.entries(counts).map(([t, n]) => `${n} ${t}`).join(", ");
console.log(`design-system: merged → token.json (${summary || "0 tokens"})`);
