#!/usr/bin/env node
// Hard-gates design-system integrity on PRs.
//
// Hard fails (exit 1):
//   - re-merging Value.tokens*.json produces a token.json that drifts from
//     the committed one (designer-led Figma export wasn't followed by
//     merge-figma.mjs)
//   - regenerating from token.json produces output that drifts from the
//     committed platform artifacts (Android XML, iOS Swift, landing-app CSS,
//     preview.html)
//
// Local invocation: `node .github/scripts/design-system-check.mjs`.

import { execSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(__dirname, "..", "..");
const dsDir = join(repoRoot, "design-system");

const generatedPaths = [
  "design-system/token.json",
  "android/app/src/main/res/values/colors_tokens.xml",
  "android/app/src/main/res/values/dimens_tokens.xml",
  "android/app/src/main/res/values/styles_tokens.xml",
  "ios/TurtleKeyboard/Generated/BrandTokens.swift",
  "lading-app/lib/design-system.css",
  "design-system/preview.html",
];

// 1. Re-merge Value.tokens*.json so token.json reflects the latest Figma export.
execSync("node merge-figma.mjs", { cwd: dsDir, stdio: "inherit" });

// 2. Regenerate platform artifacts. Build runs its own shape check on token.json.
execSync("node build.mjs", { cwd: dsDir, stdio: "inherit" });

// 3. Drift check — token.json + generated files must match what's committed.
const dirty = execSync(`git status --porcelain ${generatedPaths.join(" ")}`, {
  cwd: repoRoot,
  encoding: "utf8",
}).trim();

if (dirty) {
  console.error("design-system: drift from committed state. Run `node design-system/merge-figma.mjs && node design-system/build.mjs` and commit:");
  for (const line of dirty.split("\n")) console.error(`  ${line}`);
  console.error("\nDiff:");
  try {
    execSync(`git --no-pager diff -- ${generatedPaths.join(" ")}`, { cwd: repoRoot, stdio: "inherit" });
  } catch {}
  process.exit(1);
}

console.log("design-system: merged, built, and in sync.");
