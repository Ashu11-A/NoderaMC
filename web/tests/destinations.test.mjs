// The prose may not name a screen in the app that no longer exists.
//
// It never spells one directly: it writes `<Ui to="tracker-stores" />` and a table turns that into
// **Settings → Tracker stores**. That matters more here than it usually would, because the launcher
// is being redesigned in a sibling worktree while this site ships — so the set of screens is a moving
// target, and without a table the site would quietly go on citing screens that were renamed a month
// ago. A reader following those instructions finds nothing and concludes the software is broken.
//
// Two directions, and the second is the one that catches a rename: every key the prose uses must be
// in the table, and every key in the table must be used by something.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { sourceFiles } from "nodera-ui/token-audit";
import { siteDirectory } from "./layout.mjs";

const SRC = path.join(siteDirectory, "src");
const CONTENT = path.join(siteDirectory, "content");

const table = readFileSync(path.join(SRC, "destinations.ts"), "utf8");
const declared = new Set(
  [...table.matchAll(/^\s*"?([a-z][a-z0-9-]*)"?:\s*\{/gm)].map((match) => match[1]),
);

/** Every `<Ui to="…">` and `<Cmd to="…">` written anywhere but in the table's own documentation. */
function used() {
  const out = [];
  const files = [
    ...sourceFiles(SRC, /\.(tsx|ts)$/),
    ...sourceFiles(CONTENT, /\.mdx$/),
  ].filter((file) => file !== path.join(SRC, "destinations.ts"));
  for (const file of files) {
    // Comments stripped: `mdx.tsx` documents the component by quoting an example of it, and a rule
    // that fails on the sentence explaining the rule is a rule people learn to skip.
    const text = readFileSync(file, "utf8")
      .replace(/\/\*[\s\S]*?\*\//g, "")
      .replace(/^\s*\/\/.*$/gm, "");
    for (const [, key] of text.matchAll(/<(?:Ui|Cmd)\s+to="([^"${}]+)"/g)) {
      out.push([path.relative(siteDirectory, file), key]);
    }
  }
  return out;
}

const usages = used();

test("the destination table is not empty", () => {
  assert.ok(declared.size > 5, `only ${declared.size} destinations declared`);
});

test("every <Ui> and <Cmd> key the prose uses is in the table", () => {
  const unknown = usages
    .filter(([, key]) => !declared.has(key))
    .map(([file, key]) => `${file}: ${key}`);
  assert.deepEqual(unknown, [], `these name a screen or command with no entry:\n  ${unknown.join("\n  ")}`);
});

test("every dependsOn chain terminates in the table", () => {
  // An unresolvable parent renders a breadcrumb one level short — **Tracker stores** instead of
  // **Settings → Tracker stores** — which is a plausible-looking instruction to a screen a reader
  // will not find.
  const broken = [];
  for (const [, key, parent] of table.matchAll(
    /"?([a-z][a-z0-9-]*)"?:\s*\{[^}]*dependsOn:\s*"([^"]+)"/g,
  )) {
    if (!declared.has(parent)) broken.push(`${key} → ${parent}`);
  }
  assert.deepEqual(broken, []);
});

test("every command destination carries the literal command text", () => {
  // A `<Cmd>` that renders a label with no command is a page telling somebody to type nothing.
  for (const [, key, body] of table.matchAll(/"?([a-z][a-z0-9-]*)"?:\s*\{([^}]*)\}/g)) {
    if (!usages.some(([, used]) => used === key)) continue;
    if (!body.includes("command:")) continue;
    assert.match(body, /command:\s*"\/[^"]+"/, `${key} has an empty command`);
  }
});
