// `/add-store` — the seventeen rules, run against the page rather than read off it.
//
// # Why this suite exists at all
//
// This is the only genuinely functional page the project has today. It is the target of the root
// README's badge, and it exists because GitHub's markdown sanitiser strips any href whose scheme is
// not http/https/mailto — so a `nodera://` button renders dead on the repository page and this is
// the https hop that turns a click into that scheme.
//
// Nobody has ever checked that it satisfies the three rules its own header comment says it exists to
// keep. It ships as the bytes that were reviewed (`scripts/build-site.sh` installs it, and the
// digest assertion below is what makes that binding), and these tests are the acceptance suite a
// React port has to pass **unmodified** before it may replace those bytes.
//
// # The one that carries the weight
//
// R1 — "nothing fires on load" — is proved by CONSTRUCTION, not by inspection. The page runs with a
// `location` object whose `href` setter throws, and the test asserts the load completes. No amount
// of refactoring can pass that while firing the scheme on load. A source-ordering grep would pass
// while the behaviour was wrong, which is the specific way this rule gets lost in a rewrite.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { JSDOM } from "jsdom";
import { layoutValue, repositoryDirectory } from "nodera-ui/layout";
import { storeOfferHref } from "nodera-ui/store-link";
import { distDirectory, siteDirectory } from "./layout.mjs";

const SOURCE = path.join(siteDirectory, "add-store.html");
const html = readFileSync(SOURCE, "utf8");

const sha256 = (buffer) => createHash("sha256").update(buffer).digest("hex");

/**
 * The page's markup, with its script NOT run. Enough for the rules that are about what it says.
 *
 * `outside-only` rather than `dangerously` is deliberate even here: the page's script is run by
 * `open()` below, once, at a moment this suite chooses. Letting jsdom run it at construction time
 * too would attach every listener twice, and a click that fires two navigations would look like a
 * defect in the page rather than in the harness.
 */
function load(search = "") {
  const dom = new JSDOM(html, {
    url: `https://noderamc.org/add-store${search}`,
    runScripts: "outside-only",
    pretendToBeVisual: true,
  });
  return { dom, window: dom.window, document: dom.window.document };
}

/**
 * Load the page and run its script with a `location` whose `href` setter is watched.
 *
 * jsdom's own `window.location` is not configurable, so it cannot be replaced. The script is
 * therefore evaluated inside a function whose `window` parameter SHADOWS the global one — which is
 * the same interception with a smaller blast radius: everything the page does through `window` is
 * observed, and `document`, `setTimeout` and `navigator` remain the real ones.
 *
 * Methods are bound on the way out. An unbound `getSelection` taken off a proxy and called with the
 * proxy as its receiver is an illegal invocation, and R12 would fail for a reason that has nothing
 * to do with the page.
 */
function open(search, { throwOnNavigate = false } = {}) {
  const context = load(search);
  const navigations = [];
  const location = {
    get search() {
      return search;
    },
    set href(value) {
      if (throwOnNavigate) throw new Error(`the page navigated to ${value} without a click`);
      navigations.push(value);
    },
  };
  const view = new Proxy(context.window, {
    get(target, key) {
      if (key === "location") return location;
      const value = Reflect.get(target, key);
      return typeof value === "function" ? value.bind(target) : value;
    },
  });
  context.window.__noderaView = view;
  const script = context.document.querySelector("script:not([src])").textContent;
  context.window.eval(`(function (window) {\n${script}\n})(window.__noderaView)`);
  return { ...context, navigations };
}

const HTTPS = "https://lists.example.org/index.json";
const shown = (context) => context.document.getElementById("url").textContent;
const refusal = (context) => context.document.getElementById("badwhy").textContent;
const visible = (context, id) => context.document.getElementById(id).hidden === false;

/* --------------------------------------------------------------------------- R1: the load path */

test("R1 nothing fires on load — proved by a location that refuses to navigate", () => {
  // If any code path reached the scheme without a click, the setter below throws and this fails.
  // The URL carries a perfectly valid list, so there is nothing about the input discouraging it.
  const context = open(`?url=${encodeURIComponent(HTTPS)}`, { throwOnNavigate: true });
  assert.ok(visible(context, "ok"), "a valid link must still reach the offer");
  assert.equal(context.navigations.length, 0);
});

/* ------------------------------------------------------------------------ R2–R6: reading input */

test("R2 only ?url= is read, with URLSearchParams", () => {
  const context = open(`?other=x&url=${encodeURIComponent(HTTPS)}&trailing=y`);
  assert.equal(shown(context), HTTPS);
});

test("R3 an absent or empty url is refused, in its own words", () => {
  for (const search of ["", "?", "?url="]) {
    const context = open(search);
    assert.ok(visible(context, "bad"), `${search || "(none)"} should refuse`);
    assert.equal(
      refusal(context),
      "This link did not carry a list address, so there is nothing to add.",
    );
  }
});

test("R4 a malformed URL is refused rather than offered", () => {
  const context = open("?url=not%20a%20url");
  assert.equal(
    refusal(context),
    "The address in this link is not a valid URL, so it is not being offered to the app.",
  );
  assert.ok(!visible(context, "ok"));
});

test("R5 a non-https URL is refused, naming the scheme it saw", () => {
  // Naming the scheme matters: "this is not https" tells somebody nothing about what they clicked.
  for (const [url, scheme] of [
    ["http://lists.example.org/index.json", "http"],
    ["ftp://lists.example.org/index.json", "ftp"],
    ["javascript:alert(1)", "javascript"],
  ]) {
    const context = open(`?url=${encodeURIComponent(url)}`);
    assert.equal(
      refusal(context),
      `This link points at a ${scheme} address. Service lists must be served over https, ` +
        "so it is not being offered.",
    );
  }
});

test("R6 the raw string is displayed, never the re-serialised URL", () => {
  // Every input here is one `new URL()` would rewrite. The whole decision a visitor is making is
  // "do I trust this publisher", and it cannot be made against a string the page repaired.
  for (const raw of [
    "https://example.org:443/list.json", // URL() drops the default port
    "https://example.org/a%2Fb?x=1", // percent-encoding must survive verbatim
    "https://example.org/list.json ", // a trailing space is not tidied away
  ]) {
    const context = open(`?url=${encodeURIComponent(raw)}`);
    assert.equal(shown(context), raw);
    // Only meaningful where the two actually differ. `URL()` preserves `%2F`, so that input is here
    // to prove the encoding survives rather than to prove the parsed form was rejected — asserting
    // inequality on it would be asserting that two identical strings differ.
    if (raw !== new URL(raw).href) {
      assert.notEqual(shown(context), new URL(raw).href, `${raw} was repaired before being shown`);
    }
  }
});

/* ------------------------------------------------------------------- R7–R10: the click and back */

test("R7 a click invokes the scheme with the raw url, encoded once", () => {
  const raw = "https://example.org/a%2Fb?x=1";
  const context = open(`?url=${encodeURIComponent(raw)}`);
  context.document.getElementById("open").click();
  assert.deepEqual(context.navigations, [
    `nodera://tracker-store?url=${encodeURIComponent(raw)}`,
  ]);
});

test("R8 the fallback appears after 2000 ms, and only while the tab is still here", async () => {
  const context = open(`?url=${encodeURIComponent(HTTPS)}`);
  context.document.getElementById("open").click();
  assert.ok(!visible(context, "fallback"), "the fallback must not appear before the timer");
  context.dom.window.eval("window.__advance = 1");
  await new Promise((resolve) => setTimeout(resolve, 2100));
  assert.ok(visible(context, "fallback"), "nothing handled the scheme and nothing said so");
});

test("R9 leaving the tab cancels the fallback — the app DID open", async () => {
  const context = open(`?url=${encodeURIComponent(HTTPS)}`);
  context.document.getElementById("open").click();
  // The app taking over backgrounds this tab. Telling somebody "nothing opened" as they come back
  // from the thing that opened is worse than saying nothing.
  Object.defineProperty(context.document, "hidden", { configurable: true, value: true });
  context.document.dispatchEvent(new context.window.Event("visibilitychange"));
  await new Promise((resolve) => setTimeout(resolve, 2100));
  assert.ok(!visible(context, "fallback"));
});

test("R10 a repeat click re-arms the timer instead of stacking one", async () => {
  const context = open(`?url=${encodeURIComponent(HTTPS)}`);
  const open_ = context.document.getElementById("open");
  open_.click();
  await new Promise((resolve) => setTimeout(resolve, 1500));
  open_.click(); // 1500 ms in: a stacked timer would fire ~500 ms from now
  await new Promise((resolve) => setTimeout(resolve, 900));
  assert.ok(!visible(context, "fallback"), "the first click's timer was not cleared");
  await new Promise((resolve) => setTimeout(resolve, 1300));
  assert.ok(visible(context, "fallback"));
  assert.equal(context.navigations.length, 2);
});

/* ------------------------------------------------------------------------- R11–R12: the copy path */

test("R11 copy uses the clipboard in a secure context, and labels the outcome", async () => {
  const context = open(`?url=${encodeURIComponent(HTTPS)}`);
  const written = [];
  Object.defineProperty(context.window, "isSecureContext", { configurable: true, value: true });
  Object.defineProperty(context.window.navigator, "clipboard", {
    configurable: true,
    value: { writeText: (text) => (written.push(text), Promise.resolve()) },
  });
  const copy = context.document.getElementById("copy");
  copy.click();
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.deepEqual(written, [HTTPS]);
  assert.equal(copy.textContent, "Copied");
  await new Promise((resolve) => setTimeout(resolve, 1700));
  assert.equal(copy.textContent, "Copy the address");
});

test("R11 a rejected clipboard write says so rather than claiming success", async () => {
  const context = open(`?url=${encodeURIComponent(HTTPS)}`);
  Object.defineProperty(context.window, "isSecureContext", { configurable: true, value: true });
  Object.defineProperty(context.window.navigator, "clipboard", {
    configurable: true,
    value: { writeText: () => Promise.reject(new Error("denied")) },
  });
  const copy = context.document.getElementById("copy");
  copy.click();
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(copy.textContent, "Copy failed");
});

test("R12 without a clipboard the address is selected instead of the button doing nothing", () => {
  const context = open(`?url=${encodeURIComponent(HTTPS)}`);
  Object.defineProperty(context.window, "isSecureContext", { configurable: true, value: false });
  const copy = context.document.getElementById("copy");
  copy.click();
  assert.equal(copy.textContent, "Selected — press Ctrl/⌘+C");
  assert.equal(String(context.window.getSelection()), HTTPS);
});

/* ---------------------------------------------------------------- R13–R17: what the page says */

test("R13 the page is noindex", () => {
  const context = load("");
  const robots = context.document.querySelector('meta[name="robots"]');
  assert.equal(robots.getAttribute("content"), "noindex");
});

test("R14 a refusal still offers what a service list is", () => {
  const context = open("");
  const link = context.document.querySelector("#bad a");
  assert.match(link.textContent, /What\s+a service list is/);
  assert.equal(
    link.getAttribute("href"),
    `https://github.com/Ashu11-A/NoderaMC/blob/${layoutValue("services.branch")}/README.md`,
  );
});

test("R15 the fallback names the manual path and offers the app", () => {
  const context = load("");
  const fallback = context.document.getElementById("fallback").textContent;
  assert.match(fallback, /Settings → Tracker stores/);
  const releases = context.document.querySelector('#fallback a[href*="releases"]');
  assert.equal(releases.getAttribute("href"), "https://github.com/Ashu11-A/NoderaMC/releases/latest");
});

test("R16 the footer says a tracker is a hint, never an authority", () => {
  const context = load("");
  const foot = context.document.querySelector(".foot").textContent.replace(/\s+/g, " ");
  assert.match(foot, /never an authority/);
  assert.match(foot, /every service proves its own identity/);
  assert.match(foot, /no service can touch world state/);
});

test("R17 the page is self-contained: no external CSS, font, script or image", () => {
  const context = load("");
  for (const node of context.document.querySelectorAll("[src], [href]")) {
    const url = node.getAttribute("src") ?? node.getAttribute("href");
    // An outbound `<a>` is a link somebody chooses to follow, not a request the page makes.
    if (node.tagName === "A") continue;
    assert.ok(
      !/^(https?:)?\/\//.test(url),
      `${node.tagName} pulls ${url} from another origin; this page makes no requests`,
    );
  }
  assert.equal(context.document.querySelectorAll("script[src]").length, 0);
  assert.equal(context.document.querySelectorAll("link[rel=stylesheet]").length, 0);
  // Comments are removed by the parser that already read this page, never by a regex over its
  // source. The page's own header comment lists what it refuses to load — "no external CSS, no
  // fonts, no analytics, no requests of any kind" — so without removing them the sentence stating
  // the rule would fail the rule. `app/ui/tests/ux-honesty.test.mjs` learned the same lesson.
  //
  // One pass of `/<!--[\s\S]*?-->/g` looks equivalent and is not: it decides where a comment ends
  // by looking at the text, while the browser decides by parsing. The two disagree on exactly the
  // inputs that matter, so a string the parser never treated as a comment can be deleted anyway —
  // and the assertion then passes over markup that is really there. It is also the shape CodeQL
  // rejects as incomplete multi-character sanitization, and it is right to.
  const comments = [];
  const walker = context.document.createTreeWalker(
    context.document,
    context.window.NodeFilter.SHOW_COMMENT,
  );
  while (walker.nextNode()) comments.push(walker.currentNode);
  for (const comment of comments) comment.remove();
  assert.doesNotMatch(
    context.document.documentElement.outerHTML,
    /@import|fonts\.googleapis|analytics|gtag|googletagmanager/,
  );
});

/* ------------------------------------------------------------- the page ships as reviewed bytes */

test("the built page is byte-identical to the source", () => {
  // The digest is computed from the source AT TEST TIME, never compared against a literal checked in
  // here. A frozen hash would fail the first time somebody corrects a comment in that file — which
  // has already happened once, in the docs consolidation — and a test that cries wolf about a
  // comment is a test people start passing with `--force`.
  const built = path.join(distDirectory, "add-store.html");
  assert.ok(existsSync(built), "scripts/build-site.sh did not install add-store.html");
  assert.equal(sha256(readFileSync(built)), sha256(readFileSync(SOURCE)));
});

test("the site generator did not take the /add-store URL", () => {
  // A route called `/add-store` would emit `add-store/index.html`, which most servers prefer, and
  // the reviewed page would sit beside it being served to nobody.
  assert.ok(!existsSync(path.join(distDirectory, "add-store", "index.html")));
});

test("the README badge still points here, at the list layout.properties declares", () => {
  const readme = readFileSync(path.join(repositoryDirectory, "README.md"), "utf8");
  const badge = /https:\/\/noderamc\.org(\/add-store\?url=[^"')\s]+)/.exec(readme);
  assert.ok(badge, "the README no longer links to /add-store — the page's only inbound link");
  // Composed by the shared helper both the site and this test use, so the badge cannot drift from
  // `services.rawUrl` in either direction without one of them failing.
  assert.equal(badge[1], storeOfferHref(layoutValue("services.rawUrl")));
});

/* ===========================================================================================
 * THE SENDING END — the buttons that press this page
 *
 * Everything above is the receiving end: given a `?url=`, does the page do the right seventeen
 * things. Nothing above asks where that `?url=` comes from, and that is exactly where the site
 * shipped broken.
 *
 * `/services/` rendered every "Add to NoderaMC" button as
 *
 *   /add-store?url=%2Fadd-store%3Furl%3Dhttps%253A%252F%252Fraw…
 *
 * because the generator wrote a finished href into the data and the component composed a second one
 * on top of it. Every rule above still passed — the page was handed a string that is not a URL, and
 * it refused it, in its own words, which is R4 working perfectly. The two halves were each correct
 * and the contract between them was written down nowhere a build could read.
 *
 * So these tests join the two halves: take the href the site actually emitted, feed it to the page
 * the way a browser would, PRESS THE BUTTON, and decode what the app would receive. A grep for
 * `%253A` would have caught this one instance; pressing the button catches the class.
 * ========================================================================================= */

/** What the app receives from a press: the `url` the `nodera://` intent carries, decoded. */
function pressAndDecode(offerHref) {
  assert.ok(offerHref.startsWith("/add-store?"), `${offerHref} is not an offer href`);
  const context = open(offerHref.slice(offerHref.indexOf("?")));
  assert.ok(
    !visible(context, "bad"),
    `the page refused the site's own button: "${refusal(context)}" (href was ${offerHref})`,
  );
  assert.ok(visible(context, "ok"), `${offerHref} reached neither the offer nor a refusal`);
  context.document.getElementById("open").click();
  assert.equal(context.navigations.length, 1, "one press must invoke the scheme exactly once");
  // Decoded with `URLSearchParams`, which is what the page itself reads the inbound `?url=` with —
  // so a round trip that survives here is one that survives the real hop rather than one that
  // survives this test's idea of decoding.
  return {
    shown: shown(context),
    received: new URL(context.navigations[0]).searchParams.get("url"),
  };
}

test("every Add-to-NoderaMC button on /services/ delivers the publisher's URL unchanged", () => {
  const built = path.join(distDirectory, "services", "index.html");
  assert.ok(existsSync(built), "scripts/build-site.sh did not emit /services/");
  const { document } = new JSDOM(readFileSync(built, "utf8")).window;
  const offers = [...document.querySelectorAll('a[href^="/add-store"]')].map((node) =>
    node.getAttribute("href"),
  );

  // The count is what separates "every button is correct" from "there are no buttons". The
  // generated list is the authority on how many rows that page has.
  const published = JSON.parse(
    readFileSync(path.join(siteDirectory, "src/generated/services.json"), "utf8"),
  );
  assert.ok(published.services.length > 0, "the generated service list is empty");
  assert.equal(
    offers.length,
    published.services.length,
    "one offer button per published service, and no more",
  );

  for (const [i, href] of offers.entries()) {
    const publisher = published.services[i].storeIndexUrl;
    const { received, shown: displayed } = pressAndDecode(href);
    // Not "a valid URL", not "starts with https" — the publisher's string, character for character.
    // A page that repairs an address has taken the decision away from the person making it.
    assert.equal(
      received,
      publisher,
      `the app would receive a different address from row ${i + 1} than the one published`,
    );
    assert.equal(displayed, publisher, `row ${i + 1} showed a different address than it offers`);
  }
});

test("the offer survives a publisher URL full of characters that encode badly", () => {
  // The generated list holds one plain raw.githubusercontent.com URL, so the rows above cannot fail
  // on encoding alone. These are the inputs that separate "encoded once" from "encoded twice" and
  // from "not encoded at all": a `%2F` that must not become `%252F`, a `+` that must not arrive as
  // a space, an `&` that must not split the query, and a `#` that must not truncate it.
  for (const publisher of [
    "https://lists.example.org/a%2Fb/index.json",
    "https://lists.example.org/index.json?v=1&kind=tracker",
    "https://lists.example.org/list+of+lists.json",
    "https://lists.example.org/index.json#anchor",
    "https://lists.example.org:8443/index.json",
  ]) {
    const { received, shown: displayed } = pressAndDecode(storeOfferHref(publisher));
    assert.equal(received, publisher, `${publisher} did not survive the hop`);
    assert.equal(displayed, publisher, `${publisher} was not shown as published`);
  }
});

test("storeOfferHref refuses its own output — the shape of the defect, as a throw", () => {
  // The composition is idempotent-looking and is not: calling it twice produces a string that is
  // still a plausible href and is no longer a URL. There is no second caller today, and this is what
  // stops the third one from being written.
  const once = storeOfferHref(layoutValue("services.rawUrl"));
  assert.throws(() => storeOfferHref(once), /already an offer href/);
  assert.throws(() => storeOfferHref("/services/"), /not an absolute URL/);
  assert.throws(() => storeOfferHref(""), /expected a published index URL/);
});
