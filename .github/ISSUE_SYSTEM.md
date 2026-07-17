# GitHub Issue System — the normative workflow

<!-- AI-AGENT-INSTRUCTION: This file is the authority on how work is tracked, fixed, and closed in
     NoderaMC. Read it once at session start and follow it for every change. -->

This project uses **GitHub issues as the single source of truth** for tasks, bugs, and progress.
Every task in `docs/Task.*.md` has a corresponding issue; every detected problem becomes an issue
before a regression reaches `main`. Issues + PRs + branches let multiple agents work in parallel
without stepping on each other.

---

## 1. Labels

| Label | Meaning |
|---|---|
| `task` | A planned unit of work (one Task.NN.md, or a sub-task of one). |
| `bug` | Something is broken or a test is red. |
| `test` | Pure test work (coverage, fixtures, property tests). |
| `docs` | Documentation only (README, Tested.md, this file, task specs). |
| `phase-0` … `phase-8` | Which Plan §6 phase the work belongs to. |
| `mvp-gate` | Blocks the first playable milestone (Task 7 scenario). |
| `blocked` | Cannot start — depends on an unresolved issue (link it). |
| `in-progress` | Actively being worked on by an agent. |
| `needs-review` | PR open, awaiting orchestrator review. |
| `wontfix` | Deliberately not addressed (must say why + which §A/§B ledger entry covers it). |

---

## 2. Opening issues

- **Task issue** — created from `.github/ISSUE_TEMPLATE/task.md`. Title: `Task N — <short title>`.
  Body links to `docs/Task.N.md`, lists acceptance criteria, names the owning phase label.
- **Bug issue** — created from `.github/ISSUE_TEMPLATE/bug.md` the MOMENT a problem is detected
  (red test, divergence, crash, contract violation). Title: `bug: <one-line symptom>`. Body:
  reproduction, expected vs actual, the failing test / log excerpt, affected module(s).
- **Never** fix-forward a regression silently. Open the bug issue first, then fix on a branch, then
  close it with `fixes #N` in the PR.

---

## 3. Branching

- One task = one branch = one PR. Branch naming: `<type>/<short-slug>-#<issue>`.
  - `feature/shadow-capture-#5`
  - `fix/max-y-offbyone-#21`
  - `test/determinism-property-#7`
- Branch off the latest `main`. Rebase before opening the PR if `main` moved.

---

## 4. Assigning & working

- Self-assign the issue and set `in-progress` before starting work (so two agents don't grab the
  same task).
- Reference the issue in every commit: `refs #N` while in progress.
- If you discover the issue is blocked, set `blocked`, link the blocking issue, and unassign.

---

## 5. Committing (non-negotiable standard)

Before committing, the agent MUST, in order:

1. **Run `./gradlew check` and confirm it is green.** Never commit on a red build. If a test fails
   and you cannot fix it, open a `bug` issue and stop.
2. **Update `README.md`** — recompute the progress bar percentage and update the module status
   table / roadmap ticks.
3. **Update `Tested.md`** — adjust test counts, `Last run` date, and emojis for any module whose
   status changed.
4. **Commit using the exact format** (see README.md → "Commit message standard"):
   ```
   <emoji> [<overall-percentage>%] <change type>: <short description in English>
   ```
   with `refs #N` (work in progress) or `fixes #N` / `closes #N` (issue done) on its own line in
   the body.

---

## 6. Pull requests & closing

- Open a PR against `main`. Set the issue to `needs-review`.
- PR description MUST list: the issue(s) closed (`Closes #N`), the acceptance criteria met (link the
  test names), the README/Tested.md deltas, and the new overall percentage.
- The **orchestrator** reviews: `./gradlew check` green, README/Tested in sync, acceptance criteria
  evidenced, frozen contracts (Plan §3 / AGENTS.md) unchanged without a version bump.
- On approval: squash-merge, delete the branch. The issue auto-closes via `Closes #N`.

---

## 7. Responding, reopening, and the agent's "fix loop"

- If review finds the work unsatisfactory, the orchestrator leaves review comments, keeps the issue
  open, and removes `needs-review` / restores `in-progress`. The agent addresses every comment on
  the SAME branch and re-pushes.
- A closed issue is **reopened** if a later test or soak re-exposes it. Reopened issues get a
  `regression` note linking the original close.
- Bugs that turn out to be intended/staged behaviour are moved to `LIMITATIONS.md` §A or §B (with an
  owning task + exit test) rather than silently closed.

---

## 8. Editing README.md

`README.md` is machine- and human- read. Rules:

- Keep every `<!-- AI-AGENT-INSTRUCTION: ... -->` comment. They tell the next agent exactly what to
  change. Add new ones when you introduce a new machine-maintained field.
- The progress bar (`Overall system completion: <p>%` + the block line) is recomputed every commit.
- The module status table and the roadmap table stay in lock-step with `Tested.md` and the GitHub
  issues respectively.
- Never delete the "Commit message standard" or "GitHub issue system" sections — new agents rely on
  them.

---

## 9. The debugger tool (issue #17)

A standing task (`#17`, label `task` + `test`) is reserved for the **Nodera debugger**: an
integration-test harness that boots real server instances over the `LoopbackTransport`, drives
P2P-network scenarios (block-break validation, redstone circuits, every implemented lane), performs
real-time execution debugging, counts passing tests, and emits debug-log files + coverage reports.
Its goal is to drive the codebase toward 100% coverage. Every lane task (5–16) adds scenarios to
the debugger; the debugger's own issue tracks the harness skeleton and the coverage dashboard.
