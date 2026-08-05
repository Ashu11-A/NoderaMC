// The site may not claim a capability this system does not have.
//
// This is the project's own rule, not a house style: `docs/ROADMAP.md` carries ✅ / 🚧 / ⛔ per task,
// and the difference between "a batch commits only with a quorum" (engine task 5, ✅ headless) and
// "every action in Minecraft is validated" (minecraft task 2, 🚧) is the difference between a report
// and a lie. A marketing page is where that line gets crossed, because nobody reviewing prose has
// the register open beside them.
//
// So every capability claim has a row in `web/content/claims.json` naming the file that backs it and
// the string that must be found there. When either side moves, the build fails — which is the only
// arrangement in which the copy and the evidence cannot drift apart quietly.
import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { repositoryDirectory } from "nodera-ui/layout";
import { sourceFiles } from "nodera-ui/token-audit";
import { siteDirectory } from "./layout.mjs";

const claims = JSON.parse(readFileSync(path.join(siteDirectory, "content/claims.json"), "utf8"));

test("there are claims to check", () => {
  assert.ok(claims.length >= 10, `only ${claims.length} claims registered`);
});

test("every claim's copy is still on the page that makes it", () => {
  for (const row of claims) {
    const page = path.join(siteDirectory, row.page);
    assert.ok(existsSync(page), `${row.page} does not exist`);
    const text = readFileSync(page, "utf8").replace(/\s+/g, " ");
    assert.ok(
      text.includes(row.claim.replace(/\s+/g, " ")),
      `${row.page} no longer says "${row.claim.slice(0, 60)}…" — delete the claim or restore the copy`,
    );
  }
});

test("every claim's evidence is still in the file it cites", () => {
  for (const row of claims) {
    const source = path.join(repositoryDirectory, row.source);
    assert.ok(existsSync(source), `${row.source} does not exist`);
    const text = readFileSync(source, "utf8").replace(/\s+/g, " ");
    assert.ok(
      text.includes(row.must_match.replace(/\s+/g, " ")),
      `${row.source} no longer contains "${row.must_match}", so ${row.page}'s claim has nothing ` +
        "behind it. This is the check working, not the check being wrong.",
    );
  }
});

test("claims cite category registers, never a directory that has moved", () => {
  // `docs/app/**` and `docs/mobile/**` became `docs/frontend/**`. A claim citing a path that no
  // longer exists would fail the assertion above anyway; naming the rule here is what stops somebody
  // "fixing" it by pointing at the old tree.
  for (const row of claims) {
    assert.ok(
      !row.source.startsWith("docs/app/") && !row.source.startsWith("docs/mobile/"),
      `${row.source}: those categories are one category now (docs/frontend)`,
    );
  }
});

/* --------------------------------------------------------------------------- the banned words */

/** Every word of prose the site ships: its components, its pages and its MDX. */
function prose() {
  return [
    ...sourceFiles(path.join(siteDirectory, "src"), /\.(tsx|mdx)$/),
    ...sourceFiles(path.join(siteDirectory, "content"), /\.mdx$/),
  ].map((file) => [
    path.relative(siteDirectory, file),
    // Comments are the place where a rule explains itself by quoting what it forbids.
    readFileSync(file, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, ""),
  ]);
}

test("no superlative the software cannot cash", () => {
  // Each of these is a specific claim rather than a stylistic preference. "Seamless" and "just
  // works" claim an absence of failure modes in a peer-to-peer system whose own registers list
  // fifty-six open ones. "Fully compatible" is contradicted by COMPATIBILITY.md. "Military-grade"
  // means nothing. Bare "secure" is the one an adversary reads as an invitation.
  const banned = [/\bseamless/i, /\bjust works\b/i, /\bfully compatible\b/i, /\b100%\s/i, /military-grade/i];
  const offenders = [];
  for (const [name, text] of prose()) {
    for (const pattern of banned) {
      const hit = pattern.exec(text);
      if (hit) offenders.push(`${name}: ${hit[0]}`);
    }
  }
  assert.deepEqual(offenders, [], `unsupportable copy:\n  ${offenders.join("\n  ")}`);
});

test("'unlimited' appears only where it is explained", () => {
  // The word has a specific and counter-intuitive meaning in this project: a zero upload cap means
  // unlimited, and the day that default shipped it told every worker to serve nothing. Using it
  // without the explanation nearby is how that confusion spreads to the people configuring it.
  const offenders = [];
  for (const [name, text] of prose()) {
    for (const hit of text.matchAll(/unlimited/gi)) {
      const around = text.slice(Math.max(0, hit.index - 200), hit.index + 200);
      if (!/\b0\b|\bzero\b/i.test(around)) offenders.push(`${name}: ${hit[0]}`);
    }
  }
  assert.deepEqual(offenders, [], `"unlimited" with no explanation of what zero means:\n  ${offenders.join("\n  ")}`);
});
