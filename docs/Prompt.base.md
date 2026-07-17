# Prompt.base.md — base orientation prompt for NoderaMC

<!-- AI-AGENT-INSTRUCTION: This is a BASE PROMPT. Paste/include it at the start of any session
     (human or agent) working on NoderaMC. It tells you which files are load-bearing, what the
     project's pattern is, where progress lives, and how the GitHub-issue workflow operates.
     Keep it accurate; update it when a new "always-read" file is introduced. -->

You are working on **NoderaMC** — a NeoForge-based, decentralized Minecraft system: the world is
partitioned into 8×8-chunk regions, each simulated and validated by a small committee of
player-run peers, with a dedicated server that starts as the coordinator and is progressively
demoted to a non-authoritative full archival bootstrap peer. The project's central bet is a
**bit-for-bit deterministic region engine**; everything downstream gates on it.

---

## 1. Read these files first (in this order)

| File | Why it matters |
|---|---|
| [`AGENTS.md`](../AGENTS.md) | **Agent memory.** Build/test commands, layering rules, the frozen contracts, and the three non-negotiable disciplines (test-before-commit, update README, commit format). Re-read every session. |
| [`README.md`](../README.md) | **Progress + status.** Progress bar, module table, roadmap (tasks → issues), commit-message standard, issue-system summary. |
| [`Tested.md`](../Tested.md) | **Test status per module** (counts, pass/fail emojis, last run). |
| [`.github/ISSUE_SYSTEM.md`](../.github/ISSUE_SYSTEM.md) | **The normative workflow**: how to open/assign/branch/commit/close/reopen issues, and how to edit README. |
| [`docs/Plan.md`](Plan.md) | **Architecture & roadmap.** Locked decisions (§3), module layout (§4), phases (§6), invariants (§8). |
| [`docs/Task.0.md`](Task.0.md) | **Conventions, definitions, task index & dependency graph.** Binding for all other tasks. |
| [`docs/LIMITATIONS.md`](LIMITATIONS.md) | **The binding limitation register.** §A envelope constraints, §B staged-capability burn-down (each with an owning task + exit test). No permanent exclusions allowed. |
| [`docs/Task.1.md` … `docs/Task.16.md`](Task.1.md) | **Per-task specs** (goal, folders, classes, mod/server split, acceptance criteria). Each maps 1:1 to a GitHub issue. |

Issue templates: `.github/ISSUE_TEMPLATE/bug.md`, `.github/ISSUE_TEMPLATE/task.md`.

---

## 2. The project pattern

- **Layered, Minecraft-free core.** `core` → JDK only. `simulation`, `protocol`, `consensus`,
  `transport-api`, `storage-api` → `core`. `testkit` → all of them. Minecraft/NeoForge types live
  ONLY in `transport-neoforge` and `neoforge-mod` (not yet onboarded). This keeps the consensus- and
  determinism-critical code unit-testable without a server.
- **Frozen contracts — do not change without a version bump:**
  - Canonical encoding: `core/crypto/CanonicalWriter` + `CanonicalReader` + `Encodable` +
    `TypeTags`. Every `Encodable.encode` starts with `writeU16(typeTag); writeU16(ENCODING_VERSION);`.
  - `core/Bytes` is the single byte[] value type (never raw `byte[]` in records).
  - Wire tags: `protocol/codec/MessageCodec` (append-only — never renumber).
  - Hash: `core/crypto/HashService` (SHA-256 over canonical encoding). Sign: Ed25519 verify on
    `core/crypto/SignatureService`; signing lives on `core/identity/NodeIdentity`.
- **Determinism is sacred.** Inside `simulation`: no wall clocks, no `ThreadLocalRandom`/`Math.random`/
  `UUID.randomUUID`, no unordered-map iteration, no IO. All randomness via `DeterministicRandom`
  (L64X128MixRandom seeded from `StableHash`). Proven by `simulation/DeterminismPropertyTest`.
- **GitHub-issue-driven.** Every task is an issue; every detected problem becomes a `bug` issue
  before a regression reaches `main`. One task = one branch = one PR.

---

## 3. How progress is tracked (and where it lives)

- **Overall % and per-phase %** → `README.md` → "Progress" (the `Overall system completion: <p>%`
  line + block bar). Recomputed on every commit that changes outcomes.
- **Per-module test status** → `README.md` module table AND `Tested.md` (authoritative). Emojis:
  ✅ done · 🚧 partial · ⏳ in progress · ⬜ not started · ❌ failing.
- **Roadmap / task status** → `README.md` "Roadmap" table, mirrored 1:1 by GitHub issues labelled
  `task` + `phase-N`.
- **Limitations burn-down** → `docs/LIMITATIONS.md` §B (OPEN → RETIRING → RETIRED).

### To view progress
```bash
gh issue list --state open --label task        # what's left
gh issue list --state closed --label task      # what's done
cat README.md | sed -n '/Progress/,/^---/p'    # the bar + phase table
cat Tested.md                                  # test counts + emojis
```

### To update progress (mandatory on every outcome-changing commit)
1. Edit `README.md`: recompute `Overall system completion: <p>%` + the block bar; tick the module
   table and roadmap rows. Preserve every `<!-- AI-AGENT-INSTRUCTION: ... -->` comment.
2. Edit `Tested.md`: update test counts, `Last run` date, and the module emoji.
3. Close/open the relevant GitHub issue (`Closes #N` / `Reopen #N`).
4. If a §B limitation was staged or retired, update `docs/LIMITATIONS.md` in the same commit.

---

## 4. Opening & closing issues

- **Open a task issue** from `.github/ISSUE_TEMPLATE/task.md` — title `Task N — <title>`, label
  `task` + the owning `phase-N`; body links the task file and lists acceptance criteria.
- **Open a bug issue** from `.github/ISSUE_TEMPLATE/bug.md` — title `bug: <symptom>`, label `bug`.
  Do this the MOMENT a problem is detected (red test, divergence, contract violation) — never
  fix-forward a regression silently.
- **Work it**: self-assign + `in-progress`; branch `<type>/<slug>-#<issue>`; cite `refs #N` in
  commits while in progress.
- **Close it**: open a PR whose description says `Closes #N` (or `fixes #N` for bugs), lists the
  acceptance criteria met, and the README/Tested deltas. Squash-merge on orchestrator approval;
  the issue auto-closes.
- **Reopen**: if a closed issue regresses later, reopen it, add `regression` + a comment linking the
  original close.

Full rules: [`.github/ISSUE_SYSTEM.md`](../.github/ISSUE_SYSTEM.md).

---

## 5. Non-negotiable disciplines (every commit, no exceptions)

1. **Run `./gradlew check` first.** If red, you do NOT commit. If you can't fix it, open a `bug`
   issue and stop.
2. **Update `README.md` + `Tested.md` in the same commit** (see §3 above).
3. **Commit message format:**
   ```
   <emoji> [<overall-percentage>%] <change type>: <short description in English>
   ```
   Emoji/type legend + examples: `README.md` → "Commit message standard". Reference the issue:
   `refs #N` while working; `fixes #N` / `closes #N` to close.

---

## 6. The debugger (issue #17)

A standing task owns the **Nodera debugger** — an integration harness that boots real server
instances over the `LoopbackTransport`, drives P2P scenarios for every implemented lane
(block-break validation, redstone, entities, …), performs real-time execution debugging, counts
passing tests, and emits debug logs + coverage reports (driving toward 100% coverage). Each lane
task (5–16) adds scenarios to it.
