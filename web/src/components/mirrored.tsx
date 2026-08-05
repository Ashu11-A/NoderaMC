import mirror from "../generated/mirror.json";
import type { MirrorEntry } from "../generated/types";
import { DocLayout, type TocEntry } from "./docs";

/**
 * A page that is a file in this repository, rendered.
 *
 * Nine pages on this site are not written for the site at all — they are the operator guides, the
 * two service references, the wire protocol, the engine SDK, the compatibility contract and the
 * roadmap, which were already written for outsiders and already have to be correct. Mirroring them
 * is the only arrangement in which the site cannot drift from them, and the page says plainly that
 * it is a mirror so a reader who spots a mistake goes to the file rather than to a web form.
 *
 * The manifest of what is mirrored is editorial (`web/content/mirror.json`); the mirroring itself
 * is a build step. The build step is a reader: it strips the agent-instruction comments from its
 * *output*, and a test asserts every source file's digest is byte-identical before and after a full
 * build. A link in a mirrored file that does not resolve on disk fails the build rather than
 * rendering as a dead URL, which is the same rule `scripts/check-docs.sh` already enforces.
 */
export interface MirroredPage extends MirrorEntry {
  /** The rendered body. Sanitised at build time; the sources are files in this repository. */
  readonly html: string;
  /** ISO timestamp from `git log -1` over the source. Null when the clone has no history for it. */
  readonly updatedAt: string | null;
  readonly toc: TocEntry[];
}

const pages = mirror as unknown as Record<string, MirroredPage>;

/** Throws, naming the route, when the mirror did not produce the page this module needs. */
export function mirrored(route: string): MirroredPage {
  const page = pages[route];
  if (!page) {
    throw new Error(
      `mirror: no mirrored page for "${route}". Every route rendered by <Mirrored> must have an ` +
        `entry in web/content/mirror.json, and every entry must have produced output.`,
    );
  }
  return page;
}

export function Mirrored(props: { route: string }) {
  const page = mirrored(props.route);
  return (
    <DocLayout
      path={props.route}
      title={page.title}
      lede={page.description}
      toc={page.toc}
      source={page.source}
      sourceIso={page.updatedAt ?? undefined}
    >
      <div dangerouslySetInnerHTML={{ __html: page.html }} />
    </DocLayout>
  );
}
