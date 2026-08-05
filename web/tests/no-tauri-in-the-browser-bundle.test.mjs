// The website is not the app, and its bundle must not contain the bridge to one.
//
// `@tauri-apps/api`'s `invoke` reads a global the browser does not have. Bundled into a web page it
// does not fail at build time and it does not warn: the first screen that calls it throws about a
// missing internal, React unmounts the tree, and the page is blank. That is why the platform seam
// swaps its implementation with a Vite ALIAS rather than a `window.__TAURI_INTERNALS__` probe — a
// probe leaves both branches in the graph, and this suite would have nothing to assert.
//
// Three checks, at three distances from the mistake: the declared dependencies, the source, and the
// bytes that actually ship. Only the third is proof.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { sourceFiles } from "nodera-ui/token-audit";
import { distDirectory, siteDirectory } from "./layout.mjs";

test("the site declares no @tauri-apps dependency", () => {
  const manifest = JSON.parse(readFileSync(path.join(siteDirectory, "package.json"), "utf8"));
  const declared = Object.keys({ ...manifest.dependencies, ...manifest.devDependencies });
  assert.deepEqual(declared.filter((name) => name.startsWith("@tauri-apps")), []);
});

test("no file under web/src imports @tauri-apps", () => {
  const offenders = sourceFiles(path.join(siteDirectory, "src"), /\.(tsx|ts)$/)
    .filter((file) => /from\s+["']@tauri-apps\//.test(readFileSync(file, "utf8")))
    .map((file) => path.relative(siteDirectory, file));
  assert.deepEqual(offenders, []);
});

test("nothing in the shipped bundle mentions Tauri", () => {
  // The one that is proof. The two above can both pass while a transitive import drags the bridge
  // in — the seam's `impl.ts` re-exports the Tauri host, and the whole arrangement rests on the
  // alias in `nodera-ui/vite` replacing that module before rollup resolves it.
  const offenders = [];
  for (const file of sourceFiles(distDirectory, /\.(js|html|css)$/)) {
    const text = readFileSync(file, "utf8");
    for (const needle of ["__TAURI_INTERNALS__", "__TAURI__", "@tauri-apps"]) {
      if (text.includes(needle)) offenders.push(`${path.relative(distDirectory, file)}: ${needle}`);
    }
  }
  assert.deepEqual(offenders, [], "the alias did not take; the browser bundle carries the bridge");
});

test("the site reaches the shared kit, so the absence above means something", () => {
  // The inverse half, and it needs stating precisely rather than optimistically.
  //
  // Proving the Tauri host is absent proves nothing on its own — a bundle that imports the kit not
  // at all passes every assertion above for the wrong reason. What the site actually consumes today
  // is `storeOfferHref`; the platform seam's browser host is in the graph behind it but is
  // tree-shaken, because no page on this site calls a node. That is correct and worth writing down:
  // a website that invoked a worker command would be a website claiming to reach a machine it
  // cannot see.
  //
  // So the check is that the kit is REACHED. If `/services/` ever hand-rolled the deep-link URL
  // instead of importing it, this fails, and that is the drift that matters here.
  const bundle = sourceFiles(distDirectory, /\.js$/)
    .map((file) => readFileSync(file, "utf8"))
    .join("\n");
  assert.match(
    bundle,
    /\/add-store\?url=/,
    "no bundled code composes the deep-link URL, so the site is not using the shared kit",
  );
});
