import type { ReactNode } from "react";
import { neighbours, sidebar } from "../nav/sidebar";
import { SiteFooter, SiteHeader } from "./chrome";

/**
 * The documentation frame: one sidebar for the whole tree, a table of contents, and the two links
 * that say what comes before and after.
 *
 * Reading order lives in `nav/sidebar.ts` and nowhere else, so the sidebar and the prev/next links
 * cannot disagree about it. That is not a hypothetical: two orderings is exactly how a "next" link
 * ends up pointing at a page the sidebar puts three sections earlier.
 */

export interface TocEntry {
  readonly depth: 2 | 3;
  readonly id: string;
  readonly text: string;
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
export function EditLink(props: { source: string }) {
  return (
    <a
      className="text-dim hover:text-text"
      href={`https://github.com/Ashu11-A/NoderaMC/edit/main/${props.source}`}
      rel="noopener noreferrer"
    >
      Edit {props.source}
    </a>
  );
}

/**
 * When the source file last changed, from `git log`, not from the clone date.
 *
 * The date is rendered absolute in the prerendered HTML. A relative one ("3 weeks ago") computed at
 * build time is a lie that gets worse every day the site is not rebuilt.
 */
export function LastUpdated(props: { iso?: string }) {
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
 * It exists because a reader who finds a mistake here should be sent to the file that is wrong, not
 * to this page. The mirror is a reader: it never edits its sources, and a build asserts that every
 * source file's digest is unchanged after a full build.
 */
export function MirroredNotice(props: { source: string; iso?: string }) {
  return (
    <p className="mt-4 mb-10 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border border-line-soft bg-surface px-4 py-3 text-sm text-dim">
      <span>
        Mirrored from{" "}
        <a
          className="font-mono text-brand-3 hover:underline"
          href={`https://github.com/Ashu11-A/NoderaMC/blob/main/${props.source}`}
          rel="noopener noreferrer"
        >
          {props.source}
        </a>
        .
      </span>
      <LastUpdated iso={props.iso} />
      <EditLink source={props.source} />
    </p>
  );
}

/**
 * The reading column.
 *
 * Every typographic rule for prose lives here as descendant utilities on one element, so the MDX
 * pages and the mirrored HTML get identical treatment without either of them carrying classes.
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
        "[&_ul]:my-4 [&_ul]:list-disc [&_ul]:pl-6 [&_ol]:my-4 [&_ol]:list-decimal [&_ol]:pl-6 [&_li]:my-1.5 [&_li]:leading-7",
        "[&_code]:rounded-sm [&_code]:bg-surface-2 [&_code]:px-1.5 [&_code]:py-0.5 [&_code]:font-mono [&_code]:text-sm [&_code]:text-text",
        "[&_pre]:my-6 [&_pre]:overflow-x-auto [&_pre]:rounded-md [&_pre]:border [&_pre]:border-line-soft [&_pre]:bg-surface [&_pre]:p-4 [&_pre]:text-sm",
        "[&_pre_code]:bg-transparent [&_pre_code]:p-0 [&_pre_code]:text-dim",
        "[&_blockquote]:my-6 [&_blockquote]:border-l-2 [&_blockquote]:border-line [&_blockquote]:pl-5 [&_blockquote]:text-dim",
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
 * `path` is passed rather than read from the router: this site is prerendered to one real HTML file
 * per route, and a component that has to ask a router where it is cannot be rendered without one.
 */
export function DocLayout(props: {
  path: string;
  title: string;
  lede: string;
  toc?: readonly TocEntry[];
  source?: string;
  sourceIso?: string;
  children: ReactNode;
}) {
  return (
    <div className="min-h-screen bg-bg">
      <SiteHeader current={props.path} />
      <div className="page-canvas grid gap-12 py-12 lg:grid-cols-[240px_minmax(0,1fr)] xl:grid-cols-[240px_minmax(0,1fr)_200px]">
        <aside className="lg:sticky lg:top-24 lg:self-start">
          <Sidebar current={props.path} />
        </aside>
        <main className="min-w-0" style={{ maxWidth: "var(--prose-measure)" }}>
          <h1 className="display-type text-3xl font-bold text-text sm:text-4xl">{props.title}</h1>
          <p className="mt-4 text-lg leading-8 text-dim">{props.lede}</p>
          {props.source ? <MirroredNotice source={props.source} iso={props.sourceIso} /> : null}
          <Prose>{props.children}</Prose>
          <PrevNext current={props.path} />
        </main>
        <aside className="hidden xl:sticky xl:top-24 xl:block xl:self-start">
          <Toc entries={props.toc} />
        </aside>
      </div>
      <SiteFooter />
    </div>
  );
}

/** The frame for the pages that are not documentation: the landing page, download, status, privacy. */
export function PageLayout(props: { path: string; landing?: boolean; children: ReactNode }) {
  return (
    <div className="min-h-screen bg-bg">
      <SiteHeader landing={props.landing} current={props.path} />
      <main>{props.children}</main>
      <SiteFooter />
    </div>
  );
}
