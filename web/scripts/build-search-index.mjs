// The search index: every page's text, scored at build time, shipped as data.
//
// # Why the similarity is precomputed and the browser only ranks
//
// Because there is nowhere else to put it. There is no server — the site is static files behind
// Caddy — and there may be no third-party search service: the deployed CSP is `connect-src 'self'`,
// which forbids calling one, and an earlier study of the reference site rejected its Algolia key for
// that exact reason. So the corpus statistics that make ranking meaningful (which words are rare,
// how long each document is, what each document is about) are computed here, once, by the machine
// that already has every page in front of it. The browser does the only part that depends on the
// query: tokenize it, look terms up, add weights, sort.
//
// # Why it reads the BUILT pages and not the sources
//
// Three reasons, and the first is enough. Pages move: content is being lifted out of the landing
// page into `/docs/**` as this ships, and an index built from a hardcoded page list or from MDX
// sources would index the file that used to hold a paragraph rather than the page that holds it now.
// Second, the built page is what a reader actually sees — mirrored documents, generated tables and
// the roadmap only exist after the generators have run. Third, the route table is the site's own
// definition of what a page is, so the walk is over `routes` × `outputPath()` rather than over a
// glob that would also sweep up `404.html` and the reviewed bytes of `add-store.html`.
//
// # What is excluded, and why it is not an oversight
//
//   * Routes with `sitemap: false` — `/404`, `/add-store` and every stub. The site already states
//     the rule for search engines in `sitemap.mjs`: a stub is a page with a real lede and no
//     content, and sending somebody there from a search is promising an answer that is not present.
//     An on-site search that did it would be making the same promise with a shorter delay.
//   * `nav`, `aside` and `footer` inside a page — the sidebar, the table of contents and the site
//     footer are IDENTICAL on every page. Indexing them gives every document the same hundred terms,
//     which is both dead weight in the payload and noise in the ranking: a term that appears in
//     every document distinguishes none of them.
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { JSDOM } from "jsdom";
import { tokenize } from "nodera-ui/search";
import { distDirectory, siteDirectory } from "../tests/layout.mjs";

const { routes, outputPath } = await import(path.join(siteDirectory, ".ssr/main.js"));

/** Where the browser fetches it from. Same-origin, which is all `connect-src 'self'` permits. */
const OUT = path.join(distDirectory, "search-index.json");

/**
 * How many terms of each document survive into the payload.
 *
 * A cap rather than the whole vector, because the tail of a tf-idf vector is words that appeared
 * once in a long page and will never decide a ranking — they are half the bytes and none of the
 * answers. 120 is enough that a page's distinctive vocabulary is fully present.
 */
const TERMS_PER_DOC = 120;

/** Title, headings, body: three fields, three weights. A page is mostly about what it is called. */
const FIELD_WEIGHT = { title: 3, heading: 2, body: 1 };

/* ------------------------------------------------------------------------- read the built pages */

/** One document: what it is called, where it is, and every term in it with a raw weight. */
const documents = [];

for (const route of routes) {
  if (route.file === null) continue; // `/add-store` is reviewed bytes, not a page this build made.
  if (!route.sitemap) continue; // stubs and `/404` — see the header.

  const file = path.join(distDirectory, outputPath(route.path));
  let html;
  try {
    html = readFileSync(file, "utf8");
  } catch {
    // Not a warning to be scrolled past. The route table said this page exists; if it does not, the
    // index would silently omit a page the site links to from its own navigation.
    throw new Error(`build-search-index: ${route.path} has no built page at ${file}`);
  }

  const { document } = new JSDOM(html).window;
  const main = document.querySelector("main") ?? document.body;
  // Cloned before surgery: the DOM is thrown away after this, but removing nodes from the page a
  // later assertion might read is the kind of shared-mutation bug that is invisible until it is not.
  const body = main.cloneNode(true);
  for (const furniture of body.querySelectorAll("nav, aside, footer")) furniture.remove();

  // Headings are collected BEFORE the body text, from the cleaned tree, so a result can offer a
  // deep link to the section rather than only to the page.
  const headings = [...body.querySelectorAll("h2[id], h3[id]")]
    .map((node) => ({ id: node.id, text: node.textContent.replace(/\s+/g, " ").trim() }))
    .filter((entry) => entry.text.length > 0 && entry.text.length < 90);

  const text = body.textContent.replace(/\s+/g, " ").trim();

  /** term → weight, summed across the three fields. */
  const counts = new Map();
  const add = (source, weight) => {
    for (const term of tokenize(source)) counts.set(term, (counts.get(term) ?? 0) + weight);
  };
  add(route.title, FIELD_WEIGHT.title);
  add(route.description, FIELD_WEIGHT.title);
  for (const heading of headings) add(heading.text, FIELD_WEIGHT.heading);
  add(text, FIELD_WEIGHT.body);

  /**
   * The terms a document may not lose to the cap: what it is CALLED.
   *
   * This is not a nicety, it is the repair of a measured failure. `/docs/develop/wire-protocol`
   * mirrors the whole NDR2 reference — thousands of distinct terms, most of them rare and therefore
   * high-scoring — and `wire` and `protocol` are common across this corpus and therefore not. The
   * cap kept a hundred and twenty pieces of specialist vocabulary and dropped the two words in the
   * page's own title, so the query "wire protocol" scored that page at exactly ZERO and returned
   * the documentation index instead. It looked like a plausible result, which is the worst kind.
   */
  //
  // The title and its description only, never the headings: that is at most a couple of dozen terms
  // per page, so the payload cost of pinning is bounded and known. Pinning every heading of a
  // fifty-heading mirrored reference would push the page's body out of its own vector.
  const pinned = new Set([...tokenize(route.title), ...tokenize(route.description)]);

  documents.push({
    path: route.path,
    title: route.title,
    description: route.description,
    tier: route.tier,
    headings: headings.slice(0, 12),
    counts,
    pinned,
    length: text.length,
  });
}

if (documents.length === 0) {
  throw new Error("build-search-index: no pages were indexed; the site would ship a dead search box");
}

/* --------------------------------------------------------------------------------- score them */

/** In how many documents each term appears. The whole reason ranking beats substring matching. */
const documentFrequency = new Map();
for (const doc of documents) {
  for (const term of doc.counts.keys()) {
    documentFrequency.set(term, (documentFrequency.get(term) ?? 0) + 1);
  }
}

const N = documents.length;
/**
 * Smoothed inverse document frequency.
 *
 * `log(1 + N/df)` rather than `log(N/df)`: with 22 documents, a term in every one of them would
 * otherwise score exactly zero and drop out of the index, taking with it the ability to match a
 * query that consists only of common words. Smoothed, it is merely worth very little, which is the
 * true statement.
 */
const idfOf = (term) => Math.log(1 + N / documentFrequency.get(term));

/** The strongest `TERMS_PER_DOC` terms of one document, L2-normalised, as `[term, weight]`. */
function vectorFor(doc) {
  const weighted = [];
  for (const [term, count] of doc.counts) {
    // `1 + log(count)` — the fourth occurrence of a word says much less than the second, and a long
    // page would otherwise outrank a precise short one on every term it happens to repeat.
    weighted.push([term, (1 + Math.log(count)) * idfOf(term)]);
  }
  weighted.sort((a, b) => b[1] - a[1]);
  // What a page is CALLED survives the cap unconditionally; the rest of the budget goes to whatever
  // scored highest. See `pinned` above for the failure this repairs.
  const kept = [
    ...weighted.filter(([term]) => doc.pinned.has(term)),
    ...weighted.filter(([term]) => !doc.pinned.has(term)),
  ].slice(0, Math.max(TERMS_PER_DOC, doc.pinned.size));
  // Normalised AFTER the cap, so the vector the browser scores against is a genuine unit vector and
  // the dot product it computes is a cosine. Normalising first and then truncating would leave every
  // long document with a systematically short vector and rank it below every short one.
  const norm = Math.sqrt(kept.reduce((sum, [, weight]) => sum + weight * weight, 0)) || 1;
  return kept
    .map(([term, weight]) => [term, Math.round((weight / norm) * 1000) / 1000])
    // A weight that rounds to zero contributes nothing to a score and costs bytes in every payload.
    .filter(([, weight]) => weight > 0);
}

const vectors = documents.map(vectorFor);

/**
 * Only the terms a query can actually reach.
 *
 * A term that survived nowhere — dropped from every document by the cap above — can never contribute
 * to a score, so shipping it is shipping a word the search cannot find. Pruning is close to free in
 * recall precisely because the cap keeps the highest-weighted terms: what it drops is the words a
 * page happened to use twice, and the words that distinguish a page are the ones with high idf.
 *
 * It is most of the payload. Unpruned this index carries 5,458 terms and their idf values; reachable
 * are about a third of that.
 *
 * The list is SORTED, because the browser resolves a partial query word by binary-searching for its
 * prefix range. An unsorted list would make every keystroke a linear scan of the whole corpus.
 */
const reachable = [...new Set(vectors.flat().map(([term]) => term))].sort();
const termId = new Map(reachable.map((term, i) => [term, i]));

const payload = {
  // Bumped when the shape below changes. The browser refuses an index it does not understand rather
  // than scoring against fields that moved — a search box returning confident nonsense is worse than
  // one that says it could not load.
  version: 1,
  terms: reachable,
  // Two decimals: these are multipliers between about 0.1 and 3.2, and the third digit changes no
  // ranking anybody can perceive while costing a byte per term.
  idf: reachable.map((term) => Math.round(idfOf(term) * 100) / 100),
  docs: documents.map((doc, i) => ({
    p: doc.path,
    t: doc.title,
    d: doc.description,
    h: doc.headings.map((heading) => [heading.id, heading.text]),
    // A flat array of alternating id and weight: fewer bytes than an array of pairs, and the browser
    // reads it with a stride-2 loop.
    v: vectors[i].flatMap(([term, weight]) => [termId.get(term), weight]),
  })),
};

writeFileSync(OUT, JSON.stringify(payload));

const bytes = readFileSync(OUT).length;
console.log(
  `build-search-index: ${payload.docs.length} page(s), ${reachable.length} reachable term(s) of ` +
    `${documentFrequency.size}, ${(bytes / 1024).toFixed(1)} kB at ${path.relative(distDirectory, OUT)}`,
);
