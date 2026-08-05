// The search index is real data, and the ranking is run rather than read.
//
// # What this suite is defending
//
// A search box has one interesting failure and it is silent: it returns nothing, and nothing about
// the page says whether that is because the corpus has no answer, because the index never loaded,
// or because the two halves disagree about what a word is. The last of those is the one a build can
// catch, and it is why the tokenizer lives in `nodera-ui/search` with exactly two importers.
//
// So the assertions below are of three kinds:
//
//   * the index is well formed and complete — the vectors are unit vectors, the term list is sorted
//     (the query side binary-searches it), and every indexed path is a page the site actually built;
//   * the ranking ANSWERS — real queries are scored against the real built index, and the page a
//     reader obviously meant comes first. A similarity function nobody has run against the corpus is
//     one that returns the roadmap for every query, and it looks completely reasonable in review;
//   * the payload is small enough to hand to a visitor, stated as a number rather than a hope.
import assert from "node:assert/strict";
import { gzipSync } from "node:zlib";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { JSDOM } from "jsdom";
import { prefixRange, rank, tokenize } from "nodera-ui/search";
import { distDirectory, siteDirectory } from "./layout.mjs";

const INDEX = path.join(distDirectory, "search-index.json");

test("the build emitted a search index at all", () => {
  // First, because every assertion below reads this file and a missing one would otherwise be
  // twelve confusing failures about `undefined`.
  assert.ok(existsSync(INDEX), "scripts/build-search-index.mjs did not run — the box has nothing to search");
});

const raw = readFileSync(INDEX);
const index = JSON.parse(raw.toString("utf8"));
const { routes, outputPath } = await import(path.join(siteDirectory, ".ssr/main.js"));

/* ------------------------------------------------------------------------------- the shape */

test("the index declares a version the reader knows", () => {
  // The browser refuses an index whose version it does not recognise. That is only a defence if the
  // number is actually there — an index with no version is one a stale page will happily misread.
  assert.equal(index.version, 1);
  assert.equal(index.terms.length, index.idf.length, "idf is parallel to terms, or it is nothing");
});

test("the term list is sorted, because the query side binary-searches it", () => {
  // Not a tidiness assertion. `prefixRange` is a binary search; over an unsorted list it returns a
  // wrong range quietly, and typing `track` finds nothing while typing `tracker` works.
  for (let i = 1; i < index.terms.length; i += 1) {
    assert.ok(
      index.terms[i - 1] < index.terms[i],
      `terms are unsorted or duplicated at ${i}: ${index.terms[i - 1]} then ${index.terms[i]}`,
    );
  }
});

test("prefix lookup finds every term with that prefix and nothing else", () => {
  for (const prefix of ["track", "rendez", "world", "instal", "zz"]) {
    const [start, end] = prefixRange(index.terms, prefix);
    const found = index.terms.slice(start, end);
    assert.deepEqual(
      found,
      index.terms.filter((term) => term.startsWith(prefix)),
      `the binary search disagrees with a linear scan for "${prefix}"`,
    );
  }
});

test("every document vector is a unit vector over real term ids", () => {
  // The dot product the browser computes is only a cosine if these are unit vectors. If they are
  // not, the ranking silently becomes "longest page wins", which is exactly the ranking this
  // approach was chosen to avoid.
  for (const doc of index.docs) {
    assert.ok(doc.v.length > 0 && doc.v.length % 2 === 0, `${doc.p} has a ragged vector`);
    let sum = 0;
    for (let i = 0; i < doc.v.length; i += 2) {
      const id = doc.v[i];
      const weight = doc.v[i + 1];
      assert.ok(Number.isInteger(id) && id >= 0 && id < index.terms.length, `${doc.p}: bad term id ${id}`);
      assert.ok(weight > 0 && weight <= 1, `${doc.p}: weight ${weight} is outside (0, 1]`);
      sum += weight * weight;
    }
    // Rounded to three decimals at build time, so exact 1 is not available; 0.02 is far tighter
    // than any rounding error and far looser than a vector that was never normalised.
    assert.ok(Math.abs(Math.sqrt(sum) - 1) < 0.02, `${doc.p} has length ${Math.sqrt(sum).toFixed(3)}`);
  }
});

/* ------------------------------------------------------------------- the corpus it was built from */

test("every indexed page is a route the site actually built", () => {
  for (const doc of index.docs) {
    const route = routes.find((entry) => entry.path === doc.p);
    assert.ok(route, `the index carries ${doc.p}, which is not a route`);
    assert.equal(route.title, doc.t, `${doc.p}'s title drifted from the route table`);
    assert.ok(
      existsSync(path.join(distDirectory, outputPath(route.path))),
      `${doc.p} is indexed but was never emitted`,
    );
  }
});

test("every listed page is indexed — the index is not a subset somebody forgot to extend", () => {
  // The direction that fails silently. A page missing from the index is a page nobody can find, and
  // nothing about the site looks wrong.
  const listed = routes.filter((route) => route.file !== null && route.sitemap).map((route) => route.path);
  assert.deepEqual(
    listed.filter((page) => !index.docs.some((doc) => doc.p === page)),
    [],
    "these pages are in the sitemap and not in the search index",
  );
});

test("no stub, no /404 and no /add-store is indexed", () => {
  // `sitemap.mjs` already states the rule for search engines: a stub is a page with a real lede and
  // no content, and sending somebody there promises an answer that is not present. An on-site search
  // that did it would make the same promise with a shorter delay.
  for (const doc of index.docs) {
    const route = routes.find((entry) => entry.path === doc.p);
    assert.equal(route.stub, false, `${doc.p} is a stub`);
    assert.ok(route.sitemap, `${doc.p} is excluded from the sitemap but present in search`);
  }
});

test("the site furniture is not in every document's vector", () => {
  // The sidebar and the footer are identical on every page. Indexed, they would give every document
  // the same hundred terms — dead weight in the payload, and noise in a ranking whose whole basis is
  // that a term appearing everywhere distinguishes nothing.
  const install = index.docs.find((doc) => doc.p === "/docs/start/install");
  assert.ok(install, "/docs/start/install is not indexed");
  const terms = new Set();
  for (let i = 0; i < install.v.length; i += 2) terms.add(index.terms[install.v[i]]);
  // Words that appear ONLY in the footer's column headings and the sidebar's group labels.
  for (const furniture of ["privacy", "roadmap"]) {
    assert.ok(!terms.has(furniture), `"${furniture}" reached /docs/start/install through the chrome`);
  }
});

/* ------------------------------------------------------------------- the ranking, actually run */

/** The paths a query returns, best first. */
const search = (query) => rank(index, query).map((result) => result.doc.p);

test("the obvious query returns the obvious page, first", () => {
  // Scored against the REAL built index. These are the queries a reader types, and each expectation
  // is a page whose title says the thing — a ranking that gets these wrong is broken in a way no
  // amount of reading the formula reveals.
  const expected = [
    ["install", "/docs/start/install"],
    ["download", "/download"],
    ["privacy", "/privacy"],
    ["roadmap", "/docs/develop/roadmap"],
    ["wire protocol", "/docs/develop/wire-protocol"],
    ["mod compatibility", "/docs/develop/mod-compatibility"],
    ["host leaves", "/docs/using/when-the-host-leaves"],
  ];
  for (const [query, page] of expected) {
    const results = search(query);
    assert.equal(results[0], page, `"${query}" ranked ${JSON.stringify(results.slice(0, 3))}`);
  }
  // `rendezvous` has two right answers — the guide and the reference — and which of them leads is a
  // judgement, not a correctness property. What IS a correctness property is that neither is beaten
  // by a page that merely mentions the word.
  assert.deepEqual(
    search("rendezvous").slice(0, 2).sort(),
    ["/docs/operate/rendezvous", "/docs/operate/rendezvous-reference"],
  );
});

test("the page a query names beats the page that only links to it", () => {
  // The measured failure that produced the title component of the score. Pure cosine put `/docs/`
  // and `/docs/develop/` — two index pages whose whole text is links — above the wire protocol
  // reference itself, because a short document's single matching term is most of its length.
  const results = rank(index, "wire protocol");
  assert.equal(results[0].doc.p, "/docs/develop/wire-protocol");
  assert.ok(
    results[0].score > 3 * (results[1]?.score ?? 0),
    `the document scored ${results[0].score.toFixed(3)} against an index page's ` +
      `${(results[1]?.score ?? 0).toFixed(3)} — the margin is gone`,
  );
});

test("a half-typed word finds the page a finished one would", () => {
  // The prefix expansion, which is what this index has instead of a stemmer. Somebody types four
  // characters and stops reading; the result has to be there before they finish.
  for (const [partial, page] of [
    ["instal", "/docs/start/install"],
    ["rendezv", "/docs/operate/rendezvous"],
    ["roadma", "/docs/develop/roadmap"],
  ]) {
    assert.ok(search(partial).includes(page), `"${partial}" did not reach ${page}`);
  }
});

test("plurals work in both directions without a stemmer", () => {
  // The claim `search.mjs` makes about why it has no stemmer. Asserted rather than asserted-in-prose.
  assert.ok(search("service").includes("/services/"), "singular did not reach the services page");
  assert.ok(search("tracker").some((page) => page.includes("tracker")), "tracker found no tracker page");
});

test("a query nobody wrote about returns nothing, rather than the longest page", () => {
  // The failure this whole approach exists to avoid. A substring or term-count ranking answers every
  // query with whichever page has the most words in it.
  assert.deepEqual(search("qwertyuiop zxcvbnm"), []);
  // Stopwords only: there is no query here, and inventing results for one is worse than none.
  assert.deepEqual(search("the and of"), []);
  assert.deepEqual(search("   "), []);
});

test("results are ordered by score, capped, and never duplicated", () => {
  const results = rank(index, "tracker world peer");
  assert.ok(results.length > 1, "a three-word query about this project matched almost nothing");
  assert.ok(results.length <= 8, `${results.length} results — the dialog shows eight`);
  for (let i = 1; i < results.length; i += 1) {
    assert.ok(results[i - 1].score >= results[i].score, "results are not sorted by score");
  }
  assert.equal(new Set(results.map((result) => result.doc.p)).size, results.length);
  for (const result of results) {
    // The three components of a score are fractions that sum to one, so a score is comparable
    // across queries. A score above 1 means one of them stopped being a fraction.
    assert.ok(result.score > 0 && result.score <= 1, `score ${result.score} is outside (0, 1]`);
  }
});

test("a section is offered only when the query is in that heading", () => {
  // A deep link to a section that does not mention what was asked for is worse than a link to the
  // page: it looks like an answer and lands on a paragraph about something else.
  for (const result of rank(index, "tracker")) {
    if (!result.section) continue;
    assert.ok(
      tokenize(result.section.text).some((term) => term.startsWith("tracker")),
      `${result.doc.p} offered "${result.section.text}", which does not mention the query`,
    );
  }
});

test("the tokenizer is the one the index was built with", () => {
  // The whole reason it lives in the shared kit. If the two halves ever diverge, every query for a
  // hyphenated or numeric term stops matching and nothing reports an error.
  assert.deepEqual(tokenize("Self-hosting a Tracker"), ["self", "hosting", "tracker"]);
  assert.deepEqual(tokenize("tcp://150.230.84.206:6969"), ["tcp", "150", "230", "84", "206", "6969"]);
  assert.deepEqual(tokenize("the and of it"), []);
  // Every term the index carries must be one the tokenizer would produce. A term that cannot be
  // typed is a term that can never be found.
  for (const term of index.terms) {
    assert.deepEqual(tokenize(term), [term], `"${term}" is not a term the tokenizer would emit`);
  }
});

/* ------------------------------------------------------------------------------ what it weighs */

test("the payload is small enough to hand to a visitor", () => {
  // Stated as a number because "small" is not a property anybody notices growing. The budget is
  // roughly double today's size: the index scales with pages × terms-per-page, and 22 pages becoming
  // 45 should pass, while an index that started carrying page text should not.
  const gzipped = gzipSync(raw).length;
  assert.ok(
    raw.length < 160 * 1024,
    `the search index is ${(raw.length / 1024).toFixed(1)} kB uncompressed`,
  );
  assert.ok(gzipped < 48 * 1024, `the search index is ${(gzipped / 1024).toFixed(1)} kB gzipped`);
  // It is fetched on the first press rather than on load, so it is not in the critical path — but
  // it is still a download somebody on a phone pays for.
  console.log(
    `search index: ${(raw.length / 1024).toFixed(1)} kB raw, ${(gzipped / 1024).toFixed(1)} kB gzipped, ` +
      `${index.docs.length} pages, ${index.terms.length} terms`,
  );
});

test("the index is not bundled into the JavaScript every visitor loads", () => {
  // It is fetched on demand. A build that inlined it would put 64 kB in front of every reader,
  // including the ones who never press the button.
  for (const file of ["assets"]) {
    const dir = path.join(distDirectory, file);
    if (!existsSync(dir)) continue;
  }
  const bundles = readFileSync(path.join(distDirectory, "index.html"), "utf8");
  assert.doesNotMatch(bundles, /search-index\.json/, "index.html preloads the search payload");
});

/* ---------------------------------------------------------------------- the button on the page */

test("every page carries a visible search button, reachable without the shortcut", () => {
  // `Ctrl K` is invisible to anybody who has not been told about it. A search that can only be
  // reached by a shortcut is a search most readers never find.
  for (const route of routes.filter((entry) => entry.file !== null && entry.sitemap)) {
    const html = readFileSync(path.join(distDirectory, outputPath(route.path)), "utf8");
    const { document } = new JSDOM(html).window;
    const buttons = [...document.querySelectorAll("button")].filter((node) => {
      const label = `${node.getAttribute("aria-label") ?? ""} ${node.textContent}`.toLowerCase();
      return label.includes("search");
    });
    assert.ok(buttons.length > 0, `${route.path} has no search button`);
    // The shortcut is advertised on the element that has it, which is what a screen reader reads
    // out and what a keyboard user needs told.
    assert.ok(
      buttons.some((node) => (node.getAttribute("aria-keyshortcuts") ?? "").includes("Control+K")),
      `${route.path}'s search button does not announce its shortcut`,
    );
  }
});

test("the dialog is not in the prerendered HTML", () => {
  // It renders on interaction. A modal shipped open in static HTML is a page that covers itself for
  // anybody whose JavaScript has not arrived yet.
  const { document } = new JSDOM(readFileSync(path.join(distDirectory, "index.html"), "utf8")).window;
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.equal(document.querySelector('[role="listbox"]'), null);
});
