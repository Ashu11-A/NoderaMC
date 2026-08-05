// The launcher's half of the token audit, plus the launcher-specific surfaces it has to prove.
//
// The general rule — "every class token written in the source resolves to a rule in the shipped
// stylesheet" — now lives in `nodera-ui/token-audit`, because there are two frontends that need it
// and the website's likeliest styling failure is that *every* class in it resolves to nothing:
// Tailwind v4 detects its sources relative to the Vite root, and the site imports this application's
// stylesheet across a package boundary. A second copy of the rule would be a second definition of
// what counts as a token, drifting from this one the first time either was edited.
//
// Why the rule exists at all is worth keeping here, because it is a real bug this file caught: a
// class naming a token the theme does not declare — `bg-panel` when the palette calls it
// `--color-surface` — is not an error anywhere. `tsc` does not read class names, Vite does not warn,
// and the element renders with no background. The first-run consent modal shipped that way, on the
// first screen a new user ever sees.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { frontendRoots, readCrate } from "./layout.mjs";
import { auditTokens } from "nodera-ui/token-audit";
import { fileURLToPath } from "node:url";
import test from "node:test";

const assets = fileURLToPath(new URL("../dist/assets/", import.meta.url));

// The source roots come from the manifest for the same reason as the walkers in
// `ux-honesty.test.mjs`: the frontend stopped being one directory when the platform seam moved into
// the shared kit, and a walker pointed at a directory the code has left goes green by finding
// nothing.
const audit = auditTokens({ sourceRoots: frontendRoots(), distAssets: assets });
const builtCss = audit.css;

test("every styling class in the source resolves to a rule in the shipped CSS", () => {
  assert.deepEqual(
    audit.dead,
    [],
    `these classes name something the theme does not declare, so they style nothing:\n  ${audit.dead.join("\n  ")}`,
  );
  // Zero dead tokens out of zero tokens is the shape a wrong source root produces. The count is
  // what tells that green apart from the real one.
  assert.ok(audit.inspected > 100, `only ${audit.inspected} class tokens were inspected`);
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
  assert.match(css, /--bg:\s*#16171b/);
  assert.match(css, /--brand-1:\s*#7d67cb/);
  assert.doesNotMatch(css, /#ff4d8d|#a855f7|#22d3ee/i);
  assert.match(settings, /wide:hidden/);
  // The rule is "a sidebar that only exists at the wide breakpoint", and that is all this pins.
  // It used to read `hidden pr-6 wide:block` — an exact class string including the padding, which
  // makes a spacing tweak in a file this test does not own look like the navigation collapsing.
  assert.match(settings, /hidden[^"]*wide:block/);
  // Settings lays its cards out in more than one column once there is room. Either mechanism
  // satisfies that: the fixed two-up it was written with, or the reflowing `card-grid` wall, which
  // answers a 1920px window with more columns rather than two very wide ones.
  assert.match(settings, /wide:grid-cols-2|card-grid/);
  assert.match(worlds, /<PageGrid/);
});

test("the canvas is a ceiling, and nothing inside it may be wider than the window", () => {
  const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");

  // `min(100%, var(--canvas-max))` and `max-width: var(--canvas-max)` compute to the same number
  // right up until something measures the canvas intrinsically — and then the percentage is
  // indefinite, behaves as `auto`, and the canvas collapses onto its own content. That is the
  // "content sits in a narrow column while the window is wide" half of the report, and it is a
  // one-word difference in the source. A maximum is a maximum.
  const canvas = css.slice(css.indexOf("@utility page-canvas"));
  assert.match(canvas.slice(0, 260), /max-width:\s*var\(--canvas-max\)/);
  assert.doesNotMatch(
    canvas.slice(0, 260),
    /width:\s*min\(/,
    "the canvas is back to a computed width instead of a ceiling",
  );

  // The automatic minimum size, in the shipped CSS rather than in the source. `minmax(0, 1fr)`
  // stops a wide child stretching its *track*; it says nothing about the child, which is still a
  // grid item with `min-width: auto` and still overflows the track it was handed. Both grids have
  // to hand their children a zero minimum or a table, a tab strip or an unbreakable id pushes the
  // page out past the window's right edge, where `body { overflow: hidden }` eats it in silence.
  for (const grid of ["page-grid", "card-grid"]) {
    assert.match(
      builtCss,
      new RegExp(String.raw`\.${grid}\s*>\s*\*\s*\{[^}]*min-width:\s*0`),
      `production CSS lets a ${grid} child refuse to be narrower than its contents`,
    );
  }

  // A wide window has to answer with more columns, not more emptiness — the reference spends
  // horizontal space on card walls three and four across, never on one stretched column.
  assert.match(builtCss, /\.card-grid\{[^}]*repeat\(auto-fit,\s*minmax\(/);
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
