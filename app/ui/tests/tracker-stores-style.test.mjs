import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";

const trackerStores = readFileSync(new URL("../src/TrackerStores.tsx", import.meta.url), "utf8");
const desktopSettings = readFileSync(new URL("../src/Settings.tsx", import.meta.url), "utf8");
const nativeSettings = readFileSync(new URL("../../android/kotlin/ui/Settings.kt", import.meta.url), "utf8");
const assets = new URL("../dist/assets/", import.meta.url);
const builtCss = readdirSync(assets)
  .filter((name) => name.endsWith(".css"))
  .map((name) => readFileSync(new URL(name, assets), "utf8"))
  .join("\n");

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

test("built tracker-store CSS resolves desktop shell roles", () => {
  const desktopRoles = {
    "on-surface": "--text",
    "on-surface-variant": "--text-dim",
    muted: "--text-faint",
    surface: "--surface",
    "surface-container": "--surface-2",
    outline: "--line",
    primary: "--brand-2",
    "primary-fill": "--brand-gradient",
    "on-primary": "--color-white",
    error: "--danger",
    "error-container": "--surface-2",
    "on-error-container": "--danger",
    warning: "--warn",
    ok: "--up",
    "surface-hover": "--surface-hover",
    "outline-soft": "--line-soft",
  };

  for (const [role, source] of Object.entries(desktopRoles)) {
    assertBuiltRule(`--tracker-store-${role}:var(${source})`);
  }

  for (const declaration of [
    "color:var(--tracker-store-on-surface)",
    "color:var(--tracker-store-on-surface-variant)",
    "color:var(--tracker-store-muted)",
    "color:var(--tracker-store-primary)",
    "color:var(--tracker-store-on-primary)",
    "color:var(--tracker-store-error)",
    "color:var(--tracker-store-on-error-container)",
    "color:var(--tracker-store-warning)",
    "background-color:var(--tracker-store-surface)",
    "background-color:var(--tracker-store-surface-container)",
    "background:var(--tracker-store-primary-fill)",
    "background-color:var(--tracker-store-error-container)",
    "border-color:var(--tracker-store-outline)",
    // The import flow's own surfaces: the health dot, the hover target on every row action, and the
    // hairline between a store's heading and the services it carries. All three are new with the
    // preview-before-you-trust redesign, and all three are invisible if a shell forgets to resolve
    // them — a grey dot on grey reads as "no status" rather than as a missing custom property.
    "background-color:var(--tracker-store-ok)",
    "color:var(--tracker-store-ok)",
    "background-color:var(--tracker-store-surface-hover)",
    "border-color:var(--tracker-store-outline-soft)",
  ]) {
    assertBuiltRule(declaration);
  }

  // The store screen is a Settings section now, not a rail destination. It was one of the six
  // subsystem screens the launcher redesign moved out of the player's way — and the point of
  // asserting where it renders is that both shells must keep using the SAME component, so the
  // `--tracker-store-*` roles above have exactly one consumer per shell.
  assert.match(
    desktopSettings,
    /section === "stores" && <TrackerStoresScreen \/>/,
    "the desktop store screen is not rendered from Settings",
  );
  assertBuiltRule("padding-inline:var(--canvas-gutter)");
  assert.doesNotMatch(trackerStores, /px-\[26px\]/);
  assert.match(nativeSettings, /TrackerStoresPage\(offeredStoreUrl, onStoreHandled\)/);
});

test("importing a store shows what the address served before anything is trusted", () => {
  // The property being protected is that no path reaches `addTrackerStore` without passing through
  // `previewTrackerStore` first. The screen's whole redesign rests on it: a user deciding "I trust
  // this publisher" has to be shown what the publisher actually serves, and a shortcut added later
  // for convenience — an "add without checking" affordance, a link handled straight through — puts
  // the decision back where it was, with the user finding out afterwards.
  const previewAt = trackerStores.indexOf("previewTrackerStore(");
  assert.notEqual(previewAt, -1, "the import flow must fetch a preview");

  // `addTrackerStore` is called in exactly one place, and that place is reached from the preview
  // step: `onConfirm` is only wired to the button rendered beside the fetched service list.
  const addCalls = trackerStores.match(/\baddTrackerStore\(/g) ?? [];
  assert.equal(addCalls.length, 1, "a store must be added from one place only");
  assert.match(
    trackerStores,
    /step: "adding"[\s\S]{0,120}?onConfirm\(state\.url\)/,
    "adding must be entered from the preview step",
  );

  // A deep link is an intent, never an action — and "action" includes the fetch. A page on any
  // website can send someone here, and contacting the address it names tells that address this
  // install exists before its owner has agreed to anything. So a link lands on `offered`, which
  // shows the URL and contacts nothing; the request happens when the user asks for it.
  assert.match(
    trackerStores,
    /pendingTrackerStore\(\)[\s\S]*?step: "offered", url, source: "link"/,
    "a link must be shown, not fetched and not added",
  );
  assert.doesNotMatch(
    trackerStores,
    /pendingTrackerStore\(\)[\s\S]{0,400}?step: "checking"/,
    "a link must not start a fetch on arrival",
  );
});

test("no screen reports a tracker count from the typed list alone", () => {
  // `network.default_trackers` is what this install TYPED. The effective list is that plus whatever
  // the stores contribute, which is what `worker_env`, `WorkerConfig::of` and the synchronisation
  // file all build. Rendering the setting as though it were the list is what made a working install
  // read "0 tracker(s)" in Settings while Tracker stores, one row down, listed a tracker and a
  // relay — and the obvious conclusion, that adding a store does nothing, was wrong.
  //
  // So: a count shown to a person comes from `resolvedServices`, never from `.default_trackers
  // .length`. Editing that array is still fine — the textareas own it.
  const screens = {
    "Settings.tsx": readFileSync(new URL("../src/Settings.tsx", import.meta.url), "utf8"),
  };
  for (const [name, source] of Object.entries(screens)) {
    for (const line of source.split("\n")) {
      // Prose about the rule is not a breach of it. Without this the comment explaining *why* the
      // effective count is used would fail the test that enforces it.
      const trimmed = line.trim();
      if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
      // The one legitimate reading of the raw length is the guard that asks whether the user is
      // clearing a box they had filled in.
      if (line.includes("settings.network.default_trackers.length > 0")) continue;
      assert.doesNotMatch(
        line,
        /network\.default_trackers\.length/,
        `${name} counts the typed list; use resolvedServices() for anything a person reads`,
      );
    }
  }

  assert.match(nativeSettings, /NoderaCore\.(?:obj|call)\("resolved_services"\)/);
  assert.match(nativeSettings, /"set_direct_trackers"/);
  assert.match(nativeSettings, /Remove direct tracker/);
  assert.match(nativeSettings, /SectionTitle\("Tracker stores"/);
  assert.match(
    screens["Settings.tsx"],
    /resolvedServices\(\)/,
    "the desktop settings screen must read the effective list",
  );
});

test("the node screen does not report zero trackers before the worker has spoken", () => {
  // `unknown` and `zero` are different states and the screen must not collapse them. An app that
  // has never heard from its worker was telling users with a perfectly good store that they had no
  // trackers configured, and sending them to add one they already had.
  const app = readFileSync(new URL("../../android/kotlin/ui/Screens.kt", import.meta.url), "utf8");
  assert.match(
    app,
    /state\.known && state\.reachableTrackers == 0/,
    "the no-tracker card must be gated on having heard from the worker",
  );
  assert.match(
    app,
    /if \(!state\.known\) "Waiting for this node to report"/,
    "the tracker list must say it does not know yet, rather than saying there are none",
  );
});
