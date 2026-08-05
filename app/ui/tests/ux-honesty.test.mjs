// Exit tests for the Task 10 UX honesty bundle (docs/frontend/LIMITATIONS.md rows A-UX-1 and
// A-UX-5). Both rows are about the gap between what the app *has* and what it *says*, and both
// regress silently: a screen that stops marking stale figures still renders, and a command that
// loses its last caller still compiles. Nothing in `tsc` or `cargo` catches either, so the checks
// live here, over the sources themselves.
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";
import { readCrate } from "./layout.mjs";

const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8");

/** Every .ts/.tsx file under src/, joined — the whole surface that can call a command. */
function frontendSources() {
  const root = new URL("../src/", import.meta.url);
  const out = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const child = new URL(entry.name + (entry.isDirectory() ? "/" : ""), dir);
      if (entry.isDirectory()) walk(child);
      else if (/\.tsx?$/.test(entry.name)) out.push(readFileSync(child, "utf8"));
    }
  };
  walk(root);
  return out.join("\n");
}

const frontend = frontendSources();

/**
 * Every desktop screen the shell can show, and the module that draws it.
 *
 * Spelled once, here, so the two rules below — "a screen that reads the dashboard must be marked
 * for staleness" and "a screen must exist for every destination" — are checked against the same
 * list rather than against two hand-maintained ones.
 */
const DASHBOARD_SCREENS = [
  ["play", "PlayScreen.tsx"],
  ["worlds", "Worlds.tsx"],
  ["world", "World.tsx"],
  ["discover", "Network.tsx"],
];

/** The screen names the shell declares as showing worker figures. */
function coveredScreens(app) {
  const start = app.indexOf("export const WORKER_FIGURE_SCREENS");
  if (start === -1) return [];
  const block = app.slice(start, app.indexOf("];", start));
  const names = [...block.matchAll(/"([a-z]+)"/g)].map((m) => m[1]);
  // The destinations are pulled from the nav table by a filter rather than written as strings, so
  // read those too — otherwise this only ever sees the one name spelled literally.
  const navStart = app.indexOf("const DESTINATIONS");
  const nav = app.slice(navStart, app.indexOf("] as const", navStart));
  for (const [, name, shows] of nav.matchAll(/name: "([a-z]+)"[\s\S]*?showsWorkerFigures: (true|false)/g)) {
    if (shows === "true") names.push(name);
  }
  return names;
}
const lib = read("../../src/lib.rs");
const commands = readCrate("nodera-core", "src/api/commands.rs");

/* ------------------------------------------------------------------------------------ A-UX-1 */

test("a screen showing worker figures marks them as last-known when the link is down", () => {
  const api = read("../src/api.ts");
  // The predicate itself: a picture exists (`has_data`) but is not current. Both halves matter —
  // without `has_data` a never-connected app would claim its em-dashes are stale, and without the
  // status check a live app would claim its live numbers are dated.
  assert.match(api, /export function isStale\(link: Link\): boolean/);
  const body = api.slice(api.indexOf("export function isStale"));
  assert.match(body.slice(0, 200), /link\.has_data/);
  assert.match(body.slice(0, 200), /"live"/);
  assert.match(body.slice(0, 200), /"polling"/);

  // Desktop: the shell renders the notice above whichever screen is showing worker-derived
  // figures. The set used to be four screen names spelled here, which drifted every time a screen
  // moved and said nothing about screens that were added. It is now derived: the shell exports the
  // set, and this asserts that **every screen module which reads the dashboard is in it**.
  const app = read("../src/App.tsx");
  assert.match(app, /isStale\(d\.link\)/, "the desktop shell does not consult isStale");
  assert.match(app, /<StaleDataNotice \/>/, "the desktop shell never renders the notice");
  assert.match(
    app,
    /export const WORKER_FIGURE_SCREENS/,
    "the shell must declare which screens show worker figures",
  );
  assert.match(
    app,
    /WORKER_FIGURE_SCREENS\.includes\(screen\.name\)/,
    "the declared set must be what actually gates the notice",
  );

  const declared = new Set(coveredScreens(app));
  assert.ok(declared.size > 0, "no screen is covered by the stale-figures decision");
  for (const [screen, module] of DASHBOARD_SCREENS) {
    const source = read(`../src/${module}`);
    // Reading any of these means rendering a number the worker supplied, which is exactly what has
    // to be marked as last-known when the link is down.
    if (!/useDashboard|d\.worlds|d\.traffic|d\.node|d\.counts/.test(source)) continue;
    assert.ok(
      declared.has(screen),
      `${module} renders worker figures but ${screen} is not in WORKER_FIGURE_SCREENS`,
    );
  }

  // Android is native Compose; both Home and Worlds render the shared native notice.
  const mobile = read("../../android/kotlin/ui/Screens.kt");
  const mobileMarks = (mobile.match(/StaleNotice\(\)/g) ?? []).length;
  assert.ok(mobileMarks >= 2, `every figure-bearing native screen must mark stale data (found ${mobileMarks})`);
  assert.match(mobile, /fun StaleNotice\(\)/);

  // Both shells state the same fact in platform-appropriate compact wording.
  const words = "Showing the last known picture";
  assert.ok(read("../src/components.tsx").includes(words));
  assert.ok(mobile.includes("Last known state"));
});

/* ------------------------------------------------------------------------------------ A-UX-2 */

test("the notifications toggle is badged with why it is not in force", () => {
  const settings = read("../src/Settings.tsx");
  const toggle = settings.slice(settings.indexOf('label="Notifications"'));
  assert.match(
    toggle.slice(0, 400),
    /note=\{note\("appearance\.notifications"\)\}/,
    "the toggle claims nothing about whether it works",
  );
  // The backing declaration is asserted on the Rust side
  // (`appearance_notifications_is_declared_unenforced_with_a_reason`); this half only proves the
  // screen actually shows the badge that declaration produces.
  assert.match(readCrate("nodera-core", "src/settings.rs"), /Enforcement::Never \{\s*reason: "desktop notifications/);
});

/* ------------------------------------------------------------------------------------ A-UX-3 */

test("the restart banner restarts the worker, or says why it cannot", () => {
  const settings = read("../src/Settings.tsx");
  const banner = settings.slice(
    settings.indexOf("{restartNeeded && ("),
    settings.indexOf("{section === \"appearance\""),
  );
  assert.ok(banner.length > 0, "the restart banner is gone");
  // Owned worker: the app started it, so the app can restart it — one button, no instructions.
  assert.match(banner, /ownership\?\.can_restart \?/);
  assert.match(banner, /restartWorker\(\)/);
  // Attach mode: the process belongs to whoever started it, and the banner says so instead of
  // telling the user to do something the app could have done itself.
  assert.match(banner, /Restart it where you started it\./);
});

/* ------------------------------------------------------------------------------------ A-UX-5 */

/** The command names inside `tauri::generate_handler![...]`, in registration order. */
function registeredCommands() {
  const start = lib.indexOf("generate_handler![");
  assert.notEqual(start, -1, "no generate_handler! in lib.rs");
  const list = lib.slice(start + "generate_handler![".length, lib.indexOf("])", start));
  return list
    .split("\n")
    .map((line) => line.replace(/\/\/.*$/, "").trim().replace(/,$/, ""))
    // `#[cfg(...)]` gates entries in this list — the launch lane is desktop-only, because there is
    // no Java Minecraft client on Android. An attribute is not a command name.
    .filter((line) => line && !line.startsWith("#["))
    .map((name) => name.split("::").pop());
}

test("every registered command has a frontend caller", () => {
  const orphans = registeredCommands().filter(
    (name) => !new RegExp(`invoke<[^>]*>\\(\\s*"${name}"|invoke\\(\\s*"${name}"`).test(frontend),
  );
  assert.deepEqual(
    orphans,
    [],
    `registered but never invoked — give each a caller or delete it: ${orphans.join(", ")}`,
  );
});

test("the six A-UX-5 commands are each resolved, and stay resolved", () => {
  // Deleted outright: no caller existed and none was wanted. Named explicitly so a future paste
  // cannot quietly reintroduce a command with nothing on the other end.
  for (const gone of ["dashboard_world", "open_share_file", "get_unenforced_settings"]) {
    assert.doesNotMatch(lib + commands, new RegExp(`fn ${gone}\\b`), `${gone} is back`);
    assert.doesNotMatch(frontend, new RegExp(`"${gone}"`), `${gone} is invoked again`);
  }

  // Kept, because the answer is worth showing — so each must be shown somewhere. `pause_reason`
  // moved with the Overview screen that was dissolved into the launcher's hero: a node that has
  // quietly stopped sharing looks exactly like one that crashed, and this sentence is the whole
  // difference. It has to keep a caller wherever it lives.
  assert.match(read("../src/PlayScreen.tsx"), /fetchPauseReason\(\)/, "pause_reason has no caller");
  assert.match(
    read("../src/Settings.tsx"),
    /fetchSettingsFault\(\)/,
    "settings_fault has no desktop caller",
  );

  // The `nodera://pause` event was a second channel for a fact the pushed dashboard already
  // carries; the tray now just requests a push. One truth, one path.
  assert.doesNotMatch(lib, /nodera:\/\/pause/, "the orphan pause event is back");
  assert.doesNotMatch(frontend, /nodera:\/\/pause/, "the orphan pause event has a listener again");
});

/**
 * A source file with its comments removed.
 *
 * Every rule below is about what the code DOES, and the prose explaining a rule necessarily quotes
 * the thing the rule forbids. Without this, the comment justifying "no anchors" fails the test
 * enforcing "no anchors".
 */
function code(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
}

test("no link is an anchor the webview would swallow", () => {
  // `<a target="_blank">` opens NOTHING in a Tauri v2 webview — there is no tab for it to open in —
  // and an anchor without it navigates this window away from the application, to a page with no
  // back button because the app has no browser chrome either. Both outcomes look like a bug to the
  // person who tapped. So links go through `openExternal`, and the host decides where they open.
  const dirs = ["../src"];
  const offenders = [];
  for (const dir of dirs) {
    const base = new URL(`${dir}/`, import.meta.url);
    for (const name of readdirSync(base)) {
      if (!name.endsWith(".tsx")) continue;
      const source = code(readFileSync(new URL(name, base), "utf8"));
      // Only outbound links matter: an `href` to a data: or mailto: is not this rule's business,
      // and neither is an anchor built by the licence table for a package with no URL.
      if (/<a[\s\n][^>]*href=\{?["']?https?:/.test(source) || /<a[\s\n][^>]*target="_blank"/.test(source)) {
        offenders.push(`${dir}/${name}`);
      }
    }
  }
  assert.deepEqual(offenders, [], "these render an anchor for a web link; use openExternal");
});

test("the link helper never settles for the clipboard", () => {
  // It used to: when no browser could be opened it copied the address and said so. That is not
  // opening a link — it is telling the user to do it themselves, in an app that is itself a webview
  // and could have shown them the page. The host's ladder ends in a webview on both platforms now,
  // so there is nothing for a clipboard rung to be a fallback *from*.
  const links = code(readFileSync(new URL("../src/links.tsx", import.meta.url), "utf8"));
  assert.doesNotMatch(links, /clipboard/, "a link must open a page, not copy an address");
  assert.doesNotMatch(links, /status: "copied"/);
  // And it is a button, not an anchor: an `<a>` would invite middle-click and copy-link-address,
  // both of which silently do nothing here.
  assert.match(links, /<button/);
  assert.doesNotMatch(links, /<a\s/);
});
