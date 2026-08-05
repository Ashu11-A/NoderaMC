import type { LimitationRow } from "../generated/types";
import { status } from "./data";
import { Badge } from "./primitives";

/**
 * The status page's tables.
 *
 * Everything here is parsed out of the repository at build time: the per-category counts from the
 * roadmap's own table, the test totals from the orphan branch CI force-pushes them to, and every
 * limitation row from every category register. Nothing on this page is transcribed, and one figure
 * that could have been is deliberately absent — see `<TaskTotals />`.
 */

function Table(props: { head: string[]; children: React.ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr>
            {props.head.map((cell) => (
              <th key={cell} className="border-b border-line px-3 py-2 text-left font-medium text-text">
                {cell}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>{props.children}</tbody>
      </table>
    </div>
  );
}

export function StatusTables() {
  return (
    <Table head={["Category", "Tasks", "Done", "In progress", "Blocked"]}>
      {status.categories.map((row) => (
        <tr key={row.category}>
          <td className="border-b border-line-soft px-3 py-2 text-text">{row.category}</td>
          <td className="border-b border-line-soft px-3 py-2 text-dim tabular-nums">{row.tasks}</td>
          <td className="border-b border-line-soft px-3 py-2 text-up tabular-nums">{row.done}</td>
          <td className="border-b border-line-soft px-3 py-2 text-warn tabular-nums">{row.inProgress}</td>
          <td className="border-b border-line-soft px-3 py-2 text-dim tabular-nums">{row.blocked}</td>
        </tr>
      ))}
      <tr>
        <td className="px-3 py-2 font-medium text-text">Total</td>
        <td className="px-3 py-2 font-medium text-text tabular-nums">{status.total}</td>
        <td className="px-3 py-2 font-medium text-up tabular-nums">{status.done}</td>
        <td className="px-3 py-2" />
        <td className="px-3 py-2" />
      </tr>
    </Table>
  );
}

/**
 * The raw done-over-total, and the roadmap's own sentence about why there is no percentage here.
 *
 * The project publishes a weighted completion figure internally and states, in its own words, that
 * the weighting is not derivable from the table it sits above. So this page shows the number that
 * *is* derivable and quotes the caveat rather than inventing a percentage that would look more
 * authoritative than anything behind it.
 */
export function TaskTotals() {
  return (
    <div className="mt-6 rounded-md border border-line-soft bg-surface p-5">
      <p className="text-body text-dim">
        <strong className="font-medium text-text">
          {status.done} of {status.total} tasks
        </strong>{" "}
        are finished, as of the snapshot on {status.snapshot}. That is a raw count of task files, and
        it is the only completion figure this page will give you.
      </p>
      <p className="mt-3 text-sm leading-6 text-faint">“{status.notRecomputed}”</p>
    </div>
  );
}

/** The measured pass and fail totals, from the branch CI writes them to. Never typed by hand. */
export function TestTotals() {
  if (!status.tests) {
    return (
      <p className="mt-6 text-body text-dim">
        The measured test totals were not available to this build. That is a missing number, not a
        zero — this page will not print a count it did not read.
      </p>
    );
  }
  return (
    <div className="mt-6 flex flex-wrap gap-4">
      <div className="rounded-md border border-line-soft bg-surface px-5 py-4">
        <p className="text-2xs tracking-wide text-faint uppercase">Passing</p>
        <p className="mt-1 text-2xl font-bold text-up tabular-nums">{status.tests.passed.toLocaleString()}</p>
      </div>
      <div className="rounded-md border border-line-soft bg-surface px-5 py-4">
        <p className="text-2xs tracking-wide text-faint uppercase">Failing</p>
        <p className={`mt-1 text-2xl font-bold tabular-nums ${status.tests.failed > 0 ? "text-danger" : "text-dim"}`}>
          {status.tests.failed.toLocaleString()}
        </p>
      </div>
    </div>
  );
}

function firstSentence(text: string): { head: string; rest: string } {
  const match = text.match(/^(.{0,240}?[.;])\s(.*)$/s);
  if (!match) return { head: text, rest: "" };
  return { head: match[1], rest: match[2] };
}

function StateBadge(props: { state: LimitationRow["state"] }) {
  if (props.state === "RETIRED") return <Badge tone="up">retired</Badge>;
  if (props.state === "RETIRING") return <Badge tone="brand">retiring</Badge>;
  return <Badge tone="warn">open</Badge>;
}

/**
 * Every open row of every register, with its owning task and the test that closes it.
 *
 * Some of these cells run to several hundred words, because the register is written for whoever has
 * to do the work rather than for a reader. The first sentence is shown and the rest is one click
 * away; truncating without a way to see the rest would make the page a summary of a document whose
 * whole value is that it is not summarised.
 */
export function LimitationTable(props: { rows: readonly LimitationRow[]; emptyNote: string }) {
  if (props.rows.length === 0) return <p className="mt-6 text-body text-dim">{props.emptyNote}</p>;
  return (
    <div className="mt-6 flex flex-col gap-3">
      {props.rows.map((row) => {
        const { head, rest } = firstSentence(row.status);
        return (
          <article key={`${row.register}-${row.id}`} className="rounded-md border border-line-soft bg-surface p-5">
            <div className="flex flex-wrap items-center gap-3">
              <span className="font-mono text-sm text-brand-1">{row.id}</span>
              <StateBadge state={row.state} />
              <span className="text-2xs tracking-wide text-faint uppercase">{row.register}</span>
              {row.ownerUrl ? (
                <a
                  href={row.ownerUrl}
                  rel="noopener noreferrer"
                  className="ml-auto text-sm text-dim hover:text-text"
                >
                  {row.owner}
                </a>
              ) : (
                <span className="ml-auto text-sm text-faint">{row.owner}</span>
              )}
            </div>
            <p className="mt-3 text-body leading-7 text-text">{row.gap}</p>
            <p className="mt-3 text-sm leading-6 text-dim">
              <span className="text-faint">Closes when: </span>
              {row.exitTest}
            </p>
            <p className="mt-3 text-sm leading-6 text-dim">{head}</p>
            {rest ? (
              <details className="mt-2">
                <summary className="cursor-pointer text-sm text-faint hover:text-dim">The rest of this row</summary>
                <p className="mt-2 text-sm leading-6 text-dim">{rest}</p>
              </details>
            ) : null}
          </article>
        );
      })}
    </div>
  );
}
