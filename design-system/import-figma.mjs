#!/usr/bin/env node
// Converts the raw Figma Variables export (V1.tokens.json — Title Case keys,
// color-object $values, Figma round-trip $extensions) into the kebab-case +
// hex-string shape that build.mjs and tokens.schema.json consume. Destructive:
// overwrites tokens.json. Re-run after each Figma re-export.
//
//   node design-system/import-figma.mjs

import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const v1Path  = join(__dirname, "V1.tokens.json");
const outPath = join(__dirname, "tokens.json");

const v1 = JSON.parse(readFileSync(v1Path, "utf8"));
const out = { "$schema": "./tokens.schema.json" };
let leafCount = 0;

convert(v1, out);

writeFileSync(outPath, JSON.stringify(out, null, 2) + "\n");
console.log(`design-system: imported ${leafCount} tokens from V1.tokens.json → tokens.json`);

function convert(src, dst) {
  for (const [k, v] of Object.entries(src)) {
    if (k === "$extensions" || k === "$schema" || k === "$description") continue;
    if (!v || typeof v !== "object") continue;
    const slug = slugify(k);
    if (!slug) continue;
    if ("$value" in v && "$type" in v) {
      if (v.$type === "color") {
        const hex = typeof v.$value === "object" && v.$value !== null && "hex" in v.$value
          ? v.$value.hex
          : typeof v.$value === "string" ? v.$value : null;
        if (!hex) throw new Error(`${k}: no extractable hex value`);
        dst[slug] = { "$value": hex.toUpperCase(), "$type": "color" };
        leafCount++;
      } else {
        dst[slug] = { "$value": v.$value, "$type": v.$type };
        leafCount++;
      }
    } else {
      dst[slug] = {};
      convert(v, dst[slug]);
    }
  }
}

function slugify(name) {
  return String(name)
    .toLowerCase()
    .replace(/\s+/g, "-")
    .replace(/[^a-z0-9-]/g, "")
    .replace(/-+/g, "-")
    .replace(/^-+|-+$/g, "");
}
