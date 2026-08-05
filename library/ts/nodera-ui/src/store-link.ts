// The one URL both frontends have to spell the same way.
//
// `/add-store?url=<encoded>` is the https hop that turns a click into a `nodera://tracker-store`
// intent — GitHub's markdown sanitiser strips any href whose scheme is not http/https/mailto, so the
// README's button cannot invoke the scheme itself and the page does it instead.
//
// It is a function rather than a template written at each call site because there are two of them
// and they are in different packages: the site's service table, and the README badge that
// `web/tests/add-store.test.mjs` holds to `layout.properties`'s `services.rawUrl`. An address
// assembled independently in two places is two chances to assemble it differently, which is the
// argument `layout.properties` already makes about `services.rawUrl` itself.

/** The site path that offers a publisher's service index to the app. */
export function storeOfferHref(indexUrl: string): string {
  return `/add-store?url=${encodeURIComponent(indexUrl)}`;
}
