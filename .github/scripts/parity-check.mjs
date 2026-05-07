#!/usr/bin/env node
// Validates commands/*.yaml and integrations/*.yaml against their schemas, checks
// that beta/stable impl paths exist on disk, detects orphaned implementations, and
// posts a sticky parity matrix comment on the PR.
//
// Hard fails (exit 1):
//   - YAML invalid or fails schema (commands or integrations)
//   - support.<platform>.impl missing on disk for beta/stable
//   - new file under android/.../commands/ or ios/.../Commands/ that no command YAML references
//   - integration command listed but command name doesn't match any commands/<name>.yaml
//     AND no integration's `commands:` list claims it (we only check cross-link integrity
//     for commands that DO have backing YAMLs)
//
// Soft warnings (commented but non-blocking):
//   - Command or integration shipped on one platform but planned/in-progress on another

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";
import yaml from "js-yaml";
import Ajv from "ajv/dist/2020.js";

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(__dirname, "..", "..");
const commandsDir = join(repoRoot, "commands");
const integrationsDir = join(repoRoot, "integrations");

const ajv = new Ajv({ allErrors: true, strict: false });

const errors = [];
const warnings = [];

// ---------- Commands -----------------------------------------------------

const commandSchema = JSON.parse(readFileSync(join(commandsDir, "_schema.json"), "utf8"));
const validateCommand = ajv.compile(commandSchema);
const commands = [];

for (const file of readdirSync(commandsDir)) {
  if (!file.endsWith(".yaml") || file.startsWith("_")) continue;
  const path = join(commandsDir, file);

  let doc;
  try {
    doc = yaml.load(readFileSync(path, "utf8"));
  } catch (e) {
    errors.push(`commands/${file}: invalid YAML — ${e.message}`);
    continue;
  }

  if (!validateCommand(doc)) {
    for (const err of validateCommand.errors) {
      errors.push(`commands/${file}: ${err.instancePath || "(root)"} ${err.message}`);
    }
    continue;
  }

  const expectedId = file.replace(/\.yaml$/, "");
  if (doc.id !== expectedId) {
    errors.push(`commands/${file}: id "${doc.id}" does not match filename`);
  }

  for (const platform of ["android", "ios"]) {
    const sup = doc.support[platform];
    if ((sup.state === "stable" || sup.state === "beta") && sup.impl) {
      if (!existsSync(join(repoRoot, sup.impl))) {
        errors.push(
          `commands/${file}: support.${platform}.impl points to missing file ${sup.impl}`
        );
      }
    }
  }

  commands.push({ file, doc });
}

// ---------- Integrations -------------------------------------------------

let integrations = [];
const hasIntegrationsDir = existsSync(integrationsDir);
if (hasIntegrationsDir) {
  const integrationSchema = JSON.parse(
    readFileSync(join(integrationsDir, "_schema.json"), "utf8")
  );
  const validateIntegration = ajv.compile(integrationSchema);

  for (const file of readdirSync(integrationsDir)) {
    if (!file.endsWith(".yaml") || file.startsWith("_")) continue;
    const path = join(integrationsDir, file);

    let doc;
    try {
      doc = yaml.load(readFileSync(path, "utf8"));
    } catch (e) {
      errors.push(`integrations/${file}: invalid YAML — ${e.message}`);
      continue;
    }

    if (!validateIntegration(doc)) {
      for (const err of validateIntegration.errors) {
        errors.push(`integrations/${file}: ${err.instancePath || "(root)"} ${err.message}`);
      }
      continue;
    }

    const expectedId = file.replace(/\.yaml$/, "");
    if (doc.id !== expectedId) {
      errors.push(`integrations/${file}: id "${doc.id}" does not match filename`);
    }

    for (const platform of ["android", "ios"]) {
      const sup = doc.support[platform];
      if ((sup.state === "stable" || sup.state === "beta") && sup.impl) {
        if (!existsSync(join(repoRoot, sup.impl))) {
          errors.push(
            `integrations/${file}: support.${platform}.impl points to missing file ${sup.impl}`
          );
        }
      }
      // Encourage impl to point at a real file for in-progress too (optional).
      if (sup.state === "in-progress" && sup.impl && !existsSync(join(repoRoot, sup.impl))) {
        warnings.push(
          `integrations/${file}: support.${platform}.impl set to ${sup.impl} but file does not exist (in-progress)`
        );
      }
    }

    integrations.push({ file, doc });
  }
}

// Cross-reference: integration commands that have backing YAMLs link cleanly;
// duplicates across integrations would be a bug — same command name claimed by two.
const commandIds = new Set(commands.map((c) => c.doc.id));
const claimedBy = new Map(); // command name → integration id
for (const { doc, file } of integrations) {
  for (const cmd of doc.commands || []) {
    if (claimedBy.has(cmd) && claimedBy.get(cmd) !== doc.id) {
      errors.push(
        `integrations/${file}: command "${cmd}" already claimed by integration "${claimedBy.get(cmd)}"`
      );
    }
    claimedBy.set(cmd, doc.id);
  }
}

// ---------- Parity warnings ---------------------------------------------

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
for (const { doc } of integrations) {
  const a = doc.support.android.state;
  const i = doc.support.ios.state;
  if (shipped(a) && !shipped(i)) {
    warnings.push(
      `integration ${doc.id}: shipped on Android (${a}) but iOS is ${i}`
    );
  }
  if (shipped(i) && !shipped(a)) {
    warnings.push(
      `integration ${doc.id}: shipped on iOS (${i}) but Android is ${a}`
    );
  }
}

// ---------- Orphan implementation detection ------------------------------

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

const allCommandImpls = new Set();
for (const { doc } of commands) {
  for (const p of ["android", "ios"]) {
    if (doc.support[p].impl) allCommandImpls.add(doc.support[p].impl);
  }
}

const allIntegrationImpls = new Set();
for (const { doc } of integrations) {
  for (const p of ["android", "ios"]) {
    if (doc.support[p].impl) allIntegrationImpls.add(doc.support[p].impl);
  }
}

const androidCmdRe = /^android\/.*\/commands\/.+Command\.(java|kt)$/;
const iosCmdRe = /^ios\/.*Commands\/.+Command\.swift$/;
const androidIntegrationRe = /^android\/.*\/.+Integration\.(java|kt)$/;
const iosIntegrationRe = /^ios\/.*\/.+Integration\.swift$/;
for (const f of changedFiles) {
  if ((androidCmdRe.test(f) || iosCmdRe.test(f)) && !allCommandImpls.has(f)) {
    errors.push(
      `${f}: looks like a command implementation but no commands/<id>.yaml references it. Add or update the registry.`
    );
  }
  if ((androidIntegrationRe.test(f) || iosIntegrationRe.test(f)) && !allIntegrationImpls.has(f)) {
    errors.push(
      `${f}: looks like an integration but no integrations/<id>.yaml references it. Add or update the registry.`
    );
  }
}

// ---------- Render report ------------------------------------------------

const stateLabel = {
  stable: "stable",
  beta: "beta",
  "in-progress": "wip",
  planned: "planned",
  deprecated: "deprecated",
};
const cell = (s) => stateLabel[s] || s;

const lines = [];
lines.push("## Turtle parity report");
lines.push("");
lines.push(
  `Commands registered: **${commands.length}** · Integrations registered: **${integrations.length}**`
);

// Commands table
lines.push("");
lines.push("### Commands");
lines.push("");
lines.push("| Command | Android | iOS | Backend | Integration |");
lines.push("|---------|---------|-----|---------|-------------|");
const sortedCmds = [...commands].sort((a, b) => a.doc.id.localeCompare(b.doc.id));
for (const { doc } of sortedCmds) {
  const owner = claimedBy.get(doc.id);
  lines.push(
    `| \`${doc.name}\` | ${cell(doc.support.android.state)} | ${cell(
      doc.support.ios.state
    )} | ${cell(doc.support.backend.state)} | ${owner ? `\`${owner}\`` : "—"} |`
  );
}

// Integrations table
if (integrations.length) {
  lines.push("");
  lines.push("### Integrations");
  lines.push("");
  lines.push("| Integration | Surfaces | Commands | Android | iOS |");
  lines.push("|-------------|----------|----------|---------|-----|");
  const sortedIntegrations = [...integrations].sort((a, b) =>
    a.doc.id.localeCompare(b.doc.id)
  );
  for (const { doc } of sortedIntegrations) {
    const surfaces = (doc.surfaces || []).join(", ");
    const cmds = (doc.commands || [])
      .map((c) => (commandIds.has(c) ? `[\`${c}\`](../commands/${c}.yaml)` : `\`${c}\``))
      .join(", ") || "—";
    lines.push(
      `| \`${doc.id}\` | ${surfaces} | ${cmds} | ${cell(doc.support.android.state)} | ${cell(doc.support.ios.state)} |`
    );
  }
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

// ---------- Sticky PR comment --------------------------------------------

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
