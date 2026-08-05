// The navigation is a rail, and it tells the truth about the node it belongs to.
//
// Two rules live here, and both regress silently. A rail whose active item is only a colour still
// renders — it just stops saying where you are to anyone who cannot separate two hues, and nothing
// in `tsc` or Vite has an opinion about that. And an identity block that prints `0 B/s` when the
// worker has never answered is indistinguishable, in a screenshot, from one that is telling the
// truth: this app has been bitten by exactly that twice.
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";

const read = (name) => readFileSync(new URL(`../src/${name}`, import.meta.url), "utf8");
const assets = new URL("../dist/assets/", import.meta.url);
const builtCss = readdirSync(assets)
  .filter((name) => name.endsWith(".css"))
  .map((name) => readFileSync(new URL(name, assets), "utf8"))
  .join("\n");

/** A declaration must be emitted from a **class selector** in the shipped stylesheet. */
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

test("the navigation is a rail, and the rail is the first thing that uses --bg-rail", () => {
  const app = read("App.tsx");
  const css = read("styles.css");

  assert.match(app, /<Rail/, "the shell does not render the rail");
  assert.doesNotMatch(app, /function TopNav/, "the top bar is back");
  assert.doesNotMatch(css, /--top-nav-height/, "the top bar's height token outlived the top bar");
  assert.match(css, /--rail-w:/);
  assert.match(css, /--rail-item:/);

  // `--bg-rail` was declared in both schemes, mapped into `@theme inline`, and read by nothing at
  // all — a token that had been decorative since the day it was written. This proves it now
  // reaches production CSS from a class, the same property the hero scrim and the play fill have.
  assertBuiltRule("background-color:var(--bg-rail)");
});

test("the current destination is announced and marked more than one way", () => {
  const rail = read("Rail.tsx");

  assert.match(rail, /aria-current=\{[^}]*"page"[^}]*\}/, "the rail never announces the current page");
  // The 3px bar. Named in the source so a later tidy-up has to delete a named thing rather than
  // drop a class from the middle of a string: it is the only signal that survives monochrome.
  assert.match(rail, /const ACTIVE_MARK/, "the active bar is gone");
  const active = rail.slice(rail.indexOf("const active = props.active"));
  assert.match(active.slice(0, 800), /ACTIVE_MARK/, "the bar is declared but not worn");
  assert.match(active.slice(0, 800), /bg-brand-soft/);
  assert.match(active.slice(0, 800), /text-brand-tint/);

  // Below the wide breakpoint the labels are hidden, so every item needs both an accessible name
  // and a tooltip — otherwise the collapsed rail is five unnamed glyphs.
  assert.match(rail, /aria-label=\{/);
  assert.match(rail, /title=\{/);
});

test("the rail says it has not heard, rather than saying zero", () => {
  const rail = read("Rail.tsx");

  assert.match(rail, /d\.link\.has_data/, "the rail never asks whether a picture exists");
  assert.match(rail, /show\(/, "the rail prints figures without the em-dash formatter");
  // The fourth health state. "We have not heard from the worker" and "we heard, and it is broken"
  // are different facts, and one red dot for both is the honest-figures defect in another shape.
  assert.match(rail, /Not heard from yet/, "the rail has no unknown state, only up and down");
});

test("Settings keeps its own navigation", () => {
  const settings = read("Settings.tsx");

  // Duplicated from design-tokens.test.mjs on purpose: this file is where a reviewer looks when
  // they wonder whether the global rail swallowed the Settings sidebar. It did not — ten sections
  // do not belong in a rail beside three destinations.
  assert.match(settings, /wide:hidden/);
  assert.match(settings, /hidden pr-6 wide:block/);
  assert.doesNotMatch(settings, /from "\.\/Rail"/, "Settings imports the shell's rail");
  assert.doesNotMatch(settings, /<Rail/, "Settings renders the shell's rail");
});
