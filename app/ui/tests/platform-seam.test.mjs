// The platform seam holds only while exactly one file knows about Tauri.
//
// The seam exists so the website can be built from the same components as the launcher. Every
// property that makes that true is structural, and every one of them regresses silently:
//
//   * a second `@tauri-apps` import compiles, bundles, and ships a native bridge to a browser where
//     it throws about a missing internal global — a blank screen with no build error;
//   * a Tailwind class written inside the kit resolves to nothing in *both* applications, because
//     Tailwind v4 detects sources relative to the Vite root and does not scan dependency
//     directories. The page renders plain. Nothing warns;
//   * a browser stub that resolves an empty object instead of refusing turns "this page cannot
//     reach a node" into "this node has zero peers", which is the exact lie `api.ts`'s opening
//     paragraph exists to forbid.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { frontendRoots, pkg, repositoryDirectory } from "./layout.mjs";
import { sourceFiles, tokensIn } from "nodera-ui/token-audit";

const KIT = pkg("nodera-ui");
const read = (relative) => readFileSync(path.join(KIT, relative), "utf8");

/** Every TypeScript file of the kit and of the desktop frontend, as [repo-relative path, source]. */
function everyFrontendFile() {
  const out = [];
  for (const root of frontendRoots()) {
    for (const file of sourceFiles(root, /\.(tsx|ts)$/)) {
      out.push([path.relative(repositoryDirectory, file), readFileSync(file, "utf8")]);
    }
  }
  return out;
}

test("exactly one file in the frontend imports @tauri-apps", () => {
  const importers = everyFrontendFile()
    .filter(([, source]) => /from\s+"@tauri-apps\//.test(source))
    .map(([name]) => name)
    .sort();
  assert.deepEqual(
    importers,
    [path.relative(repositoryDirectory, path.join(KIT, "src/platform/tauri.ts"))],
    "the Tauri host is the seam's only Tauri importer; a second one ships the bridge to the browser",
  );
});

test("the browser host refuses every capability, in the host's own words", () => {
  const browser = read("src/platform/browser.ts");
  assert.doesNotMatch(browser, /@tauri-apps/, "the browser host must not import the native bridge");
  for (const capability of ["worker-link", "local-files", "process-control", "push-events"]) {
    assert.match(
      browser,
      new RegExp(`"${capability}":\\s*"[A-Z]`),
      `${capability} has no sentence explaining why it is unavailable`,
    );
  }
  // The refusal is a rejection, never a plausible-looking empty answer.
  assert.match(browser, /Promise\.reject\(\s*new Unsupported\(/);
  assert.doesNotMatch(browser, /EMPTY_|Promise\.resolve\(\{/);
});

test("the swap is a build-time alias, not a runtime probe", () => {
  assert.match(read("src/platform/impl.ts"), /export \{ platform \} from "\.\/tauri"/);
  const preset = read("vite/preset.mjs");
  assert.match(preset, /nodera-ui\/platform\/impl/, "the preset no longer aliases the seam");
  assert.match(preset, /browser\.ts/);
  // A probe leaves the Tauri import in the browser bundle, which is the one thing the seam is for.
  //
  // Comments are stripped first, the same way `ux-honesty.test.mjs` does it and for the same
  // reason: the prose explaining why a probe was rejected has to name the probe, and without this
  // the comment justifying the rule fails the rule.
  const code = (source) => source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
  for (const source of [read("src/platform/index.ts"), read("src/platform/impl.ts"), preset]) {
    assert.doesNotMatch(code(source), /__TAURI_INTERNALS__|__TAURI__/);
  }
});

test("the kit writes no Tailwind utility classes", () => {
  // Not a style rule. Tailwind v4 detects its sources relative to the Vite root and does not scan
  // dependency directories, so a class written here resolves to nothing in every application that
  // consumes the kit — silently, and identically to the consent-modal bug that
  // `design-tokens.test.mjs` was written for.
  const offenders = [];
  for (const file of sourceFiles(path.join(KIT, "src"), /\.(tsx|ts)$/)) {
    for (const token of tokensIn(readFileSync(file, "utf8"))) {
      offenders.push(`${path.relative(KIT, file)}: ${token}`);
    }
  }
  assert.deepEqual(offenders, [], `these would resolve to nothing:\n  ${offenders.join("\n  ")}`);
});

test("the desktop reaches the host through the seam and nothing else", () => {
  // The six lines that adopted it. Asserted by shape rather than by line number, because the point
  // is that these three modules have exactly one route to the host — not that the import sits on a
  // particular line.
  for (const name of ["api.ts", "ipc.ts", "play.ts"]) {
    const source = readFileSync(path.join(pkg("nodera-app-ui"), "src", name), "utf8");
    assert.match(source, /import \{ invoke \} from "nodera-ui";/, `${name} does not use the seam`);
    assert.match(
      source,
      /import \{ listen, type UnlistenFn \} from "nodera-ui";/,
      `${name} does not take its event subscription from the seam`,
    );
  }
  // The dependency followed the import. A package that still declares `@tauri-apps/api` can have a
  // stray import re-added without anything failing to resolve.
  const manifest = JSON.parse(
    readFileSync(path.join(pkg("nodera-app-ui"), "package.json"), "utf8"),
  );
  assert.equal(manifest.dependencies["@tauri-apps/api"], undefined);
  assert.equal(manifest.dependencies["nodera-ui"], "workspace:*");
});
