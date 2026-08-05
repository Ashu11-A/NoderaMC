// The one download the search box makes, and the four ways it can go wrong.
//
// # Why this file exists
//
// Because `search.test.mjs` — 20-odd assertions about the index and the ranking, all of them
// passing — did not notice that the dialog never displayed any of it. The index was correct, the
// ranking was correct, and the component that joins them cancelled its own fetch on the first
// re-render, so the box said "Loading the index…" until the tab was closed. There was no error and
// the request returned 200.
//
// A test suite that only ever checks the two ends of a pipe will pass for as long as the middle is
// missing. So the middle is a named function now — `searchIndexLoader` in `nodera-ui/search` — and
// this file drives it directly, with a `fetch` that answers whatever the assertion needs.
//
// # The property that actually fixed the bug
//
// `load()` is IDEMPOTENT. Calling it twice returns the first answer or the first request. That is
// what lets the effect in `search.tsx` stop reading the state it writes: an effect over an operation
// that cannot happen twice has no reason to ask whether it has already run. The first two tests
// below are that property, and they are the ones to keep if the rest are ever rewritten.
import assert from "node:assert/strict";
import test from "node:test";
import { searchIndexLoader } from "nodera-ui/search";

/** A minimal index that satisfies every check the loader makes. Content is `search.test.mjs`'s job. */
const WELL_FORMED = { version: 1, terms: [], idf: [], docs: [] };

/** A response, as much of one as the loader touches. */
const responds = (body, { ok = true, status = 200 } = {}) => ({
  ok,
  status,
  json: async () => body,
});

/**
 * A `fetch` that counts its calls and hands back a promise the test resolves by hand.
 *
 * Deferred rather than immediate because both interesting properties are about the window while a
 * request is outstanding: that a second caller joins it instead of starting another, and that a
 * caller who arrives after it failed gets a fresh attempt.
 */
function deferredFetch() {
  const calls = [];
  const stub = (url) => {
    let settle;
    const promise = new Promise((resolve, reject) => {
      settle = { resolve, reject };
    });
    calls.push({ url, ...settle });
    return promise;
  };
  return { stub, calls };
}

/** Lets every already-queued microtask run, so a `.then` chain has finished before we assert. */
const settled = () => new Promise((resolve) => setImmediate(resolve));

test("two callers who ask at the same moment share one download", async () => {
  // The dialog can be opened, closed and opened again in less time than the index takes to arrive.
  // Each of those runs the effect. If each also started a request, a reader on a slow connection
  // would download a 64 kB index once per keypress on the shortcut.
  const { stub, calls } = deferredFetch();
  const loader = searchIndexLoader({ fetch: stub, url: "/search-index.json", version: 1 });

  const first = loader.load();
  const second = loader.load();
  assert.equal(calls.length, 1, "the second caller started a second download");

  calls[0].resolve(responds(WELL_FORMED));
  assert.deepEqual(await first, WELL_FORMED);
  assert.deepEqual(await second, WELL_FORMED, "the second caller got a different answer");
  assert.equal(calls.length, 1);
});

test("the index is remembered, and `peek` is the same answer without a promise", async () => {
  // `peek` is not a convenience. It is what the component's initial state reads, and it is the
  // difference between reopening the dialog onto results and reopening it onto a frame of
  // "Loading the index…" over an index that is already in memory.
  const { stub, calls } = deferredFetch();
  const loader = searchIndexLoader({ fetch: stub, url: "/search-index.json", version: 1 });

  assert.equal(loader.peek(), null, "it claimed to hold an index it has never fetched");
  const first = loader.load();
  calls[0].resolve(responds(WELL_FORMED));
  await first;

  assert.deepEqual(loader.peek(), WELL_FORMED);
  assert.deepEqual(await loader.load(), WELL_FORMED);
  assert.equal(calls.length, 1, "a later caller re-downloaded an index already held");
});

test("a failed download is not remembered — the next press tries again", async () => {
  // The retry is the point. A reader whose connection dropped for the two seconds during which they
  // pressed Ctrl+K must not be told "could not load" for the rest of the session by a loader still
  // holding the promise that rejected.
  const { stub, calls } = deferredFetch();
  const loader = searchIndexLoader({ fetch: stub, url: "/search-index.json", version: 1 });

  const failing = loader.load();
  calls[0].reject(new Error("the network went away"));
  await assert.rejects(failing, /the network went away/);
  await settled();
  assert.equal(loader.peek(), null);

  const retry = loader.load();
  assert.equal(calls.length, 2, "the second attempt reused the promise that already rejected");
  calls[1].resolve(responds(WELL_FORMED));
  assert.deepEqual(await retry, WELL_FORMED);
});

test("an index from a future build is refused by version, not misread", async () => {
  // The stale-page case: a tab held open across a deploy, whose cached JavaScript scores against an
  // index whose fields have moved. Scoring it anyway produces confident nonsense, and a search box
  // that confidently returns the wrong page is worse than one that says it could not load.
  const { stub, calls } = deferredFetch();
  const loader = searchIndexLoader({ fetch: stub, url: "/search-index.json", version: 1 });

  const pending = loader.load();
  calls[0].resolve(responds({ ...WELL_FORMED, version: 2 }));
  await assert.rejects(pending, /this page expects index version 1, not 2/);
  assert.equal(loader.peek(), null, "a refused index was kept anyway");
});

test("something that is not an index is refused by shape", async () => {
  // What a misconfigured host actually serves: `index.html` for every path, with a 200 and a
  // content type nobody checked. Without this the failure surfaces as `undefined is not iterable`
  // out of the ranker, three frames from anything that names the cause.
  const { stub, calls } = deferredFetch();
  const loader = searchIndexLoader({ fetch: stub, url: "/search-index.json", version: 1 });

  const pending = loader.load();
  calls[0].resolve(responds({ version: 1, terms: "not an array", idf: [], docs: [] }));
  await assert.rejects(pending, /the index is not an index/);
  assert.equal(loader.peek(), null);
});

test("a server that says no is reported by its status", async () => {
  // The build not having run, or the asset not having been copied into the image. The status is in
  // the message because it is the difference between "nobody deployed the index" and "the CDN is
  // rate-limiting you", and the dialog prints this string verbatim.
  const { stub, calls } = deferredFetch();
  const loader = searchIndexLoader({ fetch: stub, url: "/search-index.json", version: 1 });

  const pending = loader.load();
  calls[0].resolve(responds(null, { ok: false, status: 404 }));
  await assert.rejects(pending, /the server answered 404/);
});
