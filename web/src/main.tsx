// The site's only entry, for both halves of the build.
//
// # Why one file does both
//
// `scripts/prerender.mjs` imports `renderRoute` out of the server build and writes one real HTML
// file per route; the browser loads the client build, matches the URL it was served at, and hydrates
// the same tree. Two entry files would be two answers to "which component is this URL", and the one
// that drifts is always the one nobody runs locally.
//
// # Why the page modules are not imported by name
//
// `import.meta.glob` resolves them. That is what makes `routes.ts` (data, one owner) and
// `pages/**` (components, another owner) two files nobody has to edit at the same time: adding a
// route is one entry here, adding a page is one file there, and
// `web/tests/routes-are-real-files.test.mjs` asserts the two sets are the same set in both
// directions. A missing module is reported by name, with the entry that wanted it, rather than as a
// resolution error four frames into a bundler.
import { StrictMode } from "react";
import type { ComponentType, ReactNode } from "react";
import { hydrateRoot } from "react-dom/client";
import { routes } from "./routes";
import type { RouteEntry } from "./routes";
import "./styles.css";

// Re-exported so `scripts/prerender.mjs` and `scripts/sitemap.mjs` read the same objects this
// renders, out of the same build, rather than a second copy parsed from the TypeScript source.
export { routes, outputPath } from "./routes";

/** Every page module, eagerly — the server renders all of them and the browser hydrates one. */
const pages = import.meta.glob<{ default: ComponentType }>("./pages/**/*.tsx", { eager: true });

/**
 * The optional site shell: chrome around every page.
 *
 * Resolved through a glob rather than imported, for the same reason the pages are. The shell is a
 * component — header, footer, and the documentation layout for `/docs/**` — and it belongs to
 * whoever writes components. Until it exists a page renders bare, which is a legitimate build over
 * a tree that is still being written rather than a broken one.
 */
const shells = import.meta.glob<{ default: ComponentType<{ route: RouteEntry; children: ReactNode }> }>(
  "./components/Shell.tsx",
  { eager: true },
);

/** The page module for a route, or `undefined` when nobody has written it yet. */
export function pageFor(route: RouteEntry): ComponentType | undefined {
  if (route.file === null) return undefined;
  return pages[`./pages/${route.file}`]?.default;
}

/** The whole tree for one route, or `null` when its module does not exist. */
export function renderRoute(path: string): ReactNode | null {
  const route = routes.find((entry) => entry.path === path);
  if (!route) return null;
  const Page = pageFor(route);
  if (!Page) return null;
  const Shell = shells["./components/Shell.tsx"]?.default;
  const body = <Page />;
  return (
    <StrictMode>{Shell ? <Shell route={route}>{body}</Shell> : body}</StrictMode>
  );
}

/**
 * Which route was served at this URL.
 *
 * Trailing slashes are normalised in one direction only — `/docs` and `/docs/` are the same page,
 * and the table spells whichever one the emitted file lives at. A URL that matches nothing is
 * `/404`, which is a real prerendered page rather than a client-side fallback: Caddy serves it with
 * status 404, so a broken link stays a broken link to everything that reads status codes.
 */
export function routeAt(pathname: string): RouteEntry | undefined {
  const wanted = pathname.replace(/\/+$/, "") || "/";
  return routes.find((entry) => (entry.path.replace(/\/+$/, "") || "/") === wanted);
}

// The browser half. Guarded rather than split into a second file: under the SSR build `document` is
// undefined and this module is imported purely for its exports.
if (typeof document !== "undefined") {
  const route = routeAt(window.location.pathname);
  const tree = route ? renderRoute(route.path) : null;
  const root = document.getElementById("root");
  if (tree && root) hydrateRoot(root, tree);
}
