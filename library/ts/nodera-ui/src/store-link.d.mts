/**
 * The site path that offers a publisher's service index to the app. See `store-link.mjs`.
 *
 * `indexUrl` is the publisher's absolute URL — never a path, and never this function's own output.
 * Both throw: composing the href twice is what shipped `/add-store?url=%2Fadd-store%3Furl%3D…`.
 */
export declare function storeOfferHref(indexUrl: string): string;
