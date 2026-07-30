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
