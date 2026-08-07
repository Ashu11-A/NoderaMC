// The Theme screen lets a user inject authored CSS into the app's own document.
//
// Everything asserted here is a property that cannot be reviewed once and forgotten: a rewriter
// that quietly becomes a regex filter still passes every visual check, a guard layer that stops
// being first still renders, and a save that moves out of the keep handler still saves. All three
// end at the same place — a window a person cannot use and cannot restart out of.
//
// # Why half of this file drives a browser
//
// The cascade is the thing under test, and no amount of reading the source tells you who wins it.
// The defect this file was written for measured green under every structural check there was: the
// sheet mounted, the scope attribute was written, the tokens were in it and spelled correctly — and
// the accent a person chose did not change one pixel, because the app's own palette was *unlayered*
// and an unlayered author declaration outranks every layered one whatever its specificity. A grep
// cannot see that. Neither can jsdom, which does not implement `@layer` at all.
//
// So the tests below marked "measured" render the **shipped stylesheet** in a real engine, run the
// **real `customtheme.ts`** against it, and read `getComputedStyle`. The last one goes further and
// renders the arrangement this file used to assert, to show it produces the reported bug — a fix
// nobody has watched fail is a fix nobody has confirmed.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test, { after } from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";

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

test("the guard layer is declared first, and every declaration in it is important", () => {
  const css = read("styles.css");
  const rewriter = read("themecss.ts");

  // **The order is inverted for important declarations.** A later layer wins for normal ones, which
  // is why `nodera.user` is last and a theme can repaint the palette; the *earlier* layer wins for
  // important ones, which is why the guard is first. This statement read
  // `nodera.base, nodera.user, nodera.guard` until it was measured, and in that order a theme
  // carrying `#root { display: none !important }` beat the guard and took the window with it.
  assert.match(
    css,
    /@layer nodera\.guard, nodera\.base, nodera\.user;/,
    "the guard is not the first-declared layer, so an important declaration in a theme outranks it",
  );

  const guard = css.slice(css.indexOf("@layer nodera.guard {"));
  assert.ok(guard.length > 0, "the guard block is gone");
  assert.match(guard, /\[data-nodera-escape\]/, "the escape hatch is not pinned by the guard");
  assert.match(guard, /\[data-nodera-truth\]/, "proven facts are not pinned by the guard");

  // Being first is also what makes the guard the *weakest* layer for normal declarations: one
  // written here would lose to `nodera.base` and silently do nothing. So there must not be one.
  const declarations = guard.replace(/\/\*[\s\S]*?\*\//g, "").match(/[a-z-]+\s*:[^;{}]+;/g) ?? [];
  // The `?? []` is what makes the count necessary: the guard layer DOES carry declarations — that is
  // how it pins `[data-nodera-escape]` — so a match of nothing means the extraction stopped working,
  // not that the layer is clean, and the rule below would report green either way.
  assert.ok(declarations.length > 0, "no declaration was found in the guard layer to check");
  for (const declaration of declarations) {
    assert.match(
      declaration.trim(),
      /!important;$/,
      `\`${declaration.trim()}\` is a normal declaration in the first layer, so it loses to nodera.base`,
    );
  }

  // The app's palette has to be *in* a layer or nothing a theme writes can reach it. This is the
  // defect itself: these declarations were unlayered, which put them above every layer at once.
  assert.match(
    css,
    /@layer nodera\.base \{\s*\n\s*:root \{/,
    "the palette is not inside nodera.base, so a custom theme cannot override a single token",
  );

  // This, not the at-rule allowlist, is what makes the guard unbypassable: everything a user writes
  // becomes a sub-layer of `nodera.user`, so an `@layer nodera.guard` of their own is still below
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
  // A sibling of `#root` rather than a descendant, and rendered *after* it, which is what puts it
  // last in the tab order and therefore one Shift+Tab from the top of the page. The measured test
  // below proves it takes focus; this proves it is somewhere Shift+Tab arrives.
  assert.match(app, /createPortal/, "the hatch must be a sibling of #root, not a descendant");
  assert.match(app, /document\.body/, "the hatch is not portalled to the end of the body");

  // The belt. An important declaration in a `style` attribute outranks every important author rule
  // in every layer — including on an engine that does not implement `@layer` at all, which drops
  // the guard block whole and therefore fails *open*.
  assert.match(
    custom,
    /setProperty\([^)]*"important"\)/,
    "the hatch's own styles are not set at a priority that survives a hostile theme",
  );
  assert.match(app, /Reset appearance/, "there is no way back");
});

test("a theme is not persisted until it is kept", () => {
  const screen = read("ThemeScreen.tsx");

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

  // A custom theme rides `save_settings`. It is a preference, not a subsystem, and the command
  // surface is asserted elsewhere — a new verb here would break that count.
  assert.doesNotMatch(screen, /invoke\(/, "the Theme screen calls a command of its own");
});

test("a custom theme is a patch on a base scheme, and it says where it does not apply", () => {
  const custom = read("customtheme.ts");
  const rewriter = read("themecss.ts");
  const screen = read("ThemeScreen.tsx");

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

  // The hatch withdraws rather than staying announced forever, so the screen has to say how to get
  // it back — a route nobody has read about is not a route. The chord here and the one in
  // `customtheme.ts` have to be the same chord.
  assert.match(screen, /Ctrl/, "the screen never names the chord that recalls the hatch");
  assert.match(custom, /key: "r", alt: true, ctrlOrMeta: true/, "the chord moved and the screen still says Ctrl+Alt+R");
});

// --------------------------------------------------------------------------------- the measurements

// `fileURLToPath`, never `.pathname`: on Windows the latter is `/D:/a/NoderaMC/app/ui/` — a leading
// slash and forward slashes — so `join()` builds `\D:\a\…\dist\assets`, which cannot exist. The
// whole release then falls over behind it, because a Windows installer that does not build leaves
// the release short of an asset, `fetch-release.mjs` fails closed on the incomplete manifest, and
// the site and its container image die on a path separator.
const UI = fileURLToPath(new URL("..", import.meta.url));
const CANDIDATES = ["google-chrome", "google-chrome-stable", "chromium", "chromium-browser"];

/** The browser to measure in, or "" — an environment with none still runs every test below. */
const browser = (() => {
  for (const name of [process.env.CHROME, process.env.CHROME_BIN, ...CANDIDATES].filter(Boolean)) {
    try {
      execFileSync(name, ["--version"], { stdio: "ignore" });
      return name;
    } catch {
      /* next */
    }
  }
  return "";
})();

/** The stylesheet the app actually ships, which is not the one in `src`. */
const builtCss = (() => {
  const assets = join(UI, "dist", "assets");
  assert.ok(existsSync(assets), "dist/ is missing: these tests run after `vite build`, never alone");
  const file = readdirSync(assets).find((name) => name.endsWith(".css"));
  assert.ok(file, "the build produced no stylesheet");
  return readFileSync(join(assets, file), "utf8");
})();

/**
 * The layer order as it survives the build, which is the only order that matters.
 *
 * Tailwind deletes the `@layer a, b, c;` statement and re-emits the blocks in the order it
 * established, so the source statement is a *declaration of intent* that the compiler is free to
 * express differently. Read the built sheet, not the one that was written.
 */
function builtLayerOrder() {
  return [...builtCss.matchAll(/@layer\s+(nodera\.[a-z]+)\s*[{;]/g)].map((m) => m[1]);
}

const temp = mkdtempSync(join(tmpdir(), "nodera-theme-"));
after(() => rmSync(temp, { recursive: true, force: true }));

/** Render a page, run its script, and return whatever it wrote into `#measured`. */
function measure(name, html) {
  const file = join(temp, `${name}.html`);
  writeFileSync(file, html);
  const dom = execFileSync(
    browser,
    [
      "--headless=new",
      "--disable-gpu",
      "--no-sandbox",
      "--hide-scrollbars",
      "--window-size=1200,800",
      "--virtual-time-budget=5000",
      "--dump-dom",
      // Same reason as `UI` above, read the other way: `file://D:\temp\x.html` is not a URL. The
      // browser is only reached where one is installed, so this line would have gone on being wrong
      // in silence until somebody put Chrome on a Windows runner.
      pathToFileURL(file).href,
    ],
    { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"], maxBuffer: 64 * 1024 * 1024 },
  );
  const found = /<pre id="measured">([\s\S]*?)<\/pre>/.exec(dom);
  assert.ok(found, `${name}: the page never wrote a measurement`);
  const text = found[1].replace(/&(lt|gt|quot|#39|amp);/g, (_, e) =>
    ({ lt: "<", gt: ">", quot: '"', "#39": "'", amp: "&" })[e],
  );
  return JSON.parse(text);
}

/**
 * The real `customtheme.ts`, bundled for a browser.
 *
 * Bundled rather than re-implemented because the wrapper it writes around a theme is the thing on
 * trial: a test that built the `@layer nodera.user { … }` string itself would be asserting against
 * its own copy of the bug. `react` is aliased to a stub — the module imports `useEffect` for the
 * hook nothing here calls, and pulling React in would be 300kB to no purpose.
 */
async function bundleCustomTheme() {
  writeFileSync(join(temp, "react-stub.js"), "export const useEffect = () => {};\n");
  const { build } = await import("vite");
  await build({
    configFile: false,
    logLevel: "silent",
    root: UI,
    resolve: { alias: { react: join(temp, "react-stub.js") } },
    build: {
      outDir: temp,
      emptyOutDir: false,
      minify: false,
      target: "es2022",
      lib: {
        entry: join(UI, "src", "customtheme.ts"),
        formats: ["iife"],
        name: "NoderaTheme",
        fileName: () => "customtheme.js",
      },
    },
  });
  return readFileSync(join(temp, "customtheme.js"), "utf8");
}

/**
 * What the page does, once, in order — every later reading depends on the state the earlier ones
 * left behind, so this is one script and one render rather than eight.
 *
 * `window.setTimeout` is replaced before the first apply so the twenty-second countdown can be both
 * *read* and *fired*. Waiting it out would put twenty seconds into every build; asserting on the
 * constant in the source would not prove the timer was ever started with it.
 */
const DRIVER = `
const out = {};
const cs = (n) => getComputedStyle(n);
const el = document.getElementById("probe");
const root = document.getElementById("root");
const hatch = document.getElementById("nodera-escape");
const state = (n) => {
  const box = n.getBoundingClientRect();
  const centre = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
  return {
    w: Math.round(box.width), h: Math.round(box.height),
    opacity: cs(n).opacity, position: cs(n).position, visibility: cs(n).visibility,
    pointerEvents: cs(n).pointerEvents, z: cs(n).zIndex,
    hittable: centre === n, name: n.textContent.trim(),
  };
};

out.beforeAccent = cs(el).backgroundColor;

let pending = null;
window.setTimeout = (fn, ms) => { pending = { fn, ms }; return 1; };
window.clearTimeout = () => {};

NoderaTheme.applyCustomTheme({ id: "amber", base: "dark", tokens: { "--brand-1": "#e5a50a" }, css: "" });
out.afterAccent = cs(el).backgroundColor;
out.afterVariable = cs(document.documentElement).getPropertyValue("--brand-1").trim();
out.scopeAttribute = document.documentElement.dataset.customTheme;
out.announceMs = pending && pending.ms;
out.announced = state(hatch);

pending.fn();
out.withdrawn = state(hatch);

document.dispatchEvent(new KeyboardEvent("keydown", { key: "r", ctrlKey: true, altKey: true, bubbles: true }));
out.recalled = state(hatch);
out.focusedByChord = document.activeElement === hatch;
out.tabIndex = hatch.tabIndex;

NoderaTheme.applyCustomTheme({
  id: "hostile", base: "dark", tokens: {},
  css: "#root { display: none !important } * { visibility: hidden !important; pointer-events: none !important; opacity: 0 !important }",
});
out.hostileRootDisplay = cs(root).display;
out.hostileHatch = state(hatch);

NoderaTheme.applyCustomTheme(undefined);
out.disarmedAccent = cs(el).backgroundColor;
out.disarmedInline = hatch.getAttribute("style");
out.disarmedSheet = document.getElementById("nodera-theme");

document.getElementById("measured").textContent = JSON.stringify(out);
`;

/** One render of the shipped stylesheet under the real module; both measured tests read it. */
let shipped;
async function shippedReadings() {
  if (!shipped) {
    const module_ = await bundleCustomTheme();
    shipped = measure(
      "shipped",
      `<!doctype html><html data-theme="dark"><head><meta charset="utf-8"><style>${builtCss}</style></head>` +
        `<body><div id="root"><div id="probe" class="bg-brand-1">accent</div></div>` +
        `<button id="nodera-escape" data-nodera-escape>Reset appearance</button>` +
        `<pre id="measured"></pre><script>${module_}<\/script><script>${DRIVER}<\/script></body></html>`,
    );
  }
  return shipped;
}

test("measured: the accent a person chooses is the colour the window paints", async () => {
  // `bg-brand-1` has to compile to `var(--brand-1)` rather than to a baked hex, or the token is
  // decorative and nothing the Theme screen writes could ever reach it. `@theme inline` is what
  // makes that true, and it is cheap to check on the built sheet before trusting the reading below.
  assert.match(
    builtCss,
    /\.bg-brand-1\{background-color:var\(--brand-1\)\}/,
    "the accent utility no longer reads the accent variable at runtime",
  );

  if (!browser) {
    console.warn(
      `no browser found (tried ${CANDIDATES.join(", ")}); asserting the built layer order instead ` +
        "of measuring the paint — set CHROME to get the real reading",
    );
    const order = builtLayerOrder();
    assert.ok(
      order.indexOf("nodera.base") >= 0 && order.indexOf("nodera.base") < order.indexOf("nodera.user"),
      `the palette layer does not precede the user layer in the built sheet (${order.join(" ")})`,
    );
    return;
  }

  const m = await shippedReadings();
  assert.equal(m.beforeAccent, "rgb(125, 103, 203)", "the app is not painting its own accent");
  // The whole defect, in one line: this reported the app's violet with every structural check green.
  assert.equal(
    m.afterAccent,
    "rgb(229, 165, 10)",
    "an element that paints with the accent token does not paint the chosen accent",
  );
  assert.equal(m.afterVariable, "#e5a50a", "the variable resolves to something other than the choice");
  assert.equal(m.scopeAttribute, "amber", "the scoping attribute was not written");

  // Deselecting is a complete uninstall: the element goes back to the app's own accent and the
  // managed style element is gone, not merely emptied.
  assert.equal(m.disarmedAccent, "rgb(125, 103, 203)", "removing the theme did not repaint");
  assert.equal(m.disarmedSheet, null, "the managed style element outlived the theme");
});

test("measured: the escape hatch withdraws, stays reachable, and survives a hostile theme", async () => {
  if (!browser) {
    console.warn("no browser found; the escape hatch's behaviour was not measured this run");
    const order = builtLayerOrder();
    assert.ok(
      order.indexOf("nodera.guard") === 0,
      `the guard is not the first nodera layer in the built sheet (${order.join(" ")})`,
    );
    return;
  }

  const m = await shippedReadings();

  // The complaint this shape answers: "that reset button stays on the screen forever unless I click
  // it, and then it disappears." It now does neither — it withdraws on its own, and it never leaves.
  assert.equal(m.announceMs, 20_000, "the hatch is not on a twenty-second countdown");
  assert.ok(m.announced.w > 100, `the announced hatch is ${m.announced.w}px wide; it must read as a button`);
  assert.ok(m.announced.h >= 44, "the announced hatch is under the 44px hit floor");
  assert.equal(m.withdrawn.w, 14, `the hatch withdrew to ${m.withdrawn.w}px; the handle is 14px`);
  assert.equal(m.withdrawn.visibility, "visible", "the withdrawn hatch is not on screen");
  assert.equal(m.withdrawn.pointerEvents, "auto", "the withdrawn hatch cannot be clicked");
  assert.ok(m.withdrawn.hittable, "the withdrawn handle is not the element at its own centre");
  assert.ok(Number(m.withdrawn.opacity) > 0, "the withdrawn hatch is invisible, which is dismissal");
  assert.equal(m.withdrawn.name, "Reset appearance", "the handle lost its accessible name");

  // Route one back: the chord. It announces and focuses, and deliberately does not reset — a global
  // key that threw away an hour of work would be its own kind of damage.
  assert.ok(m.recalled.w > 100, "Ctrl+Alt+R did not bring the labelled button back");
  assert.ok(m.focusedByChord, "Ctrl+Alt+R did not put the hatch under the keyboard");
  // Route two: Shift+Tab from the top. Reaching it needs the hatch to be focusable and last in the
  // document; this measures the first half, and the structural test above pins the second.
  assert.ok(m.tabIndex >= 0, "the hatch is not in the tab order");

  // Route three, and the one that matters when the other two are being fought: nothing a theme says
  // can take the window or the hatch away. Under `* { visibility: hidden !important }` and
  // `#root { display: none !important }`, both are still here and the hatch is still clickable.
  assert.equal(m.hostileRootDisplay, "flex", "a theme hid the whole app; the guard lost");
  assert.equal(m.hostileHatch.visibility, "visible", "a theme hid the escape hatch");
  assert.equal(m.hostileHatch.pointerEvents, "auto", "a theme made the escape hatch unclickable");
  assert.ok(m.hostileHatch.hittable, "a theme covered the escape hatch");
  assert.equal(m.hostileHatch.opacity, "1", "a theme faded the escape hatch out");

  // And it leaves nothing behind. Every property it sets is removed by name on disarm, so a window
  // with no custom appearance carries no inline armour at all.
  assert.equal(m.disarmedInline, "", `the hatch kept inline styles after disarming: ${m.disarmedInline}`);
});

test("measured: the layer order is the whole mechanism, and the old one reproduces the bug", () => {
  const order = builtLayerOrder();
  assert.deepEqual(
    order.slice(0, 3),
    ["nodera.guard", "nodera.base", "nodera.user"],
    `the built sheet establishes the layers as ${order.join(", ")}`,
  );

  if (!browser) {
    console.warn("no browser found; the cascade was not evaluated, only the built order read");
    return;
  }

  // Two documents that differ in exactly two lines: where the layers are declared, and whether the
  // palette sits in one. Everything else — the theme, the guard, the utility — is identical, so
  // whatever differs in the readings is caused by the layering and by nothing else.
  const scope = ':root[data-theme="dark"][data-custom-theme="t"]';
  const theme =
    `@layer nodera.user {\n${scope} { --brand-1: #e5a50a; }\n` +
    `${scope} #root { display: none !important; }\n}`;
  const page = (statement, palette) =>
    `<!doctype html><html data-theme="dark" data-custom-theme="t"><head><meta charset="utf-8"><style>
${statement}
${palette}
@layer nodera.guard { #root { display: flex !important; } }
.bg-brand-1 { background-color: var(--brand-1); }
</style><style>${theme}</style></head><body>
<div id="root"><div id="probe" class="bg-brand-1">accent</div></div><pre id="measured"></pre>
<script>document.getElementById("measured").textContent = JSON.stringify({
  accent: getComputedStyle(document.getElementById("probe")).backgroundColor,
  display: getComputedStyle(document.getElementById("root")).display,
});<\/script></body></html>`;

  const before = measure(
    "before",
    page("@layer nodera.base, nodera.user, nodera.guard;", ":root { --brand-1: #7d67cb; }"),
  );
  const after = measure(
    "after",
    page("@layer nodera.guard, nodera.base, nodera.user;", "@layer nodera.base { :root { --brand-1: #7d67cb; } }"),
  );

  // The reported bug, reproduced: the chosen accent does nothing, and the guard loses to a theme.
  assert.equal(before.accent, "rgb(125, 103, 203)", "the old arrangement no longer reproduces the defect");
  assert.equal(before.display, "none", "the old arrangement no longer loses the guard");
  // And the shipped one, which differs only in those two lines.
  assert.equal(after.accent, "rgb(229, 165, 10)", "the accent does not survive the current layer order");
  assert.equal(after.display, "flex", "the guard does not survive the current layer order");
});

/**
 * WCAG 2.x contrast, implemented here rather than imported.
 *
 * The screen has its own copy and it is the thing being checked, so the test needs a yardstick that
 * cannot agree with it by sharing its arithmetic.
 */
function ratio(front, back) {
  const channels = (colour) =>
    colour
      .replace(/^rgba?\(|\)$/g, "")
      .split(/[,/\s]+/)
      .filter(Boolean)
      .slice(0, 3)
      .map(Number);
  const luminance = (colour) => {
    const [r, g, b] = channels(colour).map((v) => {
      const c = v / 255;
      return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const [light, dark] = [luminance(front), luminance(back)].sort((a, b) => b - a);
  return (light + 0.05) / (dark + 0.05);
}

test("measured: a palette that collapses to one colour cannot silence the report that says so", async () => {
  assert.match(
    builtCss,
    /\.bg-surface\{background-color:var\(--surface\)\}/,
    "the surface utility no longer reads the surface variable, so this measures nothing",
  );

  // Comments stripped first: the block explains at length why `revert-layer` was removed, and the
  // explanation contains the words it is asserting are gone.
  const sheet = read("styles.css").replace(/\/\*[\s\S]*?\*\//g, "");
  const guard = sheet.slice(sheet.indexOf("@layer nodera.guard {"));
  // Measured out rather than reasoned about: `revert-layer` in the *first* author layer rolls back
  // to the UA origin, not to the app's own value, so it reset a marked element to `display: inline`
  // and destroyed its layout. It was invisible only because nothing wore the attribute.
  assert.doesNotMatch(
    guard,
    /display: revert-layer/,
    "revert-layer is back in the guard; from the first layer it reverts to the UA default",
  );
  assert.match(
    guard,
    /:root\[data-custom-theme\] \[data-nodera-truth\]/,
    "a proven fact is not pinned to colours a custom appearance cannot name",
  );

  if (!browser) {
    console.warn("no browser found; the collapsed palette was not rendered");
    return;
  }

  // The appearance from the report, rebuilt from the allowlist rather than pasted: every token of
  // kind `colour` set to the one hex, which is exactly the set of rows that render a colour swatch
  // and exactly what the saved document held.
  const tokens = Object.fromEntries(
    [...read("themetokens.ts").matchAll(/name: "(--[a-z0-9-]+)".*?kind: "colour"/g)].map((m) => [
      m[1],
      "#e5a50a",
    ]),
  );
  assert.equal(Object.keys(tokens).length, 24, "the colour allowlist changed size; check the fixture");

  const module_ = await bundleCustomTheme();
  const m = measure(
    "collapsed",
    `<!doctype html><html data-theme="dark"><head><meta charset="utf-8"><style>${builtCss}</style></head>` +
      `<body><div id="root"><div id="card" class="bg-surface">` +
      `<span id="marked" data-nodera-truth class="text-warn">1.00:1 needs 4.5</span>` +
      `<span id="plain" class="text-warn">1.00:1 needs 4.5</span></div></div>` +
      `<button id="nodera-escape" data-nodera-escape>Reset appearance</button>` +
      `<pre id="measured"></pre><script>${module_}<\/script><script>
        NoderaTheme.applyCustomTheme({ id: "collapse", base: "dark", tokens: ${JSON.stringify(tokens)}, css: "" });
        const cs = (id) => getComputedStyle(document.getElementById(id));
        document.getElementById("measured").textContent = JSON.stringify({
          card: cs("card").backgroundColor,
          markedText: cs("marked").color,
          markedFill: cs("marked").webkitTextFillColor,
          markedBack: cs("marked").backgroundColor,
          plainText: cs("plain").color,
        });
      <\/script></body></html>`,
  );

  // The palette really did collapse: the card is the one hex, and an unmarked warning painted with
  // `--warn` is the same hex again. This is what the window in the report was doing.
  assert.equal(m.card, "rgb(229, 165, 10)", "the collapsed palette did not take effect");
  assert.equal(
    Number(ratio(m.plainText, m.card).toFixed(2)),
    1,
    "an unmarked warning is no longer invisible, so this test has stopped reproducing the defect",
  );

  // And the marked one is readable anyway, in colours the theme never named.
  assert.equal(m.markedFill, "rgb(255, 255, 255)", "text-fill-color was not pinned, so it overrides colour");
  assert.ok(
    ratio(m.markedText, m.markedBack) >= 4.5,
    `the proven fact measures ${ratio(m.markedText, m.markedBack).toFixed(2)}:1 against its own background`,
  );
});
