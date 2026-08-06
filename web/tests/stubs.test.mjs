// A stub is a real page that says it has nothing to say, and nothing may lead to one.
//
// Three subjects are 🚧 in `docs/ROADMAP.md`, and writing them now would mean writing them twice —
// or writing them confidently, which would mean lying. So each ships as a route with a real title, a
// real one-paragraph lede, and a callout naming the task that will fill it.
//
// The interesting half is the fourth assertion, and it is what makes the other three worth having: a
// stub appears in the sidebar, marked, because a route with zero inbound links is unreachable
// debris — but no link on `/` and no link on the documentation index's start-here path may resolve
// to one. Somebody following the site's own recommended reading path must never arrive at a page
// that admits it is empty.
//
// It parses the EMITTED HTML for that, not the source. A source-level grep passes while a component
// composes the href from a variable, which is exactly how this rule would be lost.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { JSDOM } from "jsdom";
import { distDirectory, siteDirectory } from "./layout.mjs";

const { routes, outputPath } = await import(path.join(siteDirectory, ".ssr/main.js"));
const declared = JSON.parse(readFileSync(path.join(siteDirectory, "content/stubs.json"), "utf8"));

const page = (route) => readFileSync(path.join(distDirectory, outputPath(route)), "utf8");
/**
 * Every internal link in a page's CONTENT — everything outside its navigational chrome.
 *
 * The distinction is the whole of D-23's reading, and both halves of it are load-bearing. A stub
 * DOES belong in the sidebar, marked "unwritten": a route with zero inbound links is unreachable
 * debris, and somebody browsing the tree deserves to know the page exists and is empty rather than
 * to wonder whether they missed it. What a stub must never be is the target of a link in prose that
 * is telling somebody where to go next.
 */
const links = (route) =>
  [...new JSDOM(page(route)).window.document.querySelectorAll("a[href^='/']")]
    .filter((node) => node.closest("nav, aside, header, footer") === null)
    .map((node) => node.getAttribute("href").split("#")[0].replace(/\/$/, ""));

test("the route table and stubs.json name the same three pages", () => {
  const flagged = routes.filter((route) => route.stub).map((route) => route.path).sort();
  const listed = declared.map((entry) => entry.route).sort();
  assert.deepEqual(flagged, listed);
  assert.equal(flagged.length, 3, "the stub set changed; that is a decision, not a refactor");
  // Both sides, not one and an equality. `deepEqual([], [])` holds, so a `stubs.json` that failed
  // to parse into anything would satisfy the line above AND leave the rule below — "every stub names
  // the task that will fill it" — walking nothing.
  assert.equal(declared.length, 3, "content/stubs.json declares a different number of stubs");
});

test("every stub names the task that will fill it, and why it is not filled", () => {
  for (const entry of declared) {
    assert.ok(entry.task, `${entry.route} does not name an owning task`);
    assert.ok(entry.why && entry.why.length > 40, `${entry.route} does not say why it is a stub`);
  }
});

test("every stub is noindex", () => {
  for (const route of routes.filter((entry) => entry.stub)) {
    const { document } = new JSDOM(page(route.path)).window;
    const robots = document.querySelector('meta[name="robots"]');
    assert.ok(robots, `${route.path} has no robots meta`);
    assert.match(robots.getAttribute("content"), /noindex/);
  }
});

test("no stub appears in the sitemap", () => {
  const sitemap = readFileSync(path.join(distDirectory, "sitemap.xml"), "utf8");
  for (const route of routes.filter((entry) => entry.stub)) {
    assert.ok(!sitemap.includes(`${route.path}<`), `${route.path} is offered to crawlers`);
  }
});

test("nothing on / or on the docs start-here path leads to a stub", () => {
  const stubs = new Set(routes.filter((entry) => entry.stub).map((entry) => entry.path.replace(/\/$/, "")));
  // The landing page and the documentation index are the two pages that TELL somebody where to go.
  // The three start-here pages are the path they are told to take.
  for (const from of [
    "/",
    "/docs/",
    "/docs/start/what-noderamc-is",
    "/docs/start/install",
    "/docs/start/share-and-join",
  ]) {
    const reached = links(from).filter((href) => stubs.has(href));
    assert.deepEqual(
      [...new Set(reached)],
      [],
      `${from}'s prose links to a stub; the recommended reading path must not end on an empty page`,
    );
  }
});
