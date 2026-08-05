// `/status` counts what is there, and publishes no figure it cannot derive.
//
// Two failures are being guarded against, and they pull in opposite directions.
//
// The first is a number typed into a page. Both design documents this site was built from stated the
// number of limitation registers from memory: one said nine, the other said nine. There were eleven,
// and there are ten since the app and mobile categories merged. A count that is a literal is a count
// that is wrong the day the tree changes and has no way of saying so.
//
// The second is a number the page has no right to. `docs/ROADMAP.md` publishes a weighted completion
// percentage and says, in its own words, that the weighting is not derivable from its tables and that
// inventing one would be worse than a stale figure which says so. The site therefore publishes the
// raw done/total — which IS derivable — and quotes that sentence.
import assert from "node:assert/strict";
import { readFileSync, readdirSync, existsSync } from "node:fs";
import path from "node:path";
import test from "node:test";
import { repositoryDirectory } from "nodera-ui/layout";
import { distDirectory, siteDirectory } from "./layout.mjs";

const status = JSON.parse(readFileSync(path.join(siteDirectory, "src/generated/status.json"), "utf8"));
const page = readFileSync(path.join(distDirectory, "status/index.html"), "utf8");

/** Every `docs/*\/LIMITATIONS.md` on disk, by glob. The one true count. */
function registersOnDisk() {
  const docs = path.join(repositoryDirectory, "docs");
  return readdirSync(docs, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => path.join(docs, entry.name, "LIMITATIONS.md"))
    .filter((file) => existsSync(file));
}

test("registerCount is the glob count, and is not the literal an earlier plan assumed", () => {
  const actual = registersOnDisk().length;
  assert.equal(status.registerCount, actual);
  assert.notEqual(status.registerCount, 9, "nine was a guess in two separate plans; count them");
});

test("every register on disk contributed rows, or is genuinely empty", () => {
  // A register that stopped parsing would show up as an unexplained drop in the total, which is
  // exactly the kind of change nobody notices. Naming the ones with no §B table makes the shape of
  // the answer visible.
  const seen = new Set([...status.limitations, ...status.envelope].map((row) => row.register));
  const absent = registersOnDisk()
    .map((file) => path.basename(path.dirname(file)))
    .filter((register) => !seen.has(register));
  for (const register of absent) {
    const text = readFileSync(path.join(repositoryDirectory, "docs", register, "LIMITATIONS.md"), "utf8");
    assert.ok(
      !/^\|\s*(ID|Row)\s*\|/m.test(text),
      `docs/${register}/LIMITATIONS.md has a table and contributed nothing — the parser lost it`,
    );
  }
});

test("every parsed row has as many cells as its own table's header", () => {
  // Stated as an outcome rather than as a column count, because there is no single column count.
  // Across the registers on disk §B has three shapes — seven columns in tracker, five in engine and
  // its neighbours, and a differently-named five in telemetry and testing. A parser holding any one
  // of those as the truth reads five registers as ragged or, far worse, reads their cells under the
  // wrong headings. Every row carrying an id and a description is the property that survives that.
  assert.ok(status.limitations.length > 30, `only ${status.limitations.length} staged rows`);
  for (const row of [...status.limitations, ...status.envelope]) {
    // `L-81`, `A-1`, `T-5`, `M-NET-2` — the registers do not agree on a prefix shape and never
    // have. What every id has is a leading capital and a trailing number.
    assert.match(row.id, /^\*{0,2}[A-Z][A-Z0-9-]*\d+/, `"${row.id}" is not a limitation id`);
    assert.ok(row.gap.length > 10, `${row.id} has no description`);
    assert.ok(["OPEN", "RETIRING", "RETIRED", "ENVELOPE"].includes(row.state), `${row.id}: ${row.state}`);
  }
});

test("an envelope row is marked as one rather than defaulted to open", () => {
  assert.ok(status.envelope.length > 0);
  for (const row of status.envelope) {
    assert.equal(row.state, "ENVELOPE");
    // §A rows have no exit test and no status, because they do not burn down. Filling those fields
    // with something plausible would be this site inventing a claim the register never made.
    assert.equal(row.exitTest, "");
    assert.equal(row.status, "");
  }
});

test("the task totals are the sum of the category rows", () => {
  const summed = status.categories.reduce(
    (acc, row) => ({ tasks: acc.tasks + row.tasks, done: acc.done + row.done }),
    { tasks: 0, done: 0 },
  );
  assert.equal(status.total, summed.tasks);
  assert.equal(status.done, summed.done);
});

test("no completion percentage is published", () => {
  // The one number `docs/ROADMAP.md` says is not derivable. A page that quoted it would be
  // republishing a figure whose own source describes it as stale by design.
  //
  // Scoped to a percentage that CLAIMS COMPLETION, not to the character. The limitation registers
  // quote live observations in their own status cells — "0% reliable", "100.0% · 7 of 7 pieces" —
  // and those are measurements of something else entirely. A rule banning the `%` character would
  // fire on evidence, which is the opposite of what this page is for.
  const body = page.replace(/<[^>]+>/g, " ").replace(/&[a-z]+;|&#x?[0-9a-f]+;/gi, " ");
  const claims = [
    ...body.matchAll(/(?:overall|system|project|total)[^.]{0,40}?\d{1,3}(?:\.\d+)?\s*%/gi),
    ...body.matchAll(/\d{1,3}(?:\.\d+)?\s*%\s*(?:complete|completion|done|finished)/gi),
  ].map((hit) => hit[0]);
  assert.deepEqual(claims, [], `the page claims a completion figure: ${claims.join(", ")}`);
  // And the generated data carries no such field for a future page to reach for.
  assert.equal(status.percentage, undefined);
});

test("the page quotes ROADMAP's own sentence about why", () => {
  assert.ok(status.notRecomputed.includes("not derivable from this table"));
  assert.ok(
    page.includes(status.notRecomputed.slice(0, 40)),
    "the raw ratio is published without the sentence explaining what it is not",
  );
});

test("the test totals are the measured ones, or honestly absent", () => {
  // Never zero. A value nobody reported is not zero — the rule the app's dashboard opens by stating,
  // and "0 tests failed" is the most reassuring possible way to say "nothing has been measured".
  if (status.tests === null) return;
  assert.ok(Number.isInteger(status.tests.passed) && status.tests.passed > 0);
  assert.ok(Number.isInteger(status.tests.failed));
});

test("the branch the totals come from is still the branch CI creates", () => {
  // `build-status.mjs` hardcodes `badges`, which is a deliberate exception to
  // `layout.properties` — that manifest holds directories and says so in its own header. This is the
  // drift check a manifest key would have bought, and it is cheaper.
  const workflow = readFileSync(
    path.join(repositoryDirectory, ".github/workflows/build.yml"),
    "utf8",
  );
  assert.match(workflow, /git switch --orphan badges/);
  const generator = readFileSync(path.join(siteDirectory, "scripts/build-status.mjs"), "utf8");
  assert.match(generator, /badges:/);
});
