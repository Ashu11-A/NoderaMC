// Every styling class written in the source must actually resolve to a rule in the shipped CSS.
//
// # Why this test exists
//
// Tailwind emits a class only when it resolves. A class name that names a token the theme does not
// declare — `bg-panel` when the palette calls it `--color-surface` — is not an error anywhere: `tsc`
// does not read class names, Vite does not warn, and the element simply renders with no background.
// The first-run consent modal shipped that way: `bg-panel`, `text-brand`, `text-muted` and
// `hover:bg-hover` all resolved to nothing, so the panel was transparent over the scrim, its prose
// rendered at full contrast instead of dimmed, and neither button had a hover state — on the first
// screen a new user ever sees.
//
// The check is the inverse of the bug: take every class token that appears in the source, and assert
// the production stylesheet contains a rule for it. A token Tailwind rejected is a token that is
// missing from `dist`, which is exactly the signature.
//
// # Why it reads the built CSS rather than the theme
//
// Comparing class names against the `@theme` block in `styles.css` would only cover colours, and it
// would re-implement Tailwind's own resolution rules — including the built-in palette, arbitrary
// values and every variant prefix — in this file, where they would drift. The built stylesheet is
// the authority on what resolved, because it *is* what resolved.
import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";

const srcDir = fileURLToPath(new URL("../src/", import.meta.url));
const assets = new URL("../dist/assets/", import.meta.url);
const builtCss = readdirSync(assets)
  .filter((name) => name.endsWith(".css"))
  .map((name) => readFileSync(new URL(name, assets), "utf8"))
  .join("\n");

/** Every `.tsx`/`.ts` under `src/`, recursively — a new folder of screens cannot escape the rule. */
function sources(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) out.push(...sources(full));
    else if (/\.(tsx|ts)$/.test(entry)) out.push(full);
  }
  return out;
}

/**
 * The utility families that carry a design decision.
 *
 * Deliberately not "every class": layout utilities (`flex`, `grid-cols-2`) cannot name a missing
 * token, so including them would add noise without adding coverage. These families all take a value
 * out of the theme, which is where a name can be wrong.
 */
const FAMILIES = [
  "text",
  "bg",
  "border",
  "outline",
  "ring",
  "fill",
  "stroke",
  "from",
  "via",
  "to",
  "shadow",
  "decoration",
  "divide",
  "accent",
  "caret",
  "placeholder",
  "rounded",
];

const TOKEN = new RegExp(
  // optional variant prefixes (`hover:`, `max-narrow:`, `dark:`…), then family-value
  String.raw`^(?:[a-z][a-z0-9-]*:)*(?:${FAMILIES.join("|")})-[a-z0-9[\]()/.,%#_-]+$`,
  "i",
);

/** CSS identifier escaping, the same transformation Tailwind applies when it emits the selector. */
function cssEscape(token) {
  return token.replace(/[^a-zA-Z0-9_-]/g, (ch) => `\\${ch}`);
}

/**
 * Is there a rule for this class in the stylesheet?
 *
 * Matches `.token` only at a class boundary, so `.bg-surface` is not satisfied by `.bg-surface-hover`
 * — the near-miss this test most needs to catch, because a token one hyphen away from a real one is
 * exactly what a rename leaves behind.
 */
function emitted(token) {
  const needle = `.${cssEscape(token)}`;
  let at = builtCss.indexOf(needle);
  while (at !== -1) {
    const next = builtCss[at + needle.length];
    if (next === undefined || !/[a-zA-Z0-9_-]/.test(next)) return true;
    at = builtCss.indexOf(needle, at + 1);
  }
  return false;
}

/**
 * Class tokens written as plain string literals.
 *
 * Literals containing `${` are skipped: an interpolated class is composed at runtime, so the pieces
 * around the hole are not tokens and Tailwind never sees them as such either.
 */
function tokensIn(source) {
  const found = new Set();
  for (const [, literal] of source.matchAll(/"([^"\\\n]*)"|'([^'\\\n]*)'|`([^`\\$]*)`/g)) {
    if (literal === undefined || literal.includes("${")) continue;
    for (const word of literal.split(/\s+/)) {
      if (TOKEN.test(word)) found.add(word);
    }
  }
  return found;
}

test("every styling class in the source resolves to a rule in the shipped CSS", () => {
  const dead = [];
  for (const file of sources(srcDir)) {
    const source = readFileSync(file, "utf8");
    for (const token of tokensIn(source)) {
      if (!emitted(token)) dead.push(`${path.relative(srcDir, file)}: ${token}`);
    }
  }

  assert.deepEqual(
    dead,
    [],
    `these classes name something the theme does not declare, so they style nothing:\n  ${dead.join("\n  ")}`,
  );
});

test("the consent modal's own classes are among them", () => {
  // A regression guard with a name, because this is the screen the general rule was written for and
  // it is the one screen whose failure nobody would see in review: it only renders on first run.
  const consent = readFileSync(new URL("../src/Consent.tsx", import.meta.url), "utf8");
  for (const gone of ["bg-panel", "text-brand", "text-muted", "hover:bg-hover"]) {
    // Whole-token, not substring: `text-brand` is a prefix of the `text-brand-2` that replaced it,
    // and a substring check would report the fix as the bug.
    const written = new RegExp(String.raw`(^|[\s"'\`])${gone}([\s"'\`]|$)`, "m");
    assert.ok(
      !written.test(consent),
      `Consent.tsx still writes ${gone}, which resolves to nothing`,
    );
  }
  assert.ok(consent.includes("bg-surface"), "the modal panel needs a background that exists");
  assert.ok(consent.includes("text-dim"), "the modal's prose is meant to be dimmed");
});
