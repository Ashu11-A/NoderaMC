import mirror from "../generated/mirror.json";
import type { MirrorData, MirrorEntry } from "../generated/types";
import { DocLayout } from "./docs";
import { SCROLLER_CLASS } from "./primitives";

/**
 * A page that is a file in this repository, rendered.
 *
 * Nine pages on this site are not written for the site at all — the two operator guides, the two
 * service references, the wire protocol, the engine SDK, the compatibility contract, the
 * service-list format and the roadmap. They were already written for outsiders and they already have
 * to be correct. Mirroring them is the only arrangement in which this site cannot drift from them,
 * and each page says plainly that it is a mirror, so a reader who spots a mistake goes to the file
 * rather than to a web form.
 *
 * Which files are mirrored is editorial and lives in `web/content/mirror.json`; the mirroring is a
 * build step. That build step is a reader: it strips the agent-instruction comments from its
 * *output* only, and the suite asserts every source file's digest is byte-identical before and after
 * a full build. A link in a mirrored file to a path that does not resolve on disk fails the build
 * rather than rendering as a dead URL — the same rule `scripts/check-docs.sh` already enforces.
 */

const entries = (mirror as unknown as MirrorData).entries;

/** The mirrored page for a route. Throws during prerender, naming the route, rather than blank. */
export function mirrored(route: string): MirrorEntry {
  const page = entries.find((entry) => entry.route === route);
  if (!page) {
    throw new Error(
      `mirror: nothing was mirrored for "${route}". Every route rendered by <Mirrored> needs an ` +
        `entry in web/content/mirror.json, and every entry has to have produced output.`,
    );
  }
  return page;
}

/**
 * Every `<table>` in a mirrored document, in a scroller of its own.
 *
 * These are the widest things on the site by a wide margin — the wire protocol's tag table needs
 * 1518px and the reading column is 752 — and without this they push the whole document sideways:
 * measured, `/docs/develop/wire-protocol` scrolled to **2002px inside a 1920 viewport**, and the
 * same page needed 1846px at 1000. Twenty-seven of the site's tables are in these nine files.
 *
 * It is a string rewrite because a mirrored page is HTML, not elements: the mirror is a build step
 * that reads files in `docs/` and this component injects what it produced. The equivalent for prose
 * written as MDX is the component map in `docs.tsx`, and both write the same class list because
 * `SCROLLER_CLASS` is one constant — a second copy would be a second answer to how wide content
 * behaves, in the two places a reader would compare.
 *
 * `<table>` does not nest in the output of a markdown pipeline, so pairing opens with closes by
 * count is exact rather than a parse that happens to work. `<table` is matched with its delimiter so
 * a future `<tablefoo>` element cannot be caught by the prefix.
 */
export function withScrollingTables(html: string): string {
  return html
    .replace(/<table(?=[\s/>])/g, `<div class="${SCROLLER_CLASS}"><table`)
    .replace(/<\/table>/g, "</table></div>");
}

export function Mirrored(props: { route: string }) {
  const page = mirrored(props.route);
  return (
    <DocLayout
      path={props.route}
      toc={page.toc}
      source={page.source}
      sourceEditUrl={page.editUrl}
      sourceIso={page.updatedAt}
    >
      <div className="min-w-0" dangerouslySetInnerHTML={{ __html: withScrollingTables(page.html) }} />
    </DocLayout>
  );
}
