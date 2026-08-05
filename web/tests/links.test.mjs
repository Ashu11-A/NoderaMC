// Every internal link resolves to a page this site actually emitted, and every external one is https.
//
// The failure being guarded against is the cheapest one on any documentation site and the one
// readers punish hardest: a cross-reference to a page that was renamed. Nothing in a static build
// notices. The link renders, it is clickable, and it 404s — and because the site emits one real file
// per route rather than a shell that answers 200 to everything, it 404s honestly, which is the whole
// reason to check it here rather than hope.
//
// It walks the EMITTED HTML rather than the source, so a link composed from a variable is checked
// too.
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { JSDOM } from "jsdom";
import { sourceFiles } from "nodera-ui/token-audit";
import { distDirectory, siteDirectory, SITE_ORIGIN } from "./layout.mjs";

const { routes, outputPath } = await import(path.join(siteDirectory, ".ssr/main.js"));
const known = new Set(routes.map((route) => route.path.replace(/\/$/, "") || "/"));

/** Everything the emitted site links to, as `[page, href]`. */
function everyLink() {
  const out = [];
  for (const file of sourceFiles(distDirectory, /\.html$/)) {
    const name = path.relative(distDirectory, file);
    if (name === "add-store.html") continue; // reviewed bytes, and its own suite checks its links
    const { document } = new JSDOM(readFileSync(file, "utf8")).window;
    for (const node of document.querySelectorAll("a[href]")) {
      out.push([name, node.getAttribute("href")]);
    }
  }
  return out;
}

const all = everyLink();

test("the site emitted links at all", () => {
  assert.ok(all.length > 100, `only ${all.length} links found; the walk found nothing`);
});

test("every internal link resolves to a route", () => {
  const broken = [];
  for (const [page, href] of all) {
    if (!href.startsWith("/")) continue;
    const target = href.split("#")[0].split("?")[0].replace(/\/$/, "") || "/";
    // `/add-store` is a real URL served from reviewed bytes; it has no route module, so it is
    // checked against the file rather than against the table.
    if (target === "/add-store") {
      assert.ok(existsSync(path.join(distDirectory, "add-store.html")));
      continue;
    }
    if (!known.has(target)) broken.push(`${page} → ${href}`);
  }
  assert.deepEqual(broken, [], `these links resolve to nothing:\n  ${broken.join("\n  ")}`);
});

test("every internal link points at a file that exists in dist", () => {
  // The route table saying a URL exists and the build having emitted it are two different facts, and
  // the second is the one a visitor experiences.
  const missing = [];
  for (const [page, href] of all) {
    if (!href.startsWith("/")) continue;
    const target = href.split("#")[0].split("?")[0];
    if (target === "/add-store") continue;
    const route = routes.find((entry) => (entry.path.replace(/\/$/, "") || "/") === (target.replace(/\/$/, "") || "/"));
    if (!route || route.file === null) continue;
    if (!existsSync(path.join(distDirectory, outputPath(route.path)))) {
      missing.push(`${page} → ${href}`);
    }
  }
  assert.deepEqual(missing, []);
});

test("every external link is https", () => {
  const insecure = all
    .filter(([, href]) => /^https?:/.test(href))
    .filter(([, href]) => href.startsWith("http://"))
    .map(([page, href]) => `${page} → ${href}`);
  assert.deepEqual(insecure, []);
});

test("no link points at this site by absolute URL", () => {
  // An absolute self-link survives a domain change as a link to the old domain, and it defeats every
  // local preview. The canonical tags are the one legitimate place the origin appears.
  const absolute = all
    .filter(([, href]) => href.startsWith(SITE_ORIGIN))
    .map(([page, href]) => `${page} → ${href}`);
  assert.deepEqual(absolute, []);
});
