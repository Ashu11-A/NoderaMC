import type { ReactNode } from "react";
import { breadcrumb, command } from "../destinations";
import { Callout } from "./primitives";

/**
 * The five components the prose is allowed to use to name something outside itself.
 *
 * They all share one property: they turn a fact that lives somewhere else into text, and they throw
 * when that fact has moved. A page that names a screen the launcher renamed, or cites a task that
 * does not exist, is the failure this repository writes tests against everywhere else; the site is
 * not exempt just because its output is prose.
 */

/**
 * A place in the companion app: `<Ui to="tracker-stores" />` → **Settings → Tracker stores**.
 *
 * The arrow is rendered rather than typed so the separator is one decision, and the labels come
 * from `destinations.ts` so a rename is one edit. An unknown key throws, which fails the build.
 */
export function Ui(props: { to: string }) {
  const trail = breadcrumb(props.to);
  return (
    <strong className="font-medium text-text">
      {trail.map((label, i) => (
        <span key={label}>
          {i > 0 ? <span className="text-faint"> → </span> : null}
          {label}
        </span>
      ))}
    </strong>
  );
}

/** An in-game command: `<Cmd to="nodera-selftest" />` → `/nodera selftest`. */
export function Cmd(props: { to: string }) {
  return (
    <code className="rounded-sm bg-surface-2 px-1.5 py-0.5 font-mono text-sm text-text">
      {command(props.to)}
    </code>
  );
}

/** A literal from the system — a wire tag, a config key, a status word the app actually prints. */
export function Tag(props: { children: string }) {
  return <code className="font-mono text-sm text-brand-3">{props.children}</code>;
}

/**
 * The declared stub.
 *
 * A route with a real title, a real lede, and an honest statement that the rest is not written.
 * The alternative is worse in both directions: a page that does not exist leaves a dead link in
 * every place the subject comes up, and a page written confidently about a lane that is still
 * moving is a page that lies fluently.
 *
 * It names the owning **task file**, not a GitHub issue. There is no issue for "write this page",
 * and an invented issue number is precisely the kind of unbacked reference the rest of this
 * repository refuses to ship.
 */
export function Unwritten(props: { task: string; taskPath: string; route: string; children?: ReactNode }) {
  return (
    <Callout type="warning" title="This page is not written yet" marker={props.route}>
      <p>
        The behaviour this page would describe is owned by <strong>{props.task}</strong>, and it is
        still in progress. Writing the page now would mean writing it twice, and writing it
        confidently would mean guessing.
      </p>
      {props.children}
      <p>
        The specification, its acceptance criteria and its current status are in{" "}
        <a
          className="text-brand-1 underline decoration-line underline-offset-2 hover:decoration-brand-1"
          href={`https://github.com/Ashu11-A/NoderaMC/blob/main/${props.taskPath}`}
          rel="noopener noreferrer"
        >
          {props.taskPath}
        </a>
        . <a
          className="text-brand-1 underline decoration-line underline-offset-2 hover:decoration-brand-1"
          href="/status"
        >
          Where the build stands
        </a>{" "}
        lists it with everything else that is open.
      </p>
    </Callout>
  );
}

/**
 * A marker where a screenshot belongs and none exists.
 *
 * It renders as a visible, quiet gap rather than as nothing, because the honest failure mode of
 * "we will add the picture later" is that nobody can tell which pages were supposed to have one. A
 * build step counts these and prints the list; it is a report, not a failure.
 */
export function NeedsScreenshot(props: { of: string }) {
  return (
    <div
      className="my-6 rounded-md border border-dashed border-line px-5 py-4 text-sm text-faint"
      data-needs-screenshot={props.of}
    >
      A screenshot of {props.of} belongs here. It lands with the launcher redesign — a capture taken
      against today's launcher would be wrong within the month.
    </div>
  );
}
