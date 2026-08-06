// Exit tests for the Task 10 UX honesty bundle (docs/frontend/LIMITATIONS.md rows A-UX-1 and
// A-UX-5). Both rows are about the gap between what the app *has* and what it *says*, and both
// regress silently: a screen that stops marking stale figures still renders, and a command that
// loses its last caller still compiles. Nothing in `tsc` or `cargo` catches either, so the checks
// live here, over the sources themselves.
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { frontendRoots, readCrate } from "./layout.mjs";

const read = (path) => readFileSync(new URL(path, import.meta.url), "utf8");

/**
 * Every .ts/.tsx file of the desktop frontend, joined — the whole surface that can call a command.
 *
 * The roots come from `layout.properties` rather than from `../src`, because that surface stopped
 * being one directory when the platform seam moved into `library/ts/nodera-ui`. A walker pointed at
 * a directory the code has left keeps passing over an ever-smaller set: "every registered command
 * has a caller" would go green because it stopped being able to see the callers.
 */
function frontendSources() {
  const out = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const child = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(child);
      else if (/\.tsx?$/.test(entry.name)) out.push(readFileSync(child, "utf8"));
    }
  };
  for (const root of frontendRoots()) walk(root);
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
  // screen actually shows the badge that declaration produces, and that the declaration lives in
  // the Rust rather than only in this UI.
  //
  // Found by its KEY, not by the syntax of the enforcement table. This assertion used to read
  // `/Enforcement::Never \{\s*reason: "desktop notifications/` and broke the day the table was
  // collapsed behind `never(...)` constructors — while the declaration it checks for still existed
  // and still said the same thing. The key is wire contract (the worker pins the same strings); how
  // a row spells its variant is not, so the test is written against the part that cannot move.
  const table = readCrate("nodera-core", "src/settings.rs");
  const keyAt = table.indexOf('"appearance.notifications"');
  assert.notEqual(keyAt, -1, "the enforcement table must declare appearance.notifications");
  const row = table.slice(keyAt, keyAt + 400);
  assert.match(
    row,
    /\bnever\b|\bEnforcement::Never\b/,
    "appearance.notifications must be declared never-enforced, however that is spelled",
  );
  assert.match(
    row,
    /desktop notifications are not wired up in this build/,
    "the declaration must carry the reason the UI shows in its badge",
  );
});

/* ------------------------------------------------------------------------------------ A-UX-3 */

test("the restart banner only appears for a change that has actually happened", () => {
  const settings = read("../src/Settings.tsx");

  // The shipped bug: the banner read `config.restart_required`, which is the WORKER naming every
  // bind-time key it was pushed — a description of the key's scope, not of a change. The app pushes
  // `network.port_range` and `network.rendezvous_endpoints` on every save, so the list was never
  // empty, "some changes apply when the peer worker restarts" was permanently true, and restarting
  // could not clear it because it was never about a restart.
  assert.doesNotMatch(
    code(settings),
    /const restartNeeded =[^\n]*config\.restart_required/,
    "the banner is back on the worker's scope list, which is true from first launch to uninstall",
  );
  assert.match(
    code(settings),
    /const pendingKeys = ownership\?\.pending_known \?/,
    "the banner must read the app's own comparison against the running worker's environment",
  );

  const banner = settings.slice(
    settings.indexOf("{restartNeeded && ("),
    settings.indexOf("{section === \"appearance\""),
  );
  assert.ok(banner.length > 0, "the restart banner is gone");
  // It names what it is about. "Some changes" told the reader neither which nor whether it mattered.
  assert.match(banner, /pendingKeys\.join/, "the banner does not name the settings it is about");
  assert.match(banner, /restartNow/, "the banner cannot skip the settle window");
});

test("the peer worker can be restarted at any time, and a refusal is explained", () => {
  const settings = read("../src/Settings.tsx");
  const card = settings.slice(settings.indexOf("function WorkerCard("));
  assert.ok(card.length > 0, "the always-available Restart control is gone");

  // Reachable from the Network section itself, not only from a banner. It used to live inside the
  // banner, so it existed only while the app believed a setting was pending — and a player whose
  // node has gone quiet wants to restart it whether or not a setting changed.
  assert.match(
    code(settings),
    /<WorkerCard[\s\S]{0,200}onRestart=\{restartNow\}/,
    "the Network section does not mount the Peer worker card",
  );
  assert.match(card, /restarting \? "Restarting…" : "Restart peer worker"/);

  // A refusal is the BACKEND's sentence, printed. The screen used to hide the button and assemble
  // its own from `attached`, which is a second copy of a decision `restart_unavailable` makes — and
  // it disagreed with it on Android, where "restart it where you started it" meant nothing
  // (M-NET-3). It may never simply vanish with no explanation.
  assert.match(card, /own\.unavailable_reason/, "a refusal is not explained on screen");
  assert.doesNotMatch(card, /Restart it where you started it/, "the reason is being re-derived here");
  assert.match(
    readCrate("nodera-core", "src/daemon.rs"),
    /pub unavailable_reason: String/,
    "the backend must carry its own refusal to the UI",
  );

  // A-UX-1 again, on a different unknown: where this app did not spawn the worker it cannot know
  // what that worker is running, and an empty pending list must not be drawn as "nothing pending".
  assert.match(card, /!own\.pending_known/, "an unknowable pending set is rendered as empty");
  assert.match(card, /cannot tell which settings it is running/);
});

test("a bind-time setting applies by itself, without waiting to be noticed", () => {
  // The user asked for restart-requiring settings to take effect immediately. A banner is not
  // immediate — it is a chore with a notification attached. The app watches its own spawn record
  // and cycles the worker once the edit settles.
  const daemon = readCrate("nodera-core", "src/daemon.rs");
  assert.match(daemon, /pub async fn apply_bind_time_changes\(/);
  assert.match(daemon, /const SETTLE_INTERVAL: Duration/);
  // And it is actually started. This whole lane is dead code without the spawn, which is this
  // repository's most common defect shape.
  assert.match(
    lib,
    /daemon::apply_bind_time_changes\(/,
    "nothing spawns the task, so bind-time settings apply only when something else restarts the worker",
  );
  // The supervisor has to record what it spawned with, or the comparison has nothing to compare to.
  assert.match(daemon, /launched\.record\(&env\)/);
  assert.match(daemon, /launched\.forget\(\)/);
});

/* ------------------------------------------------------------- the strip may not hide a section */

test("the narrow section strip scrolls rather than losing its last sections", () => {
  // Comments stripped first, and not as tidiness: the prose above the strip explains what `min-w-0`
  // buys, so a version of this test that read the raw source passed with the class deleted. `code()`
  // is declared below and hoisted; it exists for exactly this.
  const settings = code(read("../src/Settings.tsx"));
  const strip = settings.slice(settings.indexOf('role="tablist"') - 500, settings.indexOf('role="tablist"'));

  // Ten sections do not fit across a 1000px window, and there are exactly three things a strip can
  // do about that: wrap, scroll, or lie. It was doing the third — `min-w-0` was absent, so the strip
  // sized itself to its own content, the shell it sits in was pushed past the window's right edge,
  // and `body { overflow: hidden }` took Diagnostics, Privacy and About off the screen with no way
  // to reach them. Both halves are load-bearing: the minimum is what lets the strip be narrower than
  // its tabs, and the overflow is what turns that into a scroll instead of a clip.
  assert.match(strip, /min-w-0/, "the strip cannot be narrower than its tabs, so it clips them");
  assert.match(strip, /overflow-x-auto/, "the strip does not scroll");

  // And it says so. A scrollport with no visible scrollbar is indistinguishable from a cut-off one,
  // which is exactly how this was reported; `overflow-x: auto` paints these only when there is
  // something to scroll, so the bar appears precisely when there is more than the window can show.
  assert.match(settings, /::-webkit-scrollbar-thumb\]:bg-line/, "the strip scrolls in silence");

  // Reachability is not the same as visibility. Something else picks the section — `initial` sends
  // a user to the one control they were told to fix — and a strip that opens scrolled to the left
  // has hidden the reason they came.
  assert.match(
    settings,
    /aria-selected="true"[\s\S]{0,120}scrollIntoView/,
    "the section in force is never scrolled back into view",
  );
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
  // The roots come from the manifest for the same reason as `frontendSources()` above: a rule
  // pointed at a directory the components have left is a rule with nothing to check.
  const offenders = [];
  for (const dir of frontendRoots()) {
    for (const name of readdirSync(dir)) {
      if (!name.endsWith(".tsx")) continue;
      const source = code(readFileSync(path.join(dir, name), "utf8"));
      // Only outbound links matter: an `href` to a data: or mailto: is not this rule's business,
      // and neither is an anchor built by the licence table for a package with no URL.
      if (/<a[\s\n][^>]*href=\{?["']?https?:/.test(source) || /<a[\s\n][^>]*target="_blank"/.test(source)) {
        offenders.push(path.join(dir, name));
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
