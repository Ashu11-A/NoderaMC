// Exit tests for the Task 10 app UX honesty bundle (docs/app/LIMITATIONS.md rows A-UX-1 and
// A-UX-5). Both rows are about the gap between what the app *has* and what it *says*, and both
// regress silently: a screen that stops marking stale figures still renders, and a command that
// loses its last caller still compiles. Nothing in `tsc` or `cargo` catches either, so the checks
// live here, over the sources themselves.
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";

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
const lib = read("../../src/lib.rs");
const commands = read("../../src/api/commands.rs");

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
  // figures, so no such screen can be added without a decision about staleness.
  const app = read("../src/App.tsx");
  assert.match(app, /isStale\(d\.link\)/, "the desktop shell does not consult isStale");
  assert.match(app, /<StaleDataNotice \/>/, "the desktop shell never renders the notice");
  for (const screen of ["overview", "worlds", "world", "peers"]) {
    assert.match(
      app,
      new RegExp(`screen\\.name === "${screen}"`),
      `the ${screen} screen is not covered by the stale-figures decision`,
    );
  }

  // Mobile: the same statement, in the phone shell's own words.
  const mobile = read("../src/mobile/MobileApp.tsx");
  assert.equal(
    (mobile.match(/isStale\(/g) ?? []).length,
    2,
    "both figure-bearing phone tabs must mark stale data",
  );
  assert.match(mobile, /function StaleNotice\(\)/);

  // And the words are the same on both, because it is the same claim.
  const words = "Showing the last known picture";
  assert.ok(read("../src/components.tsx").includes(words));
  assert.ok(mobile.includes(words));
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
  assert.match(read("../../src/settings.rs"), /Enforcement::Never \{\s*reason: "desktop notifications/);
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
    .filter(Boolean)
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

  // Kept, because the answer is worth showing — so each must be shown somewhere.
  assert.match(read("../src/Overview.tsx"), /fetchPauseReason\(\)/, "pause_reason has no caller");
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
