#!/usr/bin/env node
// Validates commands/*.yaml against the schema, checks that beta/stable impl
// paths exist on disk, detects orphaned command implementations, and posts
// a sticky parity matrix comment on the PR.
//
// Hard fails (exit 1):
//   - YAML invalid or fails schema
//   - support.<platform>.impl missing on disk for beta/stable
//   - new file under android/.../commands/ or ios/.../Commands/ that no YAML references
//
// Soft warnings (commented but non-blocking):
//   - Command shipped on one platform but planned/in-progress on another

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";
import yaml from "js-yaml";
import Ajv from "ajv/dist/2020.js";

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(__dirname, "..", "..");
const commandsDir = join(repoRoot, "commands");

const schema = JSON.parse(readFileSync(join(commandsDir, "_schema.json"), "utf8"));
const ajv = new Ajv({ allErrors: true, strict: false });
const validate = ajv.compile(schema);

const errors = [];
const warnings = [];
const commands = [];

for (const file of readdirSync(commandsDir)) {
  if (!file.endsWith(".yaml") || file.startsWith("_")) continue;
  const path = join(commandsDir, file);

  let doc;
  try {
    doc = yaml.load(readFileSync(path, "utf8"));
  } catch (e) {
    errors.push(`${file}: invalid YAML — ${e.message}`);
    continue;
  }

  if (!validate(doc)) {
    for (const err of validate.errors) {
      errors.push(`${file}: ${err.instancePath || "(root)"} ${err.message}`);
    }
    continue;
  }

  const expectedId = file.replace(/\.yaml$/, "");
  if (doc.id !== expectedId) {
    errors.push(`${file}: id "${doc.id}" does not match filename`);
  }

  for (const platform of ["android", "ios"]) {
    const sup = doc.support[platform];
    if ((sup.state === "stable" || sup.state === "beta") && sup.impl) {
      if (!existsSync(join(repoRoot, sup.impl))) {
        errors.push(
          `${file}: support.${platform}.impl points to missing file ${sup.impl}`
        );
      }
    }
  }

  commands.push({ file, doc });
}

const shipped = (s) => s === "stable" || s === "beta";
for (const { doc } of commands) {
  const a = doc.support.android.state;
  const i = doc.support.ios.state;
  if (shipped(a) && !shipped(i)) {
    warnings.push(`${doc.name}: shipped on Android (${a}) but iOS is ${i}`);
  }
  if (shipped(i) && !shipped(a)) {
    warnings.push(`${doc.name}: shipped on iOS (${i}) but Android is ${a}`);
  }
}

let changedFiles = [];
const baseSha = process.env.BASE_SHA;
const headSha = process.env.HEAD_SHA;
if (baseSha && headSha) {
  try {
    changedFiles = execSync(`git diff --name-only ${baseSha} ${headSha}`, {
      cwd: repoRoot,
    })
      .toString()
      .trim()
      .split("\n")
      .filter(Boolean);
  } catch (e) {
    console.warn("git diff failed, skipping orphan check:", e.message);
  }
}

const allImpls = new Set();
for (const { doc } of commands) {
  for (const p of ["android", "ios"]) {
    if (doc.support[p].impl) allImpls.add(doc.support[p].impl);
  }
}

const androidCmdRe = /^android\/.*\/commands\/.+Command\.(java|kt)$/;
const iosCmdRe = /^ios\/.*Commands\/.+Command\.swift$/;
for (const f of changedFiles) {
  if ((androidCmdRe.test(f) || iosCmdRe.test(f)) && !allImpls.has(f)) {
    errors.push(
      `${f}: looks like a command implementation but no commands/<id>.yaml references it. Add or update the registry.`
    );
  }
}

const stateLabel = {
  stable: "stable",
  beta: "beta",
  "in-progress": "wip",
  planned: "planned",
  deprecated: "deprecated",
};

const lines = [];
lines.push("## Turtle parity report");
lines.push("");
lines.push(`Commands registered: **${commands.length}**`);
lines.push("");
lines.push("| Command | Android | iOS | Backend |");
lines.push("|---------|---------|-----|---------|");
const sorted = [...commands].sort((a, b) => a.doc.id.localeCompare(b.doc.id));
for (const { doc } of sorted) {
  const cell = (s) => stateLabel[s] || s;
  lines.push(
    `| \`${doc.name}\` | ${cell(doc.support.android.state)} | ${cell(
      doc.support.ios.state
    )} | ${cell(doc.support.backend.state)} |`
  );
}

if (warnings.length) {
  lines.push("");
  lines.push("### Parity gaps (non-blocking)");
  for (const w of warnings) lines.push(`- ${w}`);
}

if (errors.length) {
  lines.push("");
  lines.push("### Errors (blocking)");
  for (const e of errors) lines.push(`- ${e}`);
} else {
  lines.push("");
  lines.push("All checks passed.");
}

const report = lines.join("\n");
console.log(report);

const token = process.env.GITHUB_TOKEN;
const prNumber = process.env.PR_NUMBER;
const repo = process.env.GITHUB_REPOSITORY;

if (token && prNumber && repo) {
  const marker = "<!-- turtle-parity-bot -->";
  const body = `${marker}\n${report}`;
  const headers = {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
    Accept: "application/vnd.github+json",
  };
  const commentsUrl = `https://api.github.com/repos/${repo}/issues/${prNumber}/comments`;
  try {
    const existing = await fetch(commentsUrl, { headers }).then((r) => r.json());
    const mine = Array.isArray(existing)
      ? existing.find((c) => c.body && c.body.includes(marker))
      : null;
    if (mine) {
      await fetch(
        `https://api.github.com/repos/${repo}/issues/comments/${mine.id}`,
        { method: "PATCH", headers, body: JSON.stringify({ body }) }
      );
    } else {
      await fetch(commentsUrl, {
        method: "POST",
        headers,
        body: JSON.stringify({ body }),
      });
    }
  } catch (e) {
    console.warn("Failed to post parity comment:", e.message);
  }
}

if (errors.length) process.exit(1);
