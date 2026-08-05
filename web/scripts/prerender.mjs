// Turn the route table into one real HTML file per route.
//
// # Why prerender at all
//
// Because a single-page shell served for every URL means Caddy answering 200 to a request for a page
// that does not exist. This site is documentation: a broken link that reports success is a broken
// link nothing can find. One file per route keeps `try_files` strict and lets `/404` be a page that
// is served with status 404.
//
// # Why by hand rather than with a framework
//
// The site has no client-side routing to speak of — every cross-link is an ordinary anchor to a real
// file — so a router would exist only to be prerendered away. What is left is the part below, and
// doing it here is what makes "**zero inline `<script>` in every emitted page**" a property this
// build controls rather than one it hopes a framework preserved.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import path from "node:path";
import { renderToString } from "react-dom/server";
import { distDirectory, siteDirectory, SITE_ORIGIN } from "../tests/layout.mjs";

const SSR_DIR = path.join(siteDirectory, ".ssr");

const template = readFileSync(path.join(distDirectory, "index.html"), "utf8");
for (const marker of ["<!--app-head-->", "<!--app-html-->"]) {
  if (!template.includes(marker)) {
    // The template is `src/index.html` after Vite has rewritten its script and style tags. If a
    // marker is gone, every page below would be written without its content and the build would
    // still "succeed" — 27 identical empty shells, which is the shape of this failure.
    throw new Error(`prerender: dist/index.html has no ${marker}`);
  }
}

// The server entry re-exports the route table, so this reads the same objects the renderer does
// rather than a second copy parsed out of the TypeScript source.
const { renderRoute, routes, outputPath } = await import(path.join(SSR_DIR, "main.js"));

/** HTML-escape a value that is going into an attribute or a text node. */
function escape(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

/** The `<meta name="theme-color">` value: the one consumer of the generated token map. */
function themeColour() {
  const generated = path.join(siteDirectory, "src/generated/tokens.json");
  if (!existsSync(generated)) {
    throw new Error("prerender: src/generated/tokens.json is missing — run scripts/build-tokens.mjs");
  }
  const tokens = JSON.parse(readFileSync(generated, "utf8"));
  const colour = tokens.dark?.["--bg"];
  if (!colour) throw new Error("prerender: the dark theme declares no --bg");
  return colour;
}

const THEME_COLOUR = themeColour();

/**
 * Everything that goes in `<head>` for one route.
 *
 * Titles and descriptions come from the route table and from nowhere else, which is why no page
 * module renders its own `<title>`: two answers to "what is this page called" is how a `<title>` and
 * an `<h1>` end up disagreeing, and only one of them is what a search result shows.
 */
function head(route) {
  const url = `${SITE_ORIGIN}${route.path}`;
  const tags = [
    `<title>${escape(route.title)}</title>`,
    `<meta name="description" content="${escape(route.description)}" />`,
    `<link rel="canonical" href="${escape(url)}" />`,
    `<meta name="theme-color" content="${escape(THEME_COLOUR)}" />`,
    `<meta property="og:type" content="website" />`,
    `<meta property="og:title" content="${escape(route.title)}" />`,
    `<meta property="og:description" content="${escape(route.description)}" />`,
    `<meta property="og:url" content="${escape(url)}" />`,
  ];
  // A stub is a real page with a real lede and no invented content. It must not be a search result:
  // somebody arriving on one from a search engine has been promised an answer that is not there.
  if (route.stub || !route.sitemap) {
    tags.push(`<meta name="robots" content="noindex" />`);
  }
  return tags.join("\n    ");
}

let written = 0;
const unwritten = [];

for (const route of routes) {
  if (route.file === null) continue; // `/add-store` ships as reviewed bytes; see build-site.sh.
  const tree = renderRoute(route.path);
  if (tree === null) {
    unwritten.push(`${route.path} → src/pages/${route.file}`);
    continue;
  }
  const html = template
    .replace("<!--app-head-->", head(route))
    .replace("<!--app-html-->", renderToString(tree));
  const target = path.join(distDirectory, outputPath(route.path));
  mkdirSync(path.dirname(target), { recursive: true });
  writeFileSync(target, html);
  written += 1;
}

// Reported, loudly, and by name. The bijection between the route table and `src/pages` is BINDING —
// `web/tests/routes-are-real-files.test.mjs` fails on any gap — but that suite asserts over emitted
// HTML, so it can only run once there is some. Until then this count is the signal, and a silent
// skip here would be the "assumeTrue guard" failure in another language.
if (unwritten.length > 0) {
  console.warn(
    `prerender: ${unwritten.length} route(s) have no page module yet:\n  ${unwritten.join("\n  ")}`,
  );
}
console.log(`prerender: ${written} page(s) written to ${path.relative(process.cwd(), distDirectory)}`);
