// `sitemap.xml` and `robots.txt`, from the route table.
//
// Three kinds of route are excluded, and each exclusion is a decision rather than housekeeping:
//
//   * `/404` — a page whose whole job is to be served with status 404. Listing it invites a crawler
//     to index the thing it sees when it is lost.
//   * `/add-store` — `noindex` in its own markup since it was written. It is a deep-link hop that
//     only means anything with a `?url=` parameter, so an indexed copy without one is a page that
//     tells every visitor the link did not carry an address.
//   * every stub — a route with a real title, a real lede and no invented content. Somebody arriving
//     on one from a search result has been promised an answer that is not there.
//
// The rule is read off `sitemap: false` in the table rather than restated here, so a route added
// with the wrong flag is wrong in exactly one place.
import { writeFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { distDirectory, siteDirectory, SITE_ORIGIN } from "../tests/layout.mjs";

const { routes } = await import(path.join(siteDirectory, ".ssr/main.js"));

const listed = routes.filter((route) => route.sitemap);
if (listed.length === 0) throw new Error("sitemap: no route is listed; the whole site is noindex");

const urls = listed
  .map((route) => `  <url><loc>${SITE_ORIGIN}${route.path}</loc></url>`)
  .join("\n");

writeFileSync(
  path.join(distDirectory, "sitemap.xml"),
  `<?xml version="1.0" encoding="UTF-8"?>\n` +
    `<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`,
);

// No `Disallow`. The excluded routes above carry `noindex` in their own `<head>`, which is the
// instruction that actually keeps a page out of an index — `robots.txt` only asks a crawler not to
// FETCH it, and a page that is never fetched can never be told it is noindex.
writeFileSync(
  path.join(distDirectory, "robots.txt"),
  `User-agent: *\nAllow: /\nSitemap: ${SITE_ORIGIN}/sitemap.xml\n`,
);

const emitted = readdirSync(distDirectory).length;
console.log(`sitemap: ${listed.length} of ${routes.length} routes listed, ${emitted} entries in dist`);
