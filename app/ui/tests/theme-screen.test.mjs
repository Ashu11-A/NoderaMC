// The Theme screen lets a user inject authored CSS into the app's own document.
//
// Everything asserted here is a property that cannot be reviewed once and forgotten: a rewriter
// that quietly becomes a regex filter still passes every visual check, a guard layer that stops
// being last still renders, and a save that moves out of the keep handler still saves. All three
// end at the same place — a window a person cannot use and cannot restart out of.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (name) => readFileSync(new URL(`../src/${name}`, import.meta.url), "utf8");

test("user CSS is rewritten by the platform parser, never filtered by a regex", () => {
  const rewriter = read("themecss.ts");

  // The parse is the browser's own. A regex over CSS text is walked past with comments, escapes and
  // `\\0` padding — all of which the tokeniser normalises away before any hand-written filter sees
  // them — so the rules are read as a tree and written back out, never passed through as text.
  assert.match(rewriter, /\.cssRules/, "the rewriter does not read a parsed rule list");
  assert.match(rewriter, /selectorText/, "selectors are not being read from the parse");
  assert.doesNotMatch(rewriter, /\.replace\(\/</, "this is a filter, not a rewriter");
  assert.doesNotMatch(rewriter, /innerHTML/, "the rewriter touches innerHTML");

  assert.match(rewriter, /const AT_ALLOWED/, "the at-rule allowlist is gone");
  assert.match(rewriter, /const PROPERTY_DENIED/, "the property denylist is gone");
  const denied = rewriter.slice(rewriter.indexOf("const PROPERTY_DENIED"));
  for (const vector of ["behavior", "-moz-binding", "expression("]) {
    assert.ok(denied.slice(0, 300).includes(vector), `${vector} is no longer refused`);
  }

  // Dropped by rule *class*, not by matching their names in text: both are fetches, and a blocked
  // request still tells a remote host that this user opened this theme.
  assert.match(rewriter, /CSSImportRule/);
  assert.match(rewriter, /CSSFontFaceRule/);
});

test("the guard layer is authored by the app, ships in the stylesheet, and is last", () => {
  const css = read("styles.css");
  const rewriter = read("themecss.ts");

  assert.match(
    css,
    /@layer nodera\.base, nodera\.user, nodera\.guard;/,
    "the layer order is not declared, so nothing guarantees the guard is last",
  );

  const guard = css.slice(css.indexOf("@layer nodera.guard {"));
  assert.ok(guard.length > 0, "the guard block is gone");
  assert.match(guard, /\[data-nodera-escape\]/, "the escape hatch is not pinned by the guard");
  assert.match(guard, /\[data-nodera-truth\]/, "proven facts are not pinned by the guard");

  // This, not the at-rule allowlist, is what makes the guard unbypassable: everything a user writes
  // becomes a sub-layer of `nodera.user`, so an `@layer nodera.guard` of their own still precedes
  // the real one, and nothing they write reaches the unlayered author origin.
  assert.match(rewriter, /@layer nodera\.user \{/, "user CSS is not wrapped in its own layer");
});

test("the escape hatch cannot be styled away", () => {
  const app = read("App.tsx");
  const custom = read("customtheme.ts");

  assert.match(app, /data-nodera-escape/, "the shell renders no escape hatch");
  // Rendered on every path. The hatch is not inside a `&&` branch, because a branch is one more
  // thing that has to still be true when the window is already broken.
  const before = app.slice(Math.max(0, app.indexOf("data-nodera-escape") - 400), app.indexOf("data-nodera-escape"));
  assert.doesNotMatch(before, /&&/, "the escape hatch is rendered conditionally");
  assert.match(app, /createPortal/, "the hatch must be a sibling of #root, not a descendant");

  // The belt. An important declaration in a `style` attribute outranks every important author rule
  // in every layer — including an engine that does not implement `@layer` at all, which drops the
  // guard block whole and therefore fails *open*.
  assert.match(
    custom,
    /setProperty\([^)]*"important"\)/,
    "the hatch's own styles are not set at a priority that survives a hostile theme",
  );
  assert.match(app, /Reset appearance/, "there is no way back");
});

test("a theme is not persisted until it is kept", () => {
  const screen = read("Theme.tsx");

  assert.match(screen, /Keep this theme/, "nothing asks for confirmation");
  assert.match(screen, /Reverting in/, "an unconfirmed theme is never taken back");

  // One call site, reachable from one place. A theme that made the window unusable must not be able
  // to survive a restart, and the only way to guarantee that is for the write to happen where the
  // user said the window still works.
  const calls = (screen.match(/saveSettings\(/g) ?? []).length;
  assert.equal(calls, 1, `saveSettings is called ${calls} times in Theme.tsx; it may be called once`);
  const keep = screen.indexOf("onKeep");
  const save = screen.indexOf("saveSettings(");
  assert.ok(keep >= 0, "there is no keep handler");
  assert.ok(
    Math.abs(save - keep) < 400,
    "the one save is not inside the keep handler",
  );
});

test("a custom theme is a patch on a base scheme, and it says where it does not apply", () => {
  const custom = read("customtheme.ts");
  const rewriter = read("themecss.ts");
  const screen = read("Theme.tsx");

  assert.match(custom, /dataset\.customTheme/, "the custom theme writes no scoping attribute");
  // `dataset.theme` keeps exactly one writer, and it is `theme.ts`. Two writers of that attribute is
  // the defect the shell's own comment records: a stale copy overwrote an explicit choice.
  assert.doesNotMatch(
    custom.replace(/\/\/.*$/gm, ""),
    /dataset\.theme\s*=/,
    "a second writer of dataset.theme is back",
  );

  // The scope is what makes deselecting a theme a complete uninstall: no rule it owns can match
  // anything once the attribute is gone.
  assert.match(rewriter, /\[data-custom-theme=/, "user CSS is not scoped to its own theme");

  assert.match(
    screen,
    /Custom themes apply to this desktop app only/,
    "the screen does not say where this preference stops applying",
  );
});
