// The window chrome the app draws for itself, and the four files that have to agree about it.
//
// Every rule here guards a failure that build tools cannot see. A Tauri capability is checked at
// **runtime**: a `core:window:*` permission that is missing compiles, bundles, installs and ships,
// and the first symptom is a person pressing Close on a window that does not close. There is no
// stack trace on screen and — because a chrome button has nowhere to report into — no message
// either. `TitleBar.tsx` swallows those rejections deliberately, which makes this file the only
// place the mistake can be caught at all.
//
// The other half is agreement between spellings. The window label appears in four independent
// files: the window definition, the two capability scopes, and the command payloads the bar sends.
// Three of them can be right while the fourth is wrong, and each disagreement fails differently —
// grants that land on a window nobody opened, or an undecorated window whose deep link goes
// nowhere.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { crate, frontendRoots } from "./layout.mjs";
import { sourceFiles } from "nodera-ui/token-audit";

const APP = crate("nodera-app");
const readJson = (relative) => JSON.parse(readFileSync(path.join(APP, relative), "utf8"));

const config = readJson("tauri.conf.json");
const desktop = readJson("capabilities/desktop.json");
const shared = readJson("capabilities/default.json");
const titleBar = readFileSync(new URL("../src/TitleBar.tsx", import.meta.url), "utf8");

/** The single window this application opens. */
const mainWindow = config.app.windows.find((window) => window.label === "main");

/**
 * Every `plugin:window|<command>` literal written anywhere in the desktop frontend.
 *
 * Read out of the source rather than listed here, because a list would be the fifth copy of the
 * same table and the whole point is that the source is what ships. A button added tomorrow with a
 * new command joins this set by existing.
 */
function windowCommandsInSource() {
  const found = new Set();
  for (const root of frontendRoots()) {
    for (const file of sourceFiles(root, /\.(tsx|ts)$/)) {
      for (const [, command] of readFileSync(file, "utf8").matchAll(/"plugin:window\|([a-z_]+)"/g)) {
        found.add(command);
      }
    }
  }
  return found;
}

/**
 * The permission that admits a command.
 *
 * Tauri's core ACL names one permission per command, mechanically: `start_resize_dragging` is
 * admitted by `core:window:allow-start-resize-dragging`. The transformation is spelled here rather
 * than read from `app/gen/schemas/acl-manifests.json` because that directory is generated and
 * git-ignored — a rule that can only run after a Rust build is a rule that skips in every fresh
 * checkout, and a skip is the one outcome a check like this cannot survive.
 */
const permissionFor = (command) => `core:window:allow-${command.replace(/_/g, "-")}`;

test("every window command the bar sends is granted, and every grant is sent", () => {
  const sent = windowCommandsInSource();
  assert.ok(sent.size >= 7, `only ${sent.size} window commands found; the bar cannot be complete`);

  const granted = desktop.permissions.filter((entry) => entry.startsWith("core:window:"));

  // The direction that fails in front of a user. A command with no grant is a button that does
  // nothing, silently, on a window that has no other way to be closed.
  const ungranted = [...sent]
    .map(permissionFor)
    .filter((permission) => !granted.includes(permission))
    .sort();
  assert.deepEqual(
    ungranted,
    [],
    `the title bar calls commands capabilities/desktop.json does not admit: ${ungranted.join(", ")}`,
  );

  // The inverse, which fails nowhere but matters anyway: a granted window command with no caller is
  // authority handed to the webview that nothing in this application asked for. `core:window:*` is
  // never granted wholesale here — no `core:window:default` — precisely so this list stays readable
  // as "what the chrome does".
  const uncalled = granted.filter((permission) => ![...sent].map(permissionFor).includes(permission));
  assert.deepEqual(uncalled, [], `granted but never called: ${uncalled.join(", ")}`);
});

test("the chrome's grants are desktop-scoped, so the Android build still links", () => {
  // The autostart plugin is not compiled for Android at all, so naming its permission in the shared
  // capability fails the mobile build rather than being ignored there. The window permissions ride
  // in the same file for the same class of reason — `decorations: false` is a statement about a
  // desktop window, and Android draws none.
  assert.deepEqual(desktop.platforms, ["linux", "macOS", "windows"]);
  assert.ok(
    !shared.permissions.some((entry) => entry.startsWith("core:window:")),
    "a window permission moved into the shared capability, where Android would have to honour it",
  );
});

test("one window label, spelled the same in all four places", () => {
  // The window definition, the two capability scopes, and the payload every command carries. The
  // deep-link plugin routes `nodera://…` to a window BY LABEL, and the dashboard's pushes are
  // emitted to it the same way — so a label changed in one file is a link that opens nothing and a
  // set of grants attached to a window that was never created.
  assert.ok(mainWindow, "tauri.conf.json no longer declares a window labelled main");
  assert.deepEqual(desktop.windows, ["main"]);
  assert.deepEqual(shared.windows, ["main"]);
  assert.match(titleBar, /export const WINDOW_LABEL = "main";/);
  assert.match(titleBar, /\{ label: WINDOW_LABEL, \.\.\.args \}/, "commands stopped naming a window");

  // `core:default` is what carries `plugin:event|listen`, and therefore what makes
  // `nodera://dashboard` reach a pixel. Three of the backend's five emits are `let _ = emit(…)`, so
  // a denial here would be silent on both sides: `invoke` would keep working while the interface
  // quietly stopped changing, which reads as a dead node rather than as a permissions mistake.
  assert.ok(shared.permissions.includes("core:default"), "the app can no longer receive its pushes");
  assert.ok(shared.permissions.includes("deep-link:default"), "nodera:// links reach no window");
});

test("the window is undecorated, and the app draws what the decorations used to", () => {
  assert.equal(mainWindow.decorations, false);
  assert.equal(mainWindow.resizable, true);

  const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
  assert.match(app, /<TitleBar \/>/, "the shell renders no chrome for an undecorated window");

  // The three buttons, by accessible name rather than by glyph: below a certain size an icon is the
  // only thing on the control, and an unlabelled one is unreachable to anything that is not a mouse.
  for (const label of ["Minimise", "Close"]) {
    assert.match(titleBar, new RegExp(`aria-label="${label}"`), `no ${label} button`);
  }
  assert.match(titleBar, /aria-label=\{maximized \? "Restore" : "Maximise"\}/);

  // The middle glyph follows the window rather than a remembered click: a tiling rule or a keyboard
  // shortcut can maximise this window without the bar being pressed at all.
  assert.match(titleBar, /plugin:window\|is_maximized/);
  assert.match(titleBar, /window\.addEventListener\("resize", sync\)/);

  // Double-click on the bar still maximises. It has to be recognised on the second `mousedown`,
  // because starting a window drag on the first one means the pair never arrives as a `dblclick`.
  assert.match(titleBar, /event\.detail === 2/);
  assert.match(titleBar, /internalToggleMaximize/);
});

test("an undecorated window keeps a resize frame, on all eight edges", () => {
  // The regression that would be reported as "the window is stuck at one size". A window manager
  // gives an undecorated window no resize border, so the app that removed the decorations owes the
  // window a frame of its own.
  const directions = [...titleBar.matchAll(/direction: "(North|South|East|West|[A-Za-z]+)"/g)].map(
    (match) => match[1],
  );
  assert.deepEqual(
    [...directions].sort(),
    ["East", "North", "NorthEast", "NorthWest", "South", "SouthEast", "SouthWest", "West"],
    "the window frame is missing an edge or a corner",
  );
  assert.match(titleBar, /plugin:window\|start_resize_dragging/);
  assert.match(titleBar, /value: grip\.direction/, "the grips do not tell the compositor which edge");
});

test("the resize frame is unconditional, and in particular not gated on isMaximized()", () => {
  // A shipped bug, found by running the app rather than by reading it. The grips used to be
  // withdrawn while the window reported itself maximised — sound reasoning resting on the
  // assumption that "maximised" describes a window somebody maximised. Hyprland hands the state to
  // ordinary floating windows, so the gate was permanently shut, no grip was ever rendered, and the
  // window could not be resized by mouse at all: the exact failure the grips exist to prevent,
  // reached through the code meant to prevent it.
  //
  // Pinned as a property of the component rather than of a comment. `ResizeGrips` takes no
  // parameters, so there is nowhere for a future condition to be threaded in without failing here.
  assert.match(
    titleBar,
    /function ResizeGrips\(\)/,
    "ResizeGrips takes an argument again — a frame the user cannot recover from must not be gated",
  );
  assert.match(titleBar, /<ResizeGrips \/>/, "the frame is being rendered conditionally");
  const body = titleBar.slice(titleBar.indexOf("function ResizeGrips()"));
  assert.doesNotMatch(
    body.slice(0, 300),
    /return null|maximized/,
    "the frame withdraws itself under some condition again",
  );
});

test("the chrome is refused where there is no window to control", () => {
  // Two other hosts run these components. The website has no window, and Android has a system
  // status bar and a back gesture — a close button on a phone is a control that lies. Neither is
  // decided by measuring the viewport: the seam reports which host the bundle was built for, and
  // `is_mobile_build` is compiled in.
  assert.match(titleBar, /platform\.id !== "tauri"/, "the browser build would try to drive a window");
  assert.match(titleBar, /fetchIsMobileBuild\(\)/, "Android would be given a close button");
  assert.match(titleBar, /if \(!chrome\) return null;/, "an unknown host still renders chrome");

  // And it reaches the host through the seam, never through `@tauri-apps`. `platform-seam.test.mjs`
  // owns that rule globally; it is restated here because this is the file most likely to break it —
  // `getCurrentWindow()` is the obvious import and it would ship a native bridge to the website.
  //
  // Comments are stripped first, the same way `ux-honesty.test.mjs` does it and for the same
  // reason: the paragraph in `TitleBar.tsx` explaining why the obvious import was rejected has to
  // name the import, and without this the prose justifying the rule fails the rule.
  const code = titleBar.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
  assert.doesNotMatch(code, /@tauri-apps/);
  assert.match(titleBar, /import \{ invoke, platform \} from "nodera-ui";/);
});
