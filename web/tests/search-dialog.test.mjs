// The search dialog, mounted.
//
// # Why this file had to exist before the feature could be called finished
//
// `search.test.mjs` scores real queries against the real built index and passes twenty-odd
// assertions. `search-loader.test.mjs` drives the download through every way it can fail. Both of
// them were green for the entire time the dialog was broken, because neither of them MOUNTED it.
// What shipped said "Loading the index…" forever: the effect read `state.status`, wrote `loading`,
// and listed `state.status` as a dependency, so its own write re-ran it and the re-run's cleanup
// cancelled the request the first run had started. The response arrived to a `cancelled` flag. No
// error, no empty list, a 200 in the network panel — and two suites reporting that search worked.
//
// A pipe tested only at its two ends passes for as long as the middle is missing. So this file is
// the middle: it renders the real component into a real DOM over a `fetch` it controls, and asserts
// on the one thing the reader can see — the state the dialog is IN, published as `data-search-state`
// on whatever `Results` returns.
//
// # Why it compiles the component through Vite
//
// Because the alternative is a second copy of the component in a form Node can read, and a copy is
// the defect this whole feature already has a `nodera-ui` module to prevent. `ssrLoadModule` runs
// `search.tsx` through the site's own `vite.config.ts` — the same MDX, React and platform-seam
// plugins the shipped bundle goes through — so what is mounted here is the file that ships, not a
// transcription of it.

import assert from "node:assert/strict";
import path from "node:path";
import test, { after } from "node:test";
import { JSDOM } from "jsdom";
import { searchIndexLoader } from "nodera-ui/search";
import { siteDirectory } from "./layout.mjs";

/* ------------------------------------------------------------------------------ a browser */

const dom = new JSDOM("<!doctype html><html><body></body></html>", {
  url: "https://noderamc.org/",
  // React schedules through `requestAnimationFrame` when it has one. Without this jsdom has none,
  // and the difference between "no frames" and "frames" is a class of test that passes locally and
  // hangs in CI.
  pretendToBeVisual: true,
});

for (const name of [
  "Element", "Event", "HTMLElement", "HTMLInputElement", "KeyboardEvent", "MouseEvent",
  "MutationObserver", "Node", "SVGElement", "document",
  "requestAnimationFrame", "cancelAnimationFrame",
]) {
  globalThis[name] = dom.window[name];
}
globalThis.window = dom.window;
// Defined rather than assigned. Node 21 gave `globalThis` its own `navigator` as a getter-only
// accessor, so a plain assignment throws here and silently does nothing in a CommonJS file — which
// is a difference worth spelling out, because the CommonJS version looks like it worked.
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
// Wrapped rather than assigned, for exactly the reason this component passes `fetch` as a closure:
// jsdom's `getComputedStyle` is a method of `window`, and handing the bare function to `globalThis`
// detaches it from its receiver. The failure is an "Illegal invocation" thrown from inside React,
// four frames from anything that names the cause.
globalThis.getComputedStyle = (...args) => dom.window.getComputedStyle(...args);
// React refuses to run `act` outside an environment that has claimed to be one, and says so.
globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const React = await import("react");
const { createRoot } = await import("react-dom/client");
const { act } = React;

/* ------------------------------------------------------------------- the component that ships */

const { createServer } = await import("vite");
const vite = await createServer({
  configFile: path.join(siteDirectory, "vite.config.ts"),
  root: path.join(siteDirectory, "src"),
  server: { middlewareMode: true, hmr: false },
  logLevel: "error",
  appType: "custom",
});
after(() => vite.close());

const { SearchDialog } = await vite.ssrLoadModule("/components/search.tsx");

/* ------------------------------------------------------------------------------- the fixtures */

/**
 * Two pages, two terms, real vectors.
 *
 * Small enough to reason about and real enough to rank: a query for `tracker` must return exactly
 * one of these, which is what makes "results appeared" an assertion rather than a hope.
 */
const INDEX = {
  version: 1,
  terms: ["rendezvous", "tracker"],
  idf: [1.2, 0.9],
  docs: [
    { p: "/docs/deploy/tracker", t: "Run a tracker", d: "Announcing a world.", h: [["ports", "Ports"]], v: [1, 1] },
    { p: "/docs/deploy/rendezvous", t: "Run a rendezvous", d: "Relaying a connection.", h: [], v: [0, 1] },
  ],
};

/** As much of a `Response` as the loader touches. */
const responds = (body, { ok = true, status = 200 } = {}) => ({ ok, status, json: async () => body });

/**
 * A `fetch` the test settles by hand, counting its calls.
 *
 * Deferred because every question here is about the window while the request is outstanding — which
 * is the only window in which the original bug existed.
 */
function deferredFetch() {
  const calls = [];
  return {
    calls,
    stub: (url) =>
      new Promise((resolve, reject) => {
        calls.push({ url, resolve, reject });
      }),
  };
}

/** A loader wired to a stub, exactly as `search.tsx` wires the real one to `window.fetch`. */
function stubbedLoader() {
  const { stub, calls } = deferredFetch();
  return { calls, loader: searchIndexLoader({ fetch: stub, url: "/search-index.json", version: 1 }) };
}

/* ------------------------------------------------------------------------------ mounting it */

/**
 * Render into a detached container and read back the state the dialog is publishing.
 *
 * `state()` is the whole point of the file. Every branch of `Results` carries `data-search-state`,
 * so "did it leave loading" is one attribute read rather than a string match against prose somebody
 * will reword.
 */
async function mount(element) {
  const container = dom.window.document.createElement("div");
  dom.window.document.body.append(container);
  const root = createRoot(container);
  await act(async () => root.render(element));
  return {
    container,
    render: async (next) => act(async () => root.render(next)),
    unmount: async () => {
      await act(async () => root.unmount());
      container.remove();
    },
    state: () => container.querySelector("[data-search-state]")?.getAttribute("data-search-state") ?? null,
    text: () => container.textContent ?? "",
    results: () => [...container.querySelectorAll("li[role=option] a")].map((a) => a.getAttribute("href")),
  };
}

/** Type into the query box the way a reader does — through the tracked value setter React watches. */
async function type(view, text) {
  const input = view.container.querySelector("input[role=combobox]");
  assert.ok(input, "the dialog rendered no query input");
  const setValue = Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, "value").set;
  await act(async () => {
    setValue.call(input, text);
    input.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
  });
}

const noop = () => {};

/* ---------------------------------------------------------------------------- the assertions */

test("an open dialog leaves the loading state when the index arrives", async () => {
  // THE regression. Under the version that shipped, the effect's own `setState` re-ran it, the
  // re-run's cleanup marked the response stale, and this assertion is the one that would have
  // failed: `loading`, permanently, with the request resolved and nobody listening.
  const { loader, calls } = stubbedLoader();
  const view = await mount(React.createElement(SearchDialog, { open: true, onClose: noop, loader }));

  assert.equal(view.state(), "loading", "the dialog did not say it was loading anything");
  assert.equal(calls.length, 1, "an open dialog did not ask for the index");
  assert.equal(calls[0].url, "/search-index.json");

  await act(async () => calls[0].resolve(responds(INDEX)));

  assert.equal(view.state(), "ready", "the index arrived and the dialog never noticed");
  assert.match(view.text(), /Type to search 2 pages/, "it is ready but does not say what it holds");
  await view.unmount();
});

test("a query typed into a loaded dialog is answered from the index", async () => {
  // The whole pipe in one test: download, state, rank, DOM. `search.test.mjs` proves the ranking is
  // right; this proves the ranking is REACHED, which is the half that was missing.
  const { loader, calls } = stubbedLoader();
  const view = await mount(React.createElement(SearchDialog, { open: true, onClose: noop, loader }));
  await act(async () => calls[0].resolve(responds(INDEX)));

  await type(view, "tracker");

  assert.deepEqual(view.results(), ["/docs/deploy/tracker"], "the ranked result never reached the DOM");
  assert.equal(calls.length, 1, "typing started a second download of the index");
  await view.unmount();
});

test("a query nothing matches is a sentence, not an empty list", async () => {
  // The rule the component's header states: a box that returns nothing looks identical whether the
  // corpus has no answer or the index failed, and only one of those is the reader's fault.
  const { loader, calls } = stubbedLoader();
  const view = await mount(React.createElement(SearchDialog, { open: true, onClose: noop, loader }));
  await act(async () => calls[0].resolve(responds(INDEX)));

  await type(view, "kubernetes");

  assert.equal(view.state(), "ready");
  assert.deepEqual(view.results(), []);
  assert.match(view.text(), /Nothing on this site matches/, "an empty result list said nothing at all");
  await view.unmount();
});

test("a download that fails names the reason in the dialog", async () => {
  // The status is in the string because it is the difference between "nobody deployed the index"
  // and "the CDN is rate-limiting you". `search-loader.test.mjs` proves the loader produces that
  // message; this proves the dialog prints it instead of showing an empty box.
  const { loader, calls } = stubbedLoader();
  const view = await mount(React.createElement(SearchDialog, { open: true, onClose: noop, loader }));

  await act(async () => calls[0].resolve(responds(null, { ok: false, status: 503 })));

  assert.equal(view.state(), "failed", "a failed download left the dialog claiming to be loading");
  assert.match(view.text(), /the server answered 503/, "the dialog swallowed the reason");
  await view.unmount();
});

test("closing while it is still downloading does not abandon the download", async () => {
  // Pressing Escape during the fetch drops THIS subscription, not the request. The version that
  // shipped conflated the two — its cleanup cancelled the download — and that is precisely how a
  // re-render came to cancel a request nobody had asked to stop.
  const { loader, calls } = stubbedLoader();
  const view = await mount(React.createElement(SearchDialog, { open: true, onClose: noop, loader }));
  assert.equal(calls.length, 1);

  await view.render(React.createElement(SearchDialog, { open: false, onClose: noop, loader }));
  await act(async () => calls[0].resolve(responds(INDEX)));
  await view.render(React.createElement(SearchDialog, { open: true, onClose: noop, loader }));

  assert.equal(view.state(), "ready", "the dialog came back to an index it had already been handed");
  assert.equal(calls.length, 1, "reopening re-downloaded the index");
  await view.unmount();
});

test("reopening after the index is held shows no loading frame at all", async () => {
  // Why `peek` exists. Initial state is seeded from what is already in memory, so the second press
  // of Ctrl+K paints results rather than a frame of "Loading the index…" over an index the page has
  // had for ten minutes.
  const { loader, calls } = stubbedLoader();
  const first = await mount(React.createElement(SearchDialog, { open: true, onClose: noop, loader }));
  await act(async () => calls[0].resolve(responds(INDEX)));
  await first.unmount();

  const second = await mount(React.createElement(SearchDialog, { open: true, onClose: noop, loader }));

  assert.equal(second.state(), "ready", "a remount flashed loading over an index already in memory");
  assert.equal(calls.length, 1, "a remount downloaded the index a second time");
  await second.unmount();
});

test("a closed dialog renders nothing and downloads nothing", async () => {
  // The index is fetched on the first press, not on load: a visitor who never searches never pays
  // for the corpus. That is a claim the component's header makes, so it is one this file checks.
  const { loader, calls } = stubbedLoader();
  const view = await mount(React.createElement(SearchDialog, { open: false, onClose: noop, loader }));

  assert.equal(view.container.innerHTML, "", "a closed dialog rendered something");
  assert.equal(calls.length, 0, "the index was downloaded by a dialog nobody opened");
  await view.unmount();
});
