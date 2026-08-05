// The one URL two frontends and a build script have to spell the same way.
//
// `/add-store?url=<encoded>` is the https hop that turns a click into a `nodera://tracker-store`
// intent — GitHub's markdown sanitiser strips any href whose scheme is not http/https/mailto, so the
// README's button cannot invoke the scheme itself and the page does it instead.
//
// It is a function rather than a template written at each call site because there are two of them
// and they live in two different worlds: the site's service table (React) and the root README's
// badge — which `web/tests/add-store.test.mjs` holds to `layout.properties`'s `services.rawUrl`
// through this same function. An address assembled independently in several places is several
// chances to assemble it differently, which is the argument `layout.properties` already makes about
// `services.rawUrl` itself.
//
// # Why this file is JavaScript with a declaration beside it
//
// `web/scripts/build-services.mjs` runs under plain Node, which does not read TypeScript. One copied
// line would be exactly the second speller this function exists to prevent, so the source is `.mjs`
// and the types live in `store-link.d.mts`.
//
// # Why it refuses anything that is not an absolute URL
//
// Because it was called twice on the same value and nobody found out from the code. The site's
// service generator wrote a FINISHED href into `storeUrl`, the service table called this on that
// href a second time, and the button shipped pointing at
//
//   /add-store?url=%2Fadd-store%3Furl%3Dhttps%253A%252F%252Fraw…
//
// which the deep-link page correctly refused with "The address in this link is not a valid URL".
// Both halves were individually right; the contract between them was not written down anywhere a
// build could check. So it is written down here, as a throw: the argument is **a publisher's index
// URL, exactly as they publish it** — never a path, never a query string, and never something this
// function has already produced. A second call is now a build failure that names itself, on a line
// somebody can act on, rather than a button that looks correct and is refused after the click.

/**
 * The site path that offers a publisher's service index to the app.
 *
 * @param {string} indexUrl the published index, as its publisher spells it — an absolute URL
 * @returns {string} `/add-store?url=<the argument, encoded exactly once>`
 * @throws {TypeError} if the argument is not an absolute URL
 */
export function storeOfferHref(indexUrl) {
  if (typeof indexUrl !== "string" || indexUrl.length === 0) {
    throw new TypeError(`storeOfferHref: expected a published index URL, got ${typeof indexUrl}`);
  }
  // Named before the general check, because this is the mistake that actually happened and
  // "not an absolute URL" would send somebody looking at the publisher's list instead of at the
  // call site that had already composed the href.
  if (indexUrl.startsWith("/add-store")) {
    throw new TypeError(
      `storeOfferHref: "${indexUrl.slice(0, 48)}…" is already an offer href. This function takes ` +
        "the publisher's index URL and composes the href once; calling it on its own output " +
        "double-encodes the address and /add-store refuses it as malformed.",
    );
  }
  // `URL` rather than a regex: the one question being asked is "would the deep-link page's own
  // `new URL()` see an address here", and that page decides with the same constructor.
  try {
    // eslint-disable-next-line no-new
    new URL(indexUrl);
  } catch {
    throw new TypeError(
      `storeOfferHref: "${indexUrl}" is not an absolute URL. A service list is published at an ` +
        "address with a scheme; a relative path cannot be offered to the app.",
    );
  }
  // The RAW string is encoded, never the re-serialised `URL`. `/add-store` shows a visitor the
  // address exactly as its publisher wrote it (rule R6), and a repaired string is a different
  // decision from the one they are being asked to make.
  return `/add-store?url=${encodeURIComponent(indexUrl)}`;
}
