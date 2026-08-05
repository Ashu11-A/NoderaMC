// What actually works, read out of the repository rather than typed into a page.
//
// Three sources, and each of them is the project's own answer to a question this site would
// otherwise be tempted to answer for itself:
//
//   * `docs/ROADMAP.md` §1 — the per-category task counts. Its own weighted completion percentage is
//     NOT published here. That file says in its own words that the weighting is not derivable from
//     its tables and that inventing one would be worse than a stale figure which says so, and it is
//     right. What is derivable — done and total — is published, and ROADMAP's sentence is quoted.
//   * the `badges` orphan branch — the measured test totals, produced by CI. Absent means `null`,
//     which the page renders as "not measured on this build". Never zero: a value nobody reported is
//     not zero, which is the rule the app's dashboard opens by stating.
//   * `docs/*\/LIMITATIONS.md`, BY GLOB — every register. The count is glob-derived and never a
//     literal: two earlier plans for this site wrote "nine" from memory, the answer was eleven, and
//     it is ten since the app and mobile categories merged into `frontend`.
import { readFileSync, writeFileSync, mkdirSync, readdirSync } from "node:fs";
import path from "node:path";
import { dir, repositoryDirectory } from "nodera-ui/layout";
import { blobUrl, git } from "./repo.mjs";

const DOCS = path.join(repositoryDirectory, "docs");
const OUT = path.join(dir("web"), "src/generated/status.json");

const die = (message) => {
  console.error(`build-status: ${message}`);
  process.exit(1);
};

/* ------------------------------------------------------------------ markdown tables, honestly */

/**
 * Split a table row into cells.
 *
 * Two kinds of pipe are content rather than a separator: one inside an inline code span
 * (`holdsCompletely(...) || hosts(...)`) and an escaped one. Counting either as a cell reports ten
 * perfectly square tables as ragged, which is how a linter teaches people to ignore it —
 * `scripts/check-docs.sh` learned that the same way and this follows its rule exactly.
 */
function cells(row) {
  const out = [];
  let current = "";
  let inCode = false;
  for (let i = 0; i < row.length; i += 1) {
    const ch = row[i];
    if (ch === "\\" && row[i + 1] === "|") {
      current += "|";
      i += 1;
    } else if (ch === "`") {
      inCode = !inCode;
      current += ch;
    } else if (ch === "|" && !inCode) {
      out.push(current);
      current = "";
    } else {
      current += ch;
    }
  }
  out.push(current);
  return out.slice(1, -1).map((cell) => cell.trim());
}

const isSeparator = (row) => /^\|[\s:|-]+\|$/.test(row.trim());
const isRow = (line) => line.trim().startsWith("|") && line.trim().endsWith("|");

/**
 * The first table under `heading`, as `{ header, rows }` of trimmed cells.
 *
 * The width is taken from the header rather than passed in, and that is the correction that matters
 * here. Both plans this site was built from state that a §B register table has a fixed column count
 * — one says six, the other seven. Across the ten registers on disk there are **three** shapes:
 *
 *   ID | Gap | Why it is not permanent | Elimination path | Owner | Exit test | Status   (tracker)
 *   ID | Limitation today | Owner | Exit test | Status                (engine, network, peer, …)
 *   Row | Status | What is missing | Owning task | Exit test           (telemetry, testing)
 *
 * A parser holding any one of those as the truth reads five registers as ragged and refuses to
 * build, or — far worse — reads their cells under the wrong headings and publishes them. So the
 * header is the schema, `columns()` below maps its names onto one row shape, and the only invariant
 * asserted is the one that is genuinely universal and genuinely breaks silently: **every row of a
 * table has as many cells as that table's own header.** That is exactly `check-docs.sh`'s rule.
 */
function tableUnder(source, file, heading) {
  const lines = source.split("\n");
  const start = lines.findIndex((line) => line.startsWith(heading));
  if (start === -1) return null;
  let header = null;
  const rows = [];
  for (let i = start + 1; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.startsWith("## ") && i > start) break;
    if (!isRow(line)) {
      if (header && rows.length > 0) break;
      continue;
    }
    if (isSeparator(line)) continue;
    const parsed = cells(line);
    if (!header) {
      header = parsed;
      continue;
    }
    if (parsed.length !== header.length) {
      die(
        `${file}:${i + 1} has ${parsed.length} cells; the table under "${heading}" has ` +
          `${header.length} (${header.join(" | ")})`,
      );
    }
    rows.push(parsed);
  }
  return header ? { header, rows } : null;
}

/**
 * Where each field of a limitation row lives in this particular table.
 *
 * Named synonyms rather than positions, because the three shapes above put "the thing that is
 * missing" in three different columns under three different names. A field with no column is
 * absent, and absent means the empty string rather than a plausible-looking value borrowed from a
 * neighbouring cell: an envelope constraint genuinely has no exit test, and saying it has one would
 * be this site inventing a claim the register never made.
 */
function columns(header) {
  const find = (...names) =>
    header.findIndex((cell) => names.includes(cell.replace(/\*\*/g, "").trim()));
  return {
    id: find("ID", "Row"),
    gap: find("Gap", "Limitation today", "What is missing", "Constraint (immovable fact)"),
    whyNotPermanent: find("Why it is not permanent"),
    eliminationPath: find("Elimination path", "Mechanism that hides it"),
    owner: find("Owner", "Owning task"),
    exitTest: find("Exit test"),
    status: find("Status"),
  };
}

/** Markdown emphasis and links, removed. Cell text is read by people, not by a renderer. */
function plain(cell) {
  return cell
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/\*\*/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

/* ------------------------------------------------------------------------------ ROADMAP §1 */

const roadmap = readFileSync(path.join(DOCS, "ROADMAP.md"), "utf8");

const snapshot = /\*\*Snapshot:\s*([0-9]{4}-[0-9]{2}-[0-9]{2})\*\*/.exec(roadmap);
if (!snapshot) die("docs/ROADMAP.md no longer opens with a **Snapshot: YYYY-MM-DD**");

const categoryTable = tableUnder(roadmap, "docs/ROADMAP.md", "## 1. Where the build stands");
if (!categoryTable || categoryTable.rows.length === 0) die("docs/ROADMAP.md §1 has no category table");

const categories = [];
let total = null;
for (const row of categoryTable.rows) {
  const name = plain(row[0]);
  const numbers = row.slice(1, 5).map((cell) => Number(plain(cell)));
  if (numbers.some((value) => !Number.isInteger(value))) {
    die(`docs/ROADMAP.md §1 row "${name}" has a non-numeric count`);
  }
  const [tasks, done, inProgress, blocked] = numbers;
  if (name.toLowerCase() === "total") total = { tasks, done };
  else categories.push({ category: name, tasks, done, inProgress, blocked });
}
if (!total) die("docs/ROADMAP.md §1 has no Total row");

// Summed from the rows, and the Total row is checked against the sum rather than trusted.
//
// The published figure is the SUM. That is the derivable one: it is what the per-category rows
// actually say, and each of those rows is checked against its own category's task files by the doc
// sweep. The Total row is a transcription of it, and a transcription is the thing that goes stale.
//
// It was stale when this was written — the rows summed to 51/82 and the Total row said 49/80. The
// drift entered in `f4ad09e`, which raised Network 14 → 15 and added the Testing row without
// touching the total; it went unnoticed for two merges because nothing printed the disagreement.
// `docs/ROADMAP.md` has since been corrected and carries the story.
//
// The warning stays, and stays non-fatal. A website that cannot build until somebody edits a
// documentation file is a coupling nobody asked for; a disagreement nothing prints is how the
// transcription got stale in the first place. Publishing the sum means the page is right either way.
const summed = categories.reduce(
  (acc, row) => ({ tasks: acc.tasks + row.tasks, done: acc.done + row.done }),
  { tasks: 0, done: 0 },
);
if (summed.tasks !== total.tasks || summed.done !== total.done) {
  console.warn(
    `build-status: docs/ROADMAP.md §1 does not add up — the rows sum to ${summed.done}/${summed.tasks}` +
      ` and the **Total** row says ${total.done}/${total.tasks}. The site publishes the sum.` +
      ` Update that row (or the rows) in docs/ROADMAP.md.`,
  );
}

// The sentence, in ROADMAP's own words, about why the weighted figure is not recomputed. Quoted
// rather than paraphrased, and required rather than optional: if it is ever removed, this site would
// otherwise start publishing a raw ratio with no explanation of what it is not.
const paragraphs = roadmap.split(/\n\s*\n/).map((block) => block.replace(/\s*\n\s*/g, " ").trim());
const notRecomputedBlock = paragraphs.find((block) => block.includes("not derivable from this table"));
if (!notRecomputedBlock) die("docs/ROADMAP.md no longer explains why its figure is not recomputed");
const notRecomputed = plain(
  notRecomputedBlock
    .split(/(?<=\.)\s+/)
    .find((sentence) => sentence.includes("not derivable from this table")),
);

/* ------------------------------------------------------------------------- measured totals */

/**
 * The test totals CI measured, from the `badges` orphan branch.
 *
 * The branch name is hardcoded here, with the file that produces it named beside it, rather than
 * being added to `layout.properties`: that manifest holds directories, says so in its own header,
 * and already carries one documented exception it did not want. `web/tests/status.test.mjs` asserts
 * `build.yml` still creates this branch, which is the drift check a manifest key would have bought.
 */
function measuredTotals() {
  // Produced by `.github/workflows/build.yml`'s `git switch --orphan badges`.
  const read = (file) => {
    const raw = git(["show", `badges:${file}`]);
    if (raw === null) return null;
    const message = JSON.parse(raw).message;
    // Shields endpoint shape: the count is comma-formatted for display.
    const value = Number(String(message).replace(/,/g, ""));
    return Number.isInteger(value) ? value : null;
  };
  const passed = read("tests-passed.json");
  const failed = read("tests-failed.json");
  // Both, or neither. "2,876 passed and an unknown number failed" is a worse claim than "not
  // measured on this build", because it reads as a claim that nothing failed.
  return passed === null || failed === null ? null : { passed, failed };
}

/* ------------------------------------------------------------------- the limitation registers */

const registers = readdirSync(DOCS, { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => ({ register: entry.name, file: path.join(DOCS, entry.name, "LIMITATIONS.md") }))
  .filter(({ file }) => {
    try {
      readFileSync(file);
      return true;
    } catch {
      return false;
    }
  })
  .sort((a, b) => a.register.localeCompare(b.register));

if (registers.length === 0) die("no docs/*/LIMITATIONS.md registers were found at all");

/** `[5](Task.5.md)` in a register's Owner cell → where that task file is read on GitHub. */
function ownerLink(cell, register) {
  const link = /\[[^\]]*\]\(([^)]+)\)/.exec(cell);
  if (!link) return null;
  const target = link[1];
  if (/^https?:/.test(target)) return target;
  return blobUrl(path.posix.normalize(path.posix.join("docs", register, target)));
}

/** One table's rows, mapped onto the shape `/status` renders. */
function readRows(table, register, relative, defaultState) {
  if (!table) return [];
  const at = columns(table.header);
  if (at.id === -1 || at.gap === -1) {
    die(`${relative}: a limitation table with no ID and no description column (${table.header.join(" | ")})`);
  }
  const cell = (row, index) => (index === -1 ? "" : plain(row[index]));
  return table.rows.map((row) => {
    const status = cell(row, at.status);
    // The first word of the Status cell IS the state; the rest of it can run to four hundred words
    // and often does. Where a table has no Status column the state is the caller's, because a §A
    // envelope constraint does not have one — it does not burn down, which is the point of §A.
    const first = (status.split(/[\s—-]/)[0] || "").toUpperCase();
    const state = status === "" ? defaultState : first;
    if (!["OPEN", "RETIRING", "RETIRED", "ENVELOPE"].includes(state)) {
      die(`${relative}: row ${cell(row, at.id)} has a Status cell starting "${first}"`);
    }
    const ownerCell = at.owner === -1 ? "" : row[at.owner];
    return {
      id: cell(row, at.id),
      register,
      gap: cell(row, at.gap),
      whyNotPermanent: cell(row, at.whyNotPermanent),
      eliminationPath: cell(row, at.eliminationPath),
      owner: cell(row, at.owner),
      ownerUrl: ownerCell ? ownerLink(ownerCell, register) : null,
      exitTest: cell(row, at.exitTest),
      status,
      state,
    };
  });
}

const limitations = [];
const envelope = [];

for (const { register, file } of registers) {
  const source = readFileSync(file, "utf8");
  const relative = path.posix.join("docs", register, "LIMITATIONS.md");

  // Most registers split into `## §A` (envelope) and `## §B` (staged). `docs/testing` has neither:
  // it is one table under its H1. Looking only under `## §B` read that register as empty and
  // dropped six open rows off `/status` in silence — the exact failure this page exists to make
  // impossible, committed by the page itself. So a register with no `## §B` falls back to its first
  // table, and `web/tests/status.test.mjs` fails on a register that has a table and contributed
  // nothing.
  const staged = tableUnder(source, relative, "## §B") ?? tableUnder(source, relative, "# ");
  limitations.push(...readRows(staged, register, relative, "OPEN"));
  envelope.push(
    ...readRows(tableUnder(source, relative, "## §A"), register, relative, "ENVELOPE"),
  );
}

/* ---------------------------------------------------------------------------------- output */

const status = {
  snapshot: snapshot[1],
  categories,
  done: summed.done,
  total: summed.tasks,
  notRecomputed,
  tests: measuredTotals(),
  registerCount: registers.length,
  limitations,
  envelope,
};

mkdirSync(path.dirname(OUT), { recursive: true });
writeFileSync(OUT, `${JSON.stringify(status, null, 2)}\n`);
console.log(
  `build-status: ${categories.length} categories, ${status.done}/${status.total} tasks, ` +
    `${limitations.length} staged rows and ${envelope.length} envelope rows across ` +
    `${registers.length} registers, tests ${status.tests ? `${status.tests.passed}/${status.tests.failed}` : "not measured"}`,
);
