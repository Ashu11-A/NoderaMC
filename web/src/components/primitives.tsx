import type { ReactNode } from "react";

/**
 * The small shapes the rest of the site is built out of.
 *
 * Every one of them is styled with utility classes over the launcher's tokens, so a palette change
 * in `app/ui/src/styles.css` moves this site too and there is no second copy of the colour to
 * forget. There are no class selectors anywhere on this site for the same reason there are none
 * left in the launcher: a class in the markup cannot drift from a rule in a stylesheet that does
 * not exist.
 */

type Tone = "note" | "tip" | "warning" | "danger" | "details";

const TONE: Record<Tone, { border: string; label: string; text: string }> = {
  note: { border: "border-l-line", label: "Note", text: "text-dim" },
  tip: { border: "border-l-up", label: "Tip", text: "text-up" },
  warning: { border: "border-l-warn", label: "Careful", text: "text-warn" },
  danger: { border: "border-l-danger", label: "This can lose data", text: "text-danger" },
  details: { border: "border-l-line", label: "Detail", text: "text-dim" },
};

/**
 * The admonition. `:::note`, `:::tip`, `:::warning`, `:::danger` and `:::details` in the prose all
 * arrive here, using the identical directive syntax `docs/` already uses so a paragraph can move
 * between the two without being rewritten.
 *
 * `danger` is reserved for "this can lose your world or your privacy". If it is used for anything
 * else it stops meaning anything, and the one place it needs to be believed is the place it will
 * not be.
 */
export function Callout(props: { type?: Tone; title?: string; children: ReactNode }) {
  const tone = TONE[props.type ?? "note"];
  return (
    <aside
      className={`my-6 rounded-md border-l-2 bg-surface px-5 py-4 text-body ${tone.border}`}
      role="note"
    >
      <p className={`mb-1 font-medium ${tone.text}`}>{props.title ?? tone.label}</p>
      <div className="text-dim [&>p]:mb-2 [&>p:last-child]:mb-0">{props.children}</div>
    </aside>
  );
}

/** A short status word. Used for release channels, task status and the sidebar's stub marker. */
export function Badge(props: { tone?: "brand" | "up" | "warn" | "danger" | "muted"; children: ReactNode }) {
  const tone = props.tone ?? "muted";
  const colour =
    tone === "brand"
      ? "border-brand-1 text-brand-1"
      : tone === "up"
        ? "border-up text-up"
        : tone === "warn"
          ? "border-warn text-warn"
          : tone === "danger"
            ? "border-danger text-danger"
            : "border-line text-faint";
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2 py-px align-middle text-2xs font-medium tracking-wide uppercase ${colour}`}
    >
      {props.children}
    </span>
  );
}

/**
 * Tabs, without JavaScript.
 *
 * Radio inputs and sibling selectors, so the whole thing works in the prerendered HTML with the
 * bundle blocked. Used on the install page, where the three platforms are the same page read three
 * ways and a reader on a broken network still has to be able to read the other two.
 */
export function Tabs(props: { name: string; tabs: { id: string; label: string; body: ReactNode }[] }) {
  return (
    <div className="my-6 rounded-lg border border-line bg-surface">
      <div className="flex flex-wrap gap-1 border-b border-line-soft p-2">
        {props.tabs.map((tab, i) => (
          <span key={tab.id} className="contents">
            <input
              className="peer/tab sr-only"
              type="radio"
              name={props.name}
              id={`${props.name}-${tab.id}`}
              defaultChecked={i === 0}
            />
            <label
              className="cursor-pointer rounded-sm px-3 py-1.5 text-sm text-dim hover:bg-surface-hover hover:text-text peer-checked/tab:bg-surface-2 peer-checked/tab:text-text"
              htmlFor={`${props.name}-${tab.id}`}
            >
              {tab.label}
            </label>
          </span>
        ))}
      </div>
      <div className="grid">
        {props.tabs.map((tab) => (
          <section
            key={tab.id}
            className="col-start-1 row-start-1 px-5 py-4 text-body"
            aria-labelledby={`${props.name}-${tab.id}`}
          >
            <p className="mb-3 font-medium text-text">{tab.label}</p>
            {tab.body}
          </section>
        ))}
      </div>
    </div>
  );
}

/**
 * A picture with a caption that carries the meaning.
 *
 * There are no screenshots on this site yet and that is a decision, not an omission: the launcher
 * is being redesigned as this ships, so a screenshot taken today is wrong within the month. What
 * this renders is authored SVG, drawn in `currentColor` and tokens so it is correct in both themes
 * without a second file. The figure itself is `aria-hidden`; the caption is the accessible content,
 * which is the right way round — a diagram that has to be described in an `alt` attribute is a
 * diagram whose caption should have said it.
 */
export function Figure(props: { caption: ReactNode; children: ReactNode }) {
  return (
    <figure className="my-8">
      <div className="rounded-lg border border-line-soft bg-surface p-6" aria-hidden="true">
        {props.children}
      </div>
      <figcaption className="mt-3 text-sm text-dim">{props.caption}</figcaption>
    </figure>
  );
}

/** A directory listing, monospaced, for the handful of places the prose names real paths. */
export function FileTree(props: { children: string }) {
  return (
    <pre className="my-6 overflow-x-auto rounded-md border border-line-soft bg-surface px-5 py-4 font-mono text-sm text-dim">
      {props.children}
    </pre>
  );
}

/** A short inline code span. Written out so the prose never reaches for a colour of its own. */
export function Code(props: { children: ReactNode }) {
  return (
    <code className="rounded-sm bg-surface-2 px-1.5 py-0.5 font-mono text-sm text-text">
      {props.children}
    </code>
  );
}
