// The route table, the page modules and the emitted HTML are the same set, three times.
//
// The site's two halves were written by two people at once, and the seam between them is that
// `routes.ts` is data and `pages/**` are components — neither edits the other's file. That is only
// safe while something checks that the two agree, in BOTH directions:
//
//   * an entry with no module is a URL that 404s, and the prerenderer skips it with a warning that
//     scrolls past in a build log;
//   * a module with no entry is a page somebody wrote that nothing links to and nothing renders,
//     which is this repository's own most-logged defect shape wearing a website's clothes.
//
// The emitted HTML is the third: it is what a visitor actually gets, and it is where a `<title>` or
// a canonical link goes missing without anything failing.
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { JSDOM } from "jsdom";
import { sourceFiles } from "nodera-ui/token-audit";
import { distDirectory, siteDirectory, SITE_ORIGIN } from "./layout.mjs";

const { routes, outputPath } = await import(path.join(siteDirectory, ".ssr/main.js"));
const PAGES = path.join(siteDirectory, "src/pages");

test("every route entry has the page module it names", () => {
  const missing = routes
    .filter((route) => route.file !== null)
    .filter((route) => !existsSync(path.join(PAGES, route.file)))
    .map((route) => `${route.path} → src/pages/${route.file}`);
  assert.deepEqual(missing, []);
});

test("every page module is in the route table", () => {
  const declared = new Set(routes.map((route) => route.file).filter(Boolean));
  const orphans = sourceFiles(PAGES, /\.tsx$/)
    .map((file) => path.relative(PAGES, file))
    .filter((file) => !declared.has(file));
  assert.deepEqual(orphans, [], "these pages render at no URL; add a route or delete them");
});

test("exactly one route is not produced by the generator, and it is /add-store", () => {
  // A `null` file is the escape hatch for a page that ships as reviewed bytes. One is a decision;
  // two would be a habit, and the second one would be a page nobody is testing.
  const external = routes.filter((route) => route.file === null).map((route) => route.path);
  assert.deepEqual(external, ["/add-store"]);
});

test("every generated route emitted an HTML file with a title, a description and a canonical", () => {
  for (const route of routes) {
    if (route.file === null) continue;
    const emitted = path.join(distDirectory, outputPath(route.path));
    assert.ok(existsSync(emitted), `${route.path} emitted no file at ${outputPath(route.path)}`);
    const html = readFileSync(emitted, "utf8");

    const title = /<title>([^<]*)<\/title>/.exec(html);
    assert.ok(title && title[1].trim().length > 0, `${route.path} has no <title>`);
    assert.equal(title[1], route.title, `${route.path}'s title is not the one in the route table`);

    const description = /<meta name="description" content="([^"]*)"/.exec(html);
    assert.ok(description && description[1].trim().length > 0, `${route.path} has no description`);

    const canonical = /<link rel="canonical" href="([^"]*)"/.exec(html);
    assert.ok(canonical, `${route.path} has no canonical link`);
    assert.equal(canonical[1], `${SITE_ORIGIN}${route.path}`);

    // A page that rendered nothing still emits a valid shell — the shape of "the component threw
    // and the renderer fell back". The mount point has to actually contain the page. Parsed rather
    // than matched: Vite hoists the module script into `<head>`, so a regex anchored on the script
    // tag after `#root` finds nothing and reports every page as empty.
    const { document } = new JSDOM(html).window;
    const root = document.getElementById("root");
    assert.ok(root, `${route.path} has no #root`);
    assert.ok(root.innerHTML.length > 200, `${route.path} emitted an empty body`);
  }
});

test("route paths are unique, and so are the files they emit", () => {
  const paths = routes.map((route) => route.path);
  assert.equal(new Set(paths).size, paths.length, "two routes claim the same URL");
  const outputs = routes.filter((route) => route.file !== null).map((route) => outputPath(route.path));
  assert.equal(new Set(outputs).size, outputs.length, "two routes emit the same file");
});
