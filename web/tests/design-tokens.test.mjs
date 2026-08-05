// The site's classes resolve, its colours are never literals, and it shadows nothing by accident.
//
// # The failure this is the detector for
//
// `web/src/styles.css` imports the launcher's stylesheet across a package boundary, and Tailwind v4
// detects its sources relative to the file that imports `tailwindcss` — which, because of that
// import, is `app/ui/src/styles.css`. Without the explicit `@source` directives beside it, the site
// builds, succeeds, and emits a stylesheet in which **every** class written in `web/src` resolves to
// nothing. The page renders as unstyled HTML, which a screenshot reviewer reads as "plain" rather
// than as broken.
//
// So this suite is not a nicety. It is the only thing standing between that failure and a deploy,
// and the `inspected` count below is what stops it reporting green over an empty set.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { auditTokens, sourceFiles } from "nodera-ui/token-audit";
import { pkg } from "nodera-ui/layout";
import { distDirectory, siteDirectory } from "./layout.mjs";

const SRC = path.join(siteDirectory, "src");
const CONTENT = path.join(siteDirectory, "content");
const audit = auditTokens({ sourceRoots: [SRC, CONTENT], distAssets: path.join(distDirectory, "assets") });

test("every styling class the site writes resolves to a rule in the shipped CSS", () => {
  assert.deepEqual(
    audit.dead,
    [],
    `these classes name something the theme does not declare, so they style nothing:\n  ${audit.dead.join("\n  ")}`,
  );
  assert.ok(audit.inspected > 100, `only ${audit.inspected} class tokens were inspected`);
});

test("the shipped stylesheet is not a near-empty one", () => {
  // The signature of broken source detection, stated as a number. A working build is around 29 kB;
  // a stylesheet with only preflight and the theme in it is about 6.
  assert.ok(
    audit.css.length > 10_000,
    `the built stylesheet is ${audit.css.length} bytes — Tailwind found no sources`,
  );
});

test("no colour literal is written anywhere in the site's own source", () => {
  // There is exactly one declaration of `--brand-1` in this repository and it is in the launcher's
  // stylesheet. A hex typed into a component is a colour that stops following the palette, silently,
  // and the launcher is being repalette'd in a sibling worktree as this ships.
  const offenders = [];
  for (const file of [...sourceFiles(SRC, /\.(tsx|ts|css)$/), ...sourceFiles(CONTENT, /\.mdx$/)]) {
    const text = readFileSync(file, "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
    for (const [literal] of text.matchAll(/#[0-9a-f]{3,8}\b|\b(?:rgba?|hsla?|oklch)\(/gi)) {
      // A fragment identifier is not a colour. `#docs` and `#L-81` are links.
      if (literal.startsWith("#") && !/^#[0-9a-f]{3}$|^#[0-9a-f]{6}$|^#[0-9a-f]{8}$/i.test(literal)) {
        continue;
      }
      offenders.push(`${path.relative(siteDirectory, file)}: ${literal}`);
    }
  }
  assert.deepEqual(offenders, [], `colour literals:\n  ${offenders.join("\n  ")}`);
});

/* ------------------------------------------------------------------ the launcher-redesign seam */

/** Every `--token` the site's own stylesheet declares in a `:root` block. */
function declaredBySite() {
  const css = readFileSync(path.join(SRC, "styles.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
  const root = /:root\s*\{([^}]*)\}/.exec(css);
  assert.ok(root, "web/src/styles.css no longer declares a :root block");
  return [...root[1].matchAll(/(--[a-z0-9-]+)\s*:/gi)].map((match) => match[1]);
}

/** Every `--token` the launcher's stylesheet declares, anywhere. */
function declaredByApp() {
  const css = readFileSync(path.join(pkg("nodera-app-ui"), "src/styles.css"), "utf8").replace(
    /\/\*[\s\S]*?\*\//g,
    "",
  );
  return new Set([...css.matchAll(/(--[a-z0-9-]+)\s*:/gi)].map((match) => match[1]));
}

test("the site shadows exactly the two launcher tokens it says it does", () => {
  // Shadowing is invisible: the value simply changes, on every screen, with nothing to read that
  // says it was deliberate. These two are deliberate — the launcher's `page-canvas` utility is
  // written as `min(100%, var(--canvas-max))`, so restating the variable is how the site reuses that
  // utility at a reading width rather than declaring a second one.
  const app = declaredByApp();
  const shadowed = declaredBySite().filter((token) => app.has(token));
  assert.deepEqual(shadowed.sort(), ["--canvas-gutter", "--canvas-max"]);
});

test("no site token is a reordering of a launcher token", () => {
  // A gate that fires on a merge commit rather than a note somebody has to remember.
  //
  // The launcher redesign introduces `--glow-brand`, a box-shadow bloom. This site declares
  // `--brand-glow`, a background gradient. They are near-anagrams with different TYPES, so a
  // confusion between them puts a shadow value into a `background-image`, which resolves to nothing
  // and renders as a missing hero glow with no error anywhere. When that token lands in the imported
  // stylesheet this test fails, which is the intent: one of the two names has to move, and the merge
  // is the moment to decide which.
  const key = (token) => token.replace(/^--/, "").split("-").sort().join("-");
  const app = new Map([...declaredByApp()].map((token) => [key(token), token]));
  const collisions = [];
  for (const token of declaredBySite()) {
    const twin = app.get(key(token));
    if (twin && twin !== token) collisions.push(`${token} vs the launcher's ${twin}`);
  }
  assert.deepEqual(collisions, [], `near-anagram token names:\n  ${collisions.join("\n  ")}`);
});

test("the site's tinted fill defers to the launcher's, once the launcher has one", () => {
  // `--brand-dimm` is declared as `var(--brand-soft, <derivation>)`. The redesign introduces
  // `--brand-soft` as the same idea at a different percentage; the fallback form means the day it
  // lands the duplicate stops existing, without an edit here and without the site's callouts sitting
  // at a different tint from the app's selected rows.
  const css = readFileSync(path.join(SRC, "styles.css"), "utf8");
  assert.match(css, /--brand-dimm:\s*var\(--brand-soft,/);
});
