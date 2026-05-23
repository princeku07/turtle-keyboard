#!/usr/bin/env node
// Hard-gates design-system integrity on PRs.
//
// Hard fails (exit 1):
//   - design-system/tokens.json fails JSON Schema validation (ajv, draft 2020-12)
//   - re-importing V1.tokens.json produces a tokens.json that drifts from the
//     committed one (designer-led Figma export wasn't followed by import-figma.mjs)
//   - regenerating from tokens.json produces output that drifts from the committed
//     platform artifacts (Android XML, iOS Swift, landing-app CSS, preview.html)
//
// Local invocation: `node .github/scripts/design-system-check.mjs`.

import { readFileSync } from "node:fs";
import { execSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv from "ajv/dist/2020.js";

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(__dirname, "..", "..");
const dsDir = join(repoRoot, "design-system");

const generatedPaths = [
  "design-system/tokens.json",
  "android/app/src/main/res/values/colors_tokens.xml",
  "android/app/src/main/res/values/dimens_tokens.xml",
  "android/app/src/main/res/values/styles_tokens.xml",
  "ios/TurtleKeyboard/Generated/BrandTokens.swift",
  "lading-app/lib/design-system.css",
  "design-system/preview.html",
];

// 1. Re-import V1.tokens.json so tokens.json reflects the latest Figma export.
execSync("node import-figma.mjs", { cwd: dsDir, stdio: "inherit" });

// 2. Schema validate the (re-imported) tokens.json.
const schema = JSON.parse(readFileSync(join(dsDir, "tokens.schema.json"), "utf8"));
const tokens = JSON.parse(readFileSync(join(dsDir, "tokens.json"), "utf8"));

const ajv = new Ajv({ allErrors: true, strict: false });
const validate = ajv.compile(schema);
if (!validate(tokens)) {
  console.error("design-system: tokens.json failed schema validation:");
  for (const err of validate.errors) {
    console.error(`  - ${err.instancePath || "/"} ${err.message}`);
  }
  process.exit(1);
}

// 3. Regenerate platform artifacts.
execSync("node build.mjs", { cwd: dsDir, stdio: "inherit" });

// 4. Drift check — tokens.json + generated files must match what's committed.
const dirty = execSync(`git status --porcelain ${generatedPaths.join(" ")}`, {
  cwd: repoRoot,
  encoding: "utf8",
}).trim();

if (dirty) {
  console.error("design-system: drift from committed state. Run `node design-system/import-figma.mjs && node design-system/build.mjs` and commit:");
  for (const line of dirty.split("\n")) console.error(`  ${line}`);
  console.error("\nDiff:");
  try {
    execSync(`git --no-pager diff -- ${generatedPaths.join(" ")}`, { cwd: repoRoot, stdio: "inherit" });
  } catch {}
  process.exit(1);
}

console.log("design-system: schema OK, generated outputs in sync.");
