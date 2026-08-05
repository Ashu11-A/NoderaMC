import type { CSSProperties } from "react";
import { openLimitations, status } from "../components/data";
import { PageLayout } from "../components/docs";
import { LimitationTable, StatusTables, TaskTotals, TestTotals } from "../components/status";

export default function Page() {
  const open = openLimitations();
  return (
    <PageLayout path="/status">
      <>
        <section className="mt-12" style={{ maxWidth: "var(--prose-measure)" }}>
          <p className="text-body leading-7 text-dim">
            This page exists because the honest version of "how far along is it" is not a percentage
            on a marketing page. It is a list of things that do not work yet, each with the name of
            the task that owns it and the exact test that will close it. Every number below is
            generated at build time from the same files the project's own gate reads; if this page
            and the repository disagree, the build fails rather than the page rounding up.
          </p>
        </section>

        <section className="mt-12">
          <h2 className="display-type text-2xl font-bold text-text">Tasks, by category</h2>
          {/* The table and the caveat that belongs with it are data, not prose, so neither is bound
              by the reading measure — they are bound to each other. Side by side on a wide display
              the count and the sentence about why there is no percentage are read together; in one
              752px column the sentence sits below the table on the screen with the most room. */}
          {/* No top margin of its own: both children already carry one, and grid item margins do
              not collapse, so `mt-6` here would read as 48px under the heading. */}
          <div className="card-grid items-start gap-6" style={{ "--card-min": "26rem" } as CSSProperties}>
            <StatusTables />
            <TaskTotals />
          </div>
        </section>

        <section className="mt-14">
          <h2 className="display-type text-2xl font-bold text-text">Tests</h2>
          <p className="mt-3 text-body leading-7 text-dim" style={{ maxWidth: "var(--prose-measure)" }}>
            These are the counts continuous integration measured on the last run of the gate, not a
            figure anyone typed. Java unit tests, both Rust workspaces, the Tauri shell and the
            frontend all report into the same total.
          </p>
          <TestTotals />
        </section>

        <section className="mt-14">
          <h2 className="display-type text-2xl font-bold text-text">
            {open.length} things that do not work yet
          </h2>
          <p className="mt-3 text-body leading-7 text-dim" style={{ maxWidth: "var(--prose-measure)" }}>
            Every category of the system keeps a register of its own limitations, and a row leaves it
            only when the test named in that row goes green. <strong className="font-medium text-text">Open</strong> means
            the work has not been done. <strong className="font-medium text-text">Retiring</strong> means the mechanism is
            built and tested and the row is waiting on something that is not code — a live run across
            the open internet, or a credential that cannot live in a public repository. There are{" "}
            {status.registerCount} registers.
          </p>
          <LimitationTable rows={open} emptyNote="Every register is empty. That would be a first; check the build." />
        </section>

        <section className="mt-14">
          <h2 className="display-type text-2xl font-bold text-text">Constraints, rather than gaps</h2>
          <p className="mt-3 text-body leading-7 text-dim" style={{ maxWidth: "var(--prose-measure)" }}>
            Each register also carries a shorter list of facts that are not going to change — the
            envelope the system is engineered inside rather than a backlog. They are here so that a
            reader can tell the two apart, which is the whole reason they are kept in separate
            sections upstream.
          </p>
          <LimitationTable
            rows={status.envelope}
            emptyNote="No category declares an envelope constraint at the moment."
          />
        </section>
      </>
    </PageLayout>
  );
}
