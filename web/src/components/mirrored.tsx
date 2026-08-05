import mirror from "../generated/mirror.json";
import type { MirrorData, MirrorEntry } from "../generated/types";
import { DocLayout } from "./docs";

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
      <div dangerouslySetInnerHTML={{ __html: page.html }} />
    </DocLayout>
  );
}
