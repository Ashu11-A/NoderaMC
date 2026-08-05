// The one URL two frontends and a build script have to spell the same way.
//
// `/add-store?url=<encoded>` is the https hop that turns a click into a `nodera://tracker-store`
// intent — GitHub's markdown sanitiser strips any href whose scheme is not http/https/mailto, so the
// README's button cannot invoke the scheme itself and the page does it instead.
//
// It is a function rather than a template written at each call site because there are three of them
// and they live in three different worlds: the site's service table (React), the site's service
// generator (plain Node), and the root README's badge — which `web/tests/add-store.test.mjs` holds
// to `layout.properties`'s `services.rawUrl` through this same function. An address assembled
// independently in three places is three chances to assemble it differently, which is the argument
// `layout.properties` already makes about `services.rawUrl` itself.
//
// # Why this file is JavaScript with a declaration beside it
//
// `web/scripts/build-services.mjs` runs under plain Node, which does not read TypeScript. One copied
// line would be exactly the second speller this function exists to prevent, so the source is `.mjs`
// and the types live in `store-link.d.mts`.

/**
 * The site path that offers a publisher's service index to the app.
 *
 * @param {string} indexUrl the published index, as its publisher spells it
 * @returns {string}
 */
export function storeOfferHref(indexUrl) {
  return `/add-store?url=${encodeURIComponent(indexUrl)}`;
}
