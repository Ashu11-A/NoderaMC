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

test("the shell fills the window, and only ever scrolls downwards", () => {
  const app = read("App.tsx");
  const css = read("styles.css");

  // The defect this pins is invisible to every other check in this folder, because nothing here
  // was wrong on its own. The guard layer makes `#root` a flex container so a theme cannot
  // un-display the app; that quietly turned the shell into a **flex item**, and a flex item with
  // `width: auto` is shrink-to-fit. The shell therefore sized itself to its own content — narrower
  // than the window on a large display (a column of cards stranded in an empty page), and *wider*
  // than the window whenever its min-content was, at which point `body { overflow: hidden }` cut
  // the right-hand edge off the cards, the badges and the last settings tab with no scrollbar to
  // say anything had been lost.
  const shell = app.slice(app.indexOf("<div className=\"flex h-full"));
  assert.match(
    shell.slice(0, 200),
    /w-full/,
    "the shell does not claim the window's width, so it shrink-wraps onto its own content",
  );
  assert.match(
    shell.slice(0, 200),
    /min-w-0/,
    "the shell cannot go below its own min-content, so a wide child pushes the app off-screen",
  );

  // One axis. `overflow-y: auto` on its own is not one axis: the other axis computes from
  // `visible` to `auto`, and the scrollport answers a too-wide child by scrolling the whole page
  // sideways under the rail. Wide content that cannot get narrower scrolls inside itself instead.
  const scrollport = app.slice(app.indexOf("id={SCROLLPORT_ID}"));
  assert.match(scrollport.slice(0, 400), /overflow-y-auto/, "the scrollport does not scroll");
  assert.match(
    scrollport.slice(0, 400),
    /overflow-x-hidden/,
    "the page is allowed to scroll sideways",
  );
  assert.match(scrollport.slice(0, 400), /min-w-0/, "the scrollport inherits its content's minimum");

  // The other half of the same chain. `#root` is where `height` was already asserted and `width`
  // was not — and a theme that shrink-wraps the app locks the window out exactly as thoroughly as
  // one that hides it, with every way back inside the app it just made unreachable.
  const guard = css.slice(css.indexOf("@layer nodera.guard"));
  assert.match(guard, /width:\s*100%\s*!important/, "the guard pins no width on the app's root");
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
  assert.match(settings, /hidden[^"]*wide:block/);
  assert.doesNotMatch(settings, /from "\.\/Rail"/, "Settings imports the shell's rail");
  assert.doesNotMatch(settings, /<Rail/, "Settings renders the shell's rail");
});
