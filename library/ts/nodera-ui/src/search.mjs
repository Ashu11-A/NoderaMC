// The tokenizer and the ranker the search index and the search box must both use.
//
// # Why it is here and not in either of them
//
// A search index is a promise between two programs: a build script that reads every page and writes
// terms, and a browser that reads a query and looks terms up. If they disagree about what a term IS
// — whether `NoderaMC` is one token or two, whether `8x8` survives, whether `the` was ever stored —
// then the query silently matches nothing and the box looks broken with no error anywhere. That is
// the same class of defect `store-link.mjs` sits beside this file to prevent, and the same fix: one
// function, two importers, no second speller.
//
// The ranker is here for a second reason as well: it is the only interesting logic in the whole
// feature, and inside a React component it is unreachable by a test that does not mount a browser.
// Here, `web/tests/search.test.mjs` scores real queries against the real built index — which is the
// difference between a ranking somebody read and a ranking somebody ran.
//
// It is `.mjs` with a declaration beside it for exactly the reason `store-link.mjs` gives: the
// generator runs under plain Node, which does not read TypeScript, and one copied line would be the
// second speller.
//
// # Why there is no stemmer
//
// Because a stemmer is a fourth thing to keep identical across two runtimes, and it buys less here
// than prefix matching does. `service` and `services` are related by a prefix; so are `track`,
// `tracker` and `trackers`; so are `install` and `installer`. The query side matches a term exactly
// or by prefix, which covers the plural and the gerund without a rule that also turns `does` into
// `doe`. What it deliberately does not cover is `ran` → `run`, and that is an honest limit rather
// than a bug: this is a documentation site with 26 pages, not a search engine.

/**
 * Words carried by every page in the corpus, so they separate nothing.
 *
 * Kept short and closed on purpose. A long stoplist starts deleting words that are content in a
 * technical corpus — `does`, `over`, `off`, `up` and `down` all mean something specific here, and
 * "peer up" is a phrase somebody will type.
 */
const STOP = new Set([
  "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "for", "from", "had", "has",
  "have", "if", "in", "into", "is", "it", "its", "of", "on", "or", "so", "than", "that", "the",
  "their", "them", "then", "there", "these", "they", "this", "to", "was", "were", "when", "which",
  "will", "with", "you", "your",
]);

/** The longest a token may be. Beyond this it is a base64 blob or a hash, not a word. */
const MAX_TOKEN = 24;

/**
 * Split text into index terms.
 *
 * The rules, all four of them, because every one is a decision the other half has to make the same
 * way:
 *
 *   1. lowercase — a query is typed casually and a heading is not;
 *   2. split on anything that is not a letter or a digit — so `tcp://150.230.84.206:6969` becomes
 *      `tcp 150 230 84 206 6969` and somebody who types the port finds the page;
 *   3. drop single characters and stopwords;
 *   4. truncate at 24 characters.
 *
 * @param {string} text
 * @returns {string[]} terms, in order, with duplicates kept — the caller counts them
 */
export function tokenize(text) {
  const out = [];
  for (const raw of String(text).toLowerCase().split(/[^a-z0-9]+/)) {
    if (raw.length < 2) continue;
    const token = raw.length > MAX_TOKEN ? raw.slice(0, MAX_TOKEN) : raw;
    if (STOP.has(token)) continue;
    out.push(token);
  }
  return out;
}

/**
 * The first and last positions of `prefix` in a SORTED term list, as `[start, end)`.
 *
 * Binary search rather than a scan: this runs on every keystroke, and the alternative is a linear
 * pass over every term in the corpus per query word.
 *
 * @param {readonly string[]} terms sorted ascending
 * @param {string} prefix
 * @returns {[number, number]}
 */
export function prefixRange(terms, prefix) {
  const lower = (target) => {
    let lo = 0;
    let hi = terms.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (terms[mid] < target) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  };
  const start = lower(prefix);
  let end = start;
  while (end < terms.length && terms[end].startsWith(prefix)) end += 1;
  return [start, end];
}

/* =============================================================================== the ranking */

/** How many index terms one partial query word may expand to. A two-letter prefix hits hundreds. */
const PREFIX_LIMIT = 24;

/**
 * What a prefix match is worth against an exact one.
 *
 * Typing `track` should find the tracker pages, and it should not beat a page that literally says
 * `track`. Damping is what keeps a half-typed word from outranking a finished one.
 */
const PREFIX_DAMPING = 0.55;

/** A dialog showing more than this is a list nobody reads to the end of. */
const MAX_RESULTS = 8;

/**
 * How a score is made up: corpus similarity, the page's name, and its headings.
 *
 * The three sum to 1, so a score stays a number between 0 and 1 that can be compared across
 * queries — and each part is a fraction, so no component can run away with the ranking.
 *
 * # Why cosine alone is not enough here, measured
 *
 * This corpus is a handful of short index pages beside a few enormous mirrored references, and pure
 * cosine systematically prefers the short ones: a page whose whole text is twenty links has a tiny
 * vector, so one matching term is most of its length. Scored that way, `wire protocol` returned
 * `/docs/` and `/docs/develop/` — two index pages that merely LINK to it — above
 * `/docs/develop/wire-protocol`, which is the document itself and scored 0.066 against their 0.170.
 *
 * A title is the strongest statement a page makes about what it is about, and it is the one signal
 * length normalisation cannot dilute. So it is scored separately rather than by piling more weight
 * into the same vector, where it would be diluted by the same arithmetic all over again.
 */
const PART = { similarity: 0.5, title: 0.4, heading: 0.1 };

/**
 * @typedef {object} SearchDoc
 * @property {string} p route path
 * @property {string} t title
 * @property {string} d description
 * @property {[string, string][]} h `[id, text]` per indexed heading
 * @property {number[]} v alternating term id and weight — a unit vector
 */

/**
 * @typedef {object} SearchIndex
 * @property {number} version
 * @property {string[]} terms sorted ascending
 * @property {number[]} idf parallel to `terms`
 * @property {SearchDoc[]} docs
 */

/**
 * What fraction of the query's words this text contains.
 *
 * Prefix rather than equality, so a half-typed word counts: somebody typing `instal` is asking for
 * the page called "Install NoderaMC" and should not have to finish the word to be told so.
 *
 * @param {string[]} words the query, tokenized
 * @param {string[]} terms the text being checked, tokenized
 * @returns {number} 0 to 1
 */
function covered(words, terms) {
  if (words.length === 0) return 0;
  let hits = 0;
  for (const word of words) {
    if (terms.some((term) => term === word || term.startsWith(word))) hits += 1;
  }
  return hits / words.length;
}

/**
 * The heading a query best lands in, or `null`.
 *
 * Offered only when a heading contains a query word outright. A "closest heading" computed by
 * similarity would attach a section to every result, and a deep link to a section that does not
 * mention what was asked for is worse than a link to the page.
 *
 * @param {SearchDoc} doc
 * @param {string[]} words
 * @returns {{ id: string, text: string } | null}
 */
function bestSection(doc, words) {
  let best = null;
  let bestHits = 0;
  for (const [id, text] of doc.h ?? []) {
    // The same prefix rule `covered` uses, so a half-typed word offers the same section a finished
    // one does. Two matching rules for the same question is how a result's section appears and
    // disappears as somebody types the last letter of a word.
    const hits = covered(words, tokenize(text)) * words.length;
    if (hits > bestHits) {
      bestHits = hits;
      best = { id, text };
    }
  }
  return best;
}

/**
 * Cosine similarity between the query and every document, ranked.
 *
 * The documents are already unit vectors — normalised at build time — so normalising the query makes
 * the dot product below a genuine cosine: a number between 0 and 1 meaning "how much of what you
 * asked for is what this page is about", rather than "how many times your word appears", which is
 * the ranking that puts the longest page first for every query.
 *
 * Each query word contributes its inverse document frequency, so a rare word (`rendezvous`) steers
 * the result and a common one (`world`) barely moves it. A word that is not in the index at all is
 * expanded by prefix, damped, and shared out across its expansions — otherwise a two-letter prefix
 * matching forty terms would outweigh a word somebody finished typing.
 *
 * @param {SearchIndex} index
 * @param {string} query
 * @returns {{ doc: SearchDoc, score: number, section: { id: string, text: string } | null }[]}
 */
export function rank(index, query) {
  const words = tokenize(query);
  if (words.length === 0) return [];

  /** @type {Map<number, number>} term id → weight */
  const wanted = new Map();
  const bump = (id, weight) => {
    const held = wanted.get(id);
    if (held === undefined || weight > held) wanted.set(id, weight);
  };

  for (const word of words) {
    const [start, end] = prefixRange(index.terms, word);
    let exact = -1;
    for (let i = start; i < end; i += 1) {
      if (index.terms[i] === word) {
        exact = i;
        break;
      }
    }
    if (exact >= 0) bump(exact, index.idf[exact]);
    const expansions = [];
    for (let i = start; i < end && expansions.length < PREFIX_LIMIT; i += 1) {
      if (i !== exact) expansions.push(i);
    }
    for (const i of expansions) {
      bump(i, (index.idf[i] * PREFIX_DAMPING) / Math.sqrt(expansions.length));
    }
  }
  if (wanted.size === 0) return [];

  let norm = 0;
  for (const weight of wanted.values()) norm += weight * weight;
  norm = Math.sqrt(norm) || 1;

  const results = [];
  for (const doc of index.docs) {
    let similarity = 0;
    for (let i = 0; i < doc.v.length; i += 2) {
      const weight = wanted.get(doc.v[i]);
      if (weight !== undefined) similarity += (weight / norm) * doc.v[i + 1];
    }
    const section = bestSection(doc, words);
    const named = covered(words, tokenize(doc.t));
    const headed = section ? covered(words, tokenize(section.text)) : 0;
    const score =
      PART.similarity * similarity + PART.title * named + PART.heading * headed;
    // Zero means no term of the query is anywhere in this document. Returning it would be inventing
    // a result, which is the failure a search box has instead of an error message.
    if (score <= 0) continue;
    results.push({ doc, score, section });
  }
  // Ties broken by path, so the order is the same on every machine and in every test run. An
  // unstable sort over equal scores is a suite that fails one run in twenty for no reason.
  results.sort((a, b) => b.score - a.score || a.doc.p.localeCompare(b.doc.p));
  return results.slice(0, MAX_RESULTS);
}

/* ============================================================ fetching the index, exactly once */

/**
 * A loader for the published index: fetches it once, remembers it, and dedupes concurrent callers.
 *
 * # Why this is not four lines inside a `useEffect`
 *
 * Because it was, and it deadlocked. The effect read `state.status`, set it to `loading`, and had
 * `state.status` in its dependency list — so the state it had just written re-ran the effect, whose
 * CLEANUP cancelled the fetch the first run had started. The request completed; its `setState` was
 * skipped as stale; the dialog said "Loading the index…" forever, with no error in the console and a
 * 200 in the network panel. Every assertion about the index and the ranking passed the whole time,
 * because none of them mounted the component.
 *
 * A factory rather than a module-level singleton so a test can make its own with its own `fetch`,
 * and so this file holds no process-wide state.
 *
 * # What `load` being idempotent buys the caller
 *
 * The whole reason the effect could write its own dependency is that it was guarding a side effect
 * that must not happen twice. It does not have to guard this one: calling `load` a second time
 * returns the first answer, or the first request, and never starts a second download. So the effect
 * that calls it needs no `status` to look at, and the cycle has nowhere to form.
 *
 * `peek` is the same fact read synchronously, for the one thing a promise cannot do: decide what to
 * render on the FIRST paint. Without it, reopening the dialog shows "Loading the index…" for a frame
 * over an index that is already in memory.
 *
 * @param {object} options
 * @param {(url: string) => Promise<{ ok: boolean, status: number, json: () => Promise<unknown> }>} options.fetch
 *   Called as a plain function. Pass a closure, not a bare `window.fetch` — detached from its
 *   receiver that throws "Illegal invocation" in a browser and nowhere else.
 * @param {string} options.url
 * @param {number} options.version the only shape the caller knows how to score
 * @returns {{ load: () => Promise<SearchIndex>, peek: () => SearchIndex | null }}
 */
export function searchIndexLoader({ fetch, url, version }) {
  /** @type {SearchIndex | null} */
  let held = null;
  /** @type {Promise<SearchIndex> | null} */
  let inFlight = null;

  function load() {
    if (held) return Promise.resolve(held);
    if (inFlight) return inFlight;
    inFlight = fetch(url)
      .then(async (response) => {
        if (!response.ok) throw new Error(`the server answered ${response.status}`);
        const index = await response.json();
        // A page held in a cache while the index moved on. Refusing is the honest read: scoring
        // against fields that have changed shape produces confident nonsense, and a search box that
        // confidently returns the wrong page is worse than one that says it could not load.
        if (index.version !== version) {
          throw new Error(`this page expects index version ${version}, not ${index.version}`);
        }
        if (!Array.isArray(index.terms) || !Array.isArray(index.docs)) {
          throw new Error("the index is not an index");
        }
        held = index;
        inFlight = null;
        return index;
      })
      .catch((error) => {
        // Cleared, so a reader who opens the dialog again after their connection comes back gets a
        // second attempt rather than the first failure for the rest of the session.
        inFlight = null;
        throw error;
      });
    return inFlight;
  }

  return { load, peek: () => held };
}
