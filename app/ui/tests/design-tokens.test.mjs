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
import { readCrate } from "./layout.mjs";
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

/**
 * A declaration must be emitted from a **class selector** in the shipped stylesheet.
 *
 * Stronger than "the token is declared": a custom property can exist in `:root` and still be read
 * by nothing, which is a hero that renders as a flat rectangle with no error anywhere. This proves
 * the rule that uses it actually reached `dist`.
 */
function assertBuiltRule(declaration) {
  const position = builtCss.indexOf(declaration);
  assert.notEqual(position, -1, `production CSS omits ${declaration}`);

  const ruleStart = builtCss.lastIndexOf("{", position);
  const previousRule = Math.max(
    builtCss.lastIndexOf("{", ruleStart - 1),
    builtCss.lastIndexOf("}", ruleStart - 1),
  );
  const selector = builtCss.slice(previousRule + 1, ruleStart);
  assert.match(selector, /\./, `${declaration} is not emitted by a class selector`);
}

test("the launcher's own surfaces reach the shipped stylesheet", () => {
  // The hero is the screen. Each of these is a token that, if it silently resolved to nothing,
  // would leave the page looking merely plain rather than broken — which is why a human reviewing
  // a screenshot would not catch it and this does.
  assertBuiltRule("background-image:var(--hero-scrim)");
  assertBuiltRule("background-image:var(--play-fill)");
  // The generated art: the class carries the gradients, the element carries only the hashed hues.
  assertBuiltRule("--wa-h1");
  assertBuiltRule("--wa-a");
});

test("desktop screens share the wide twelve-column obsidian canvas", () => {
  const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
  const settings = readFileSync(new URL("../src/Settings.tsx", import.meta.url), "utf8");
  const worlds = readFileSync(new URL("../src/Worlds.tsx", import.meta.url), "utf8");

  assert.match(css, /--canvas-max:\s*1680px/);
  assert.match(css, /grid-template-columns:\s*repeat\(12, minmax\(0, 1fr\)\)/);
  assert.match(css, /--bg:\s*#080a09/);
  assert.match(css, /--brand-1:\s*#b8ff4a/);
  assert.doesNotMatch(css, /#ff4d8d|#a855f7|#22d3ee/i);
  assert.match(settings, /wide:hidden/);
  assert.match(settings, /hidden pr-6 wide:block/);
  assert.match(settings, /wide:grid-cols-2/);
  assert.match(worlds, /<PageGrid/);
});

test("desktop typefaces are bundled and separated by role", () => {
  const entry = readFileSync(new URL("../src/main.tsx", import.meta.url), "utf8");
  const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
  assert.match(entry, /@fontsource-variable\/inter/);
  assert.match(entry, /@fontsource-variable\/space-grotesk/);
  assert.match(css, /--font-ui:\s*"Inter Variable"/);
  assert.match(css, /--font-display:\s*"Space Grotesk Variable"/);
});

test("every launch phase and remedy has a word on the Play screen", () => {
  // A `LaunchPhase` with no label renders an unlabelled spinner, and a `Remedy` with no label
  // renders a button with no text — both are the "a number with no provenance" failure in another
  // shape. The Rust enums are the source of truth, so they are read rather than restated.
  const rust = readCrate("nodera-core", "src/launch/mod.rs");
  const play = readFileSync(new URL("../src/PlayScreen.tsx", import.meta.url), "utf8");

  const variants = (enumName) => {
    const start = rust.indexOf(`pub enum ${enumName} {`);
    assert.notEqual(start, -1, `${enumName} is gone from launch/mod.rs`);
    const body = rust.slice(start, rust.indexOf("\n}", start));
    return [...body.matchAll(/^    ([A-Z][A-Za-z]*),$/gm)].map((m) => m[1]);
  };

  const kebab = (name) => name.replace(/([a-z])([A-Z])/g, "$1-$2").toLowerCase();

  const phases = variants("Phase");
  assert.ok(phases.length >= 6, `only found ${phases.length} phases`);
  for (const phase of phases) {
    assert.match(
      play,
      new RegExp(`\\b${phase.toLowerCase()}:`),
      `LaunchPhase::${phase} has no word on the Play screen`,
    );
  }

  for (const remedy of variants("Remedy")) {
    const key = kebab(remedy);
    assert.ok(
      play.includes(`"${key}"`) || play.includes(`${key}:`),
      `Remedy::${remedy} has no button on the Play screen`,
    );
  }
});

test("the launcher never promises an auto-connect it cannot deliver", () => {
  const play = readFileSync(new URL("../src/PlayScreen.tsx", import.meta.url), "utf8");
  const lane = readFileSync(new URL("../src/play.ts", import.meta.url), "utf8");

  // Two tiers land the player in the world and two do not, and the screen says which before the
  // button is pressed. Deriving that from the tier — rather than assuming — is what stops the
  // `servers.dat` route from claiming it will open the world when it only adds it to a list.
  assert.match(lane, /export function autoConnects/, "the tier distinction is gone");
  assert.match(
    lane,
    /tier === "prism" \|\| tier === "direct"/,
    "autoConnects must name exactly the two quick-play tiers",
  );
  assert.match(play, /autoConnects\(/, "the Play screen does not consult it");
  assert.match(
    play,
    /Multiplayer list/,
    "the non-auto-connect wording must say what actually happens",
  );
});
