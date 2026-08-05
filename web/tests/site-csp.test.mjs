// Nothing this site serves reaches another origin, and nothing it serves carries an inline script.
//
// # Why the second half is the one that matters
//
// The theme is written on the `<html>` tag at build time and corrected after mount, rather than by
// an inline boot script. The cost is one frame of dark for a visitor whose system prefers light —
// never the reverse, which is the flash that hurts. What it buys is this assertion, and this
// assertion is what lets the deployment lane write
//
//   script-src 'self'
//
// as a constant, with no hash in it. A hash would mean something outside this repository reading a
// build artefact in order to write a security policy, and a policy that is regenerated per build is
// a policy that silently stops matching.
//
// # Why it parses instead of grepping
//
// `web/src/index.html` explains this decision in a comment, and that comment contains the literal
// text `<script>`. A regex over the emitted HTML flags every page on day one — the rule failing on
// the sentence that states it, which is a trap this repository has now hit three times in one
// afternoon. So the pages are parsed, and only real elements count.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { JSDOM } from "jsdom";
import { sourceFiles } from "nodera-ui/token-audit";
import { distDirectory } from "./layout.mjs";

/** Every emitted page, as `[relative path, source]`. */
const pages = sourceFiles(distDirectory, /\.html$/).map((file) => [
  path.relative(distDirectory, file),
  readFileSync(file, "utf8"),
]);

test("the build emitted pages at all", () => {
  // A suite that walks an empty directory reports every rule green. The count is what separates
  // "nothing is wrong" from "nothing was checked".
  assert.ok(pages.length >= 20, `only ${pages.length} page(s) in dist`);
});

test("no emitted page carries an inline script", () => {
  // `add-store.html` is excluded BY NAME, with its reason: it is the reviewed bytes of the project's
  // one live external contract, it predates this build, and it is self-contained precisely so a
  // defect in the site cannot reach it. Its own suite covers it in full.
  for (const [name, html] of pages) {
    if (name === "add-store.html") continue;
    const { document } = new JSDOM(html).window;
    const inline = [...document.querySelectorAll("script")].filter(
      (node) => !node.hasAttribute("src"),
    );
    assert.deepEqual(
      inline.map((node) => node.textContent.slice(0, 80)),
      [],
      `${name} carries an inline <script>, which forces a hash into the deployed CSP`,
    );
  }
});

test("no emitted page loads anything from another origin", () => {
  for (const [name, html] of pages) {
    const { document } = new JSDOM(html).window;
    // Only things the browser FETCHES. A `<link rel="canonical">` is a statement about this page's
    // address — it names the public origin by definition, and reading it as a third-party request
    // would make every page fail for saying where it lives.
    const fetched = 'link[rel="stylesheet"], link[rel="preload"], link[rel="modulepreload"], link[rel="icon"]';
    for (const node of document.querySelectorAll(`[src], ${fetched}`)) {
      const url = node.getAttribute("src") ?? node.getAttribute("href");
      assert.ok(
        !/^(https?:)?\/\//.test(url),
        `${name}: <${node.tagName.toLowerCase()}> pulls ${url} from another origin`,
      );
      assert.ok(!url.startsWith("http://"), `${name}: ${url} is not even https`);
    }
  }
});

test("no emitted stylesheet fetches a font or an image from another origin", () => {
  for (const file of sourceFiles(distDirectory, /\.css$/)) {
    const css = readFileSync(file, "utf8");
    const remote = [...css.matchAll(/url\((['"]?)((?:https?:)?\/\/[^)'"]+)\1\)/g)].map((m) => m[2]);
    assert.deepEqual(remote, [], `${path.relative(distDirectory, file)} fetches from another origin`);
    assert.doesNotMatch(css, /@import\s+url\(\s*['"]?https?:/);
  }
});

test("none of the usual third parties appear anywhere in dist", () => {
  // Named rather than derived, because each of these is a specific thing somebody adds without
  // thinking of it as a third party: a badge image, a contributor avatar strip, a font, an
  // analytics tag. The project runs an OPT-IN telemetry programme with a consent modal; loading a
  // third-party analytics script on the marketing site would undercut it in the one place a
  // sceptical reader looks first.
  const banned = [
    "googletagmanager",
    "google-analytics",
    "fonts.googleapis.com",
    "fonts.gstatic.com",
    "shields.io",
    "contrib.rocks",
    "cdn.jsdelivr.net",
    "unpkg.com",
    "plausible.io",
  ];
  for (const file of sourceFiles(distDirectory, /\.(html|css|js)$/)) {
    const text = readFileSync(file, "utf8");
    for (const host of banned) {
      assert.ok(!text.includes(host), `${path.relative(distDirectory, file)} references ${host}`);
    }
  }
});
