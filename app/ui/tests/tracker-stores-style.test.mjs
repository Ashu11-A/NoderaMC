import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";

const trackerStores = readFileSync(new URL("../src/TrackerStores.tsx", import.meta.url), "utf8");
const desktopShell = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
const mobileSettings = readFileSync(new URL("../src/mobile/Settings.tsx", import.meta.url), "utf8");
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

test("built tracker-store CSS resolves desktop and mobile shell roles", () => {
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
    scrim: "--color-black",
  };
  const mobileRoles = {
    "on-surface": "--md-sys-color-on-surface",
    "on-surface-variant": "--md-sys-color-on-surface-variant",
    muted: "--md-sys-color-on-surface-variant",
    surface: "--md-sys-color-surface-container-high",
    "surface-container": "--md-sys-color-surface-container-highest",
    outline: "--md-sys-color-outline-variant",
    primary: "--md-sys-color-primary",
    "primary-fill": "--md-sys-color-primary",
    "on-primary": "--md-sys-color-on-primary",
    error: "--md-sys-color-error",
    "error-container": "--md-sys-color-error-container",
    "on-error-container": "--md-sys-color-on-error-container",
    warning: "--md-sys-color-error",
    ok: "--md-sys-color-tertiary",
    "surface-hover": "--md-sys-color-surface-container-highest",
    "outline-soft": "--md-sys-color-outline-variant",
    scrim: "--md-sys-color-scrim",
  };

  for (const [role, source] of Object.entries(desktopRoles)) {
    assertBuiltRule(`--tracker-store-${role}:var(${source})`);
  }
  for (const [role, source] of Object.entries(mobileRoles)) {
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
    "background-color:var(--tracker-store-scrim)",
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

  assert.match(
    desktopShell,
    /screen\.name === "stores"[\s\S]*?<div className="max-w-\[1100px\] px-\[26px\] pt-5 pb-10">\s*<TrackerStoresScreen \/>/,
  );
  assert.match(
    mobileSettings,
    /className="flex-1 overflow-y-auto px-4 pb-6"[\s\S]*?page === "stores" && <TrackerStoresScreen shell="mobile" \/>/,
  );
  assertBuiltRule("padding-inline:26px");
  assertBuiltRule("padding-inline:calc(var(--spacing) * 4)");
  assert.doesNotMatch(trackerStores, /px-\[26px\]/);
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
