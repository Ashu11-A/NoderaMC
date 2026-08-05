import type { ReactNode } from "react";
import { neighbours, sidebar } from "../nav/sidebar";
import { routes, type RouteEntry } from "../routes";

/**
 * The two page frames, and the documentation furniture inside one of them.
 *
 * Neither renders the header or the footer: `Shell.tsx` does that once, around everything. What
 * these decide is the shape of a page's own body — a reading column with a sidebar and a table of
 * contents, or a plain canvas.
 *
 * Both take a `path` and read the title and the lede out of `routes.ts`. Nothing on a page spells
 * its own heading, because two answers to "what is this page called" is how an `<h1>` and a
 * `<title>` end up disagreeing, and only one of them is what a search result shows.
 */

export interface TocEntry {
  readonly depth: 2 | 3;
  readonly id: string;
  readonly text: string;
}

/** The route entry for a path. Throws during prerender, naming the path, rather than rendering blank. */
export function routeFor(path: string): RouteEntry {
  const entry = routes.find((route) => route.path === path);
  if (!entry) {
    throw new Error(
      `layout: "${path}" is not in web/src/routes.ts. A page's layout path must be the route's own ` +
        `path — that is where its title and description come from.`,
    );
  }
  return entry;
}

export function PageHeader(props: { path: string }) {
  const route = routeFor(props.path);
  return (
    <>
      <h1 className="display-type text-3xl font-bold text-text sm:text-4xl">{route.title}</h1>
      <p className="mt-4 text-lg leading-8 text-dim">{route.description}</p>
    </>
  );
}

export function Sidebar(props: { current: string }) {
  return (
    <nav className="text-sm" aria-label="Documentation">
      {sidebar.map((group) => (
        <div key={group.title} className="mb-7">
          <p className="mb-2 text-2xs font-medium tracking-wide text-faint uppercase">{group.title}</p>
          <ul className="flex flex-col gap-px">
            {group.links.map((link) => {
              const active = link.to === props.current;
              return (
                <li key={link.to}>
                  <a
                    href={link.to}
                    aria-current={active ? "page" : undefined}
                    className={`flex items-center justify-between gap-2 rounded-sm px-2 py-1.5 hover:bg-surface-hover ${
                      active ? "bg-surface-2 text-text" : "text-dim"
                    }`}
                  >
                    <span>{link.title}</span>
                    {link.stub ? (
                      <span className="shrink-0 text-2xs tracking-wide text-faint uppercase">unwritten</span>
                    ) : null}
                  </a>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </nav>
  );
}

export function Toc(props: { entries?: readonly TocEntry[] }) {
  if (!props.entries || props.entries.length === 0) return null;
  return (
    <nav className="text-sm" aria-label="On this page">
      <p className="mb-2 text-2xs font-medium tracking-wide text-faint uppercase">On this page</p>
      <ul className="flex flex-col gap-1 border-l border-line-soft">
        {props.entries.map((entry) => (
          <li key={entry.id}>
            <a
              href={`#${entry.id}`}
              className={`-ml-px block border-l border-transparent py-0.5 text-dim hover:border-brand-1 hover:text-text ${
                entry.depth === 3 ? "pl-6" : "pl-3"
              }`}
            >
              {entry.text}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}

/**
 * The two links that say what comes before and after.
 *
 * Reading order is the flattened sidebar and nothing else, so these cannot disagree with it. They
 * step over stubs, so a reader following "next" through the documentation is never handed a page
 * with nothing on it.
 */
export function PrevNext(props: { current: string }) {
  const { prev, next } = neighbours(props.current);
  if (!prev && !next) return null;
  return (
    <nav className="mt-16 grid gap-3 border-t border-line-soft pt-8 sm:grid-cols-2" aria-label="Nearby pages">
      {prev ? (
        <a href={prev.to} className="rounded-md border border-line-soft p-4 hover:border-brand-1">
          <span className="block text-2xs tracking-wide text-faint uppercase">Previous</span>
          <span className="mt-1 block text-body text-text">{prev.title}</span>
        </a>
      ) : (
        <span />
      )}
      {next ? (
        <a href={next.to} className="rounded-md border border-line-soft p-4 text-right hover:border-brand-1">
          <span className="block text-2xs tracking-wide text-faint uppercase">Next</span>
          <span className="mt-1 block text-body text-text">{next.title}</span>
        </a>
      ) : null}
    </nav>
  );
}

/** A link to the file this page was written from, so a correction is one click from the page. */
export function EditLink(props: { url: string; source: string }) {
  return (
    <a className="text-dim hover:text-text" href={props.url} rel="noopener noreferrer">
      Edit {props.source}
    </a>
  );
}

/**
 * When the source file last changed, from `git log`, not from the clone date.
 *
 * The date is rendered absolute in the prerendered HTML. A relative one computed at build time is a
 * lie that gets worse every day the site is not rebuilt.
 */
export function LastUpdated(props: { iso?: string | null }) {
  if (!props.iso) return null;
  const date = new Date(props.iso);
  if (Number.isNaN(date.getTime())) return null;
  return (
    <span className="text-faint">
      Source last changed{" "}
      <time dateTime={props.iso}>
        {date.toLocaleDateString("en-GB", { year: "numeric", month: "long", day: "numeric" })}
      </time>
    </span>
  );
}

/**
 * The notice a mirrored page carries under its heading.
 *
 * It exists because a reader who finds a mistake here should be sent to the file that is wrong,
 * rather than to this page. The mirror is a reader: it never edits its sources, and the build
 * asserts every source file's digest is unchanged after a full run.
 */
export function MirroredNotice(props: { source: string; editUrl: string; iso?: string | null }) {
  return (
    <p className="mt-4 mb-10 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border border-line-soft bg-surface px-4 py-3 text-sm text-dim">
      <span>
        Mirrored from <span className="font-mono text-brand-3">{props.source}</span>.
      </span>
      <LastUpdated iso={props.iso} />
      <EditLink url={props.editUrl} source={props.source} />
    </p>
  );
}

/**
 * The reading column.
 *
 * Every typographic rule for prose lives here as descendant utilities on one element, so the MDX
 * pages and the mirrored HTML get identical treatment and neither has to carry classes of its own.
 * `--prose-measure` is the site's own token: the launcher has no reading column and would have no
 * use for one.
 */
export function Prose(props: { children: ReactNode }) {
  return (
    <div
      className={[
        "text-body text-dim",
        "[&_h2]:display-type [&_h2]:mt-14 [&_h2]:mb-4 [&_h2]:scroll-mt-24 [&_h2]:text-2xl [&_h2]:font-bold [&_h2]:text-text",
        "[&_h3]:mt-10 [&_h3]:mb-3 [&_h3]:scroll-mt-24 [&_h3]:text-lg [&_h3]:font-medium [&_h3]:text-text",
        "[&_h4]:mt-8 [&_h4]:mb-2 [&_h4]:font-medium [&_h4]:text-text",
        "[&_p]:my-4 [&_p]:leading-7",
        "[&_strong]:font-medium [&_strong]:text-text",
        "[&_a]:text-brand-1 [&_a]:underline [&_a]:decoration-line [&_a]:underline-offset-2 hover:[&_a]:decoration-brand-1",
        "[&_h2_a]:no-underline [&_h3_a]:no-underline [&_h2_a]:text-text [&_h3_a]:text-text",
        "[&_ul]:my-4 [&_ul]:list-disc [&_ul]:pl-6 [&_ol]:my-4 [&_ol]:list-decimal [&_ol]:pl-6 [&_li]:my-1.5 [&_li]:leading-7",
        "[&_code]:rounded-sm [&_code]:bg-surface-2 [&_code]:px-1.5 [&_code]:py-0.5 [&_code]:font-mono [&_code]:text-sm [&_code]:text-text",
        "[&_pre]:my-6 [&_pre]:overflow-x-auto [&_pre]:rounded-md [&_pre]:border [&_pre]:border-line-soft [&_pre]:bg-surface [&_pre]:p-4 [&_pre]:text-sm",
        "[&_pre_code]:bg-transparent [&_pre_code]:p-0",
        "[&_blockquote]:my-6 [&_blockquote]:border-l-2 [&_blockquote]:border-line [&_blockquote]:pl-5",
        "[&_hr]:my-12 [&_hr]:border-line-soft",
        "[&_table]:my-6 [&_table]:w-full [&_table]:border-collapse [&_table]:text-sm",
        "[&_th]:border-b [&_th]:border-line [&_th]:px-3 [&_th]:py-2 [&_th]:text-left [&_th]:font-medium [&_th]:text-text",
        "[&_td]:border-b [&_td]:border-line-soft [&_td]:px-3 [&_td]:py-2 [&_td]:align-top",
        "[&_img]:max-w-full [&_img]:rounded-md",
      ].join(" ")}
    >
      {props.children}
    </div>
  );
}

/**
 * The frame every page under `/docs/` renders inside.
 *
 * `path` is passed rather than read from a router: the site is prerendered to one real HTML file
 * per route and hydrated in place, so a component that has to ask a router where it is would be
 * asking a question the server render cannot answer.
 */
export function DocLayout(props: {
  path: string;
  toc?: readonly TocEntry[];
  source?: string;
  sourceEditUrl?: string;
  sourceIso?: string | null;
  children: ReactNode;
}) {
  return (
    <div className="page-canvas grid gap-12 py-12 lg:grid-cols-[240px_minmax(0,1fr)] xl:grid-cols-[240px_minmax(0,1fr)_200px]">
      <aside className="lg:sticky lg:top-24 lg:self-start">
        <Sidebar current={props.path} />
      </aside>
      <article className="min-w-0" style={{ maxWidth: "var(--prose-measure)" }}>
        <PageHeader path={props.path} />
        {props.source && props.sourceEditUrl ? (
          <MirroredNotice source={props.source} editUrl={props.sourceEditUrl} iso={props.sourceIso} />
        ) : null}
        <Prose>{props.children}</Prose>
        <PrevNext current={props.path} />
      </article>
      <aside className="hidden xl:sticky xl:top-24 xl:block xl:self-start">
        <Toc entries={props.toc} />
      </aside>
    </div>
  );
}

/**
 * The frame for pages that are not documentation.
 *
 * `narrow` puts the body in a reading column; the download, status and services pages want the full
 * canvas for their tables and set their own measure on the paragraphs that need one.
 */
export function PageLayout(props: { path: string; narrow?: boolean; children: ReactNode }) {
  return (
    <div className="page-canvas py-14">
      <div style={props.narrow ? { maxWidth: "var(--prose-measure)" } : undefined}>
        <PageHeader path={props.path} />
      </div>
      {props.children}
    </div>
  );
}
