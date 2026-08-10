<!-- AI-AGENT-INSTRUCTION: This is an ACTIVE PROGRAMME PLAN — the scoping document for the
     source-reduction effort that runs on `refactor/reuse-driven-shrink`. It is cross-cutting and
     owned by no single docs category. Update the phase table when a phase merges; do not delete
     the measured baseline, because every claim the programme makes is a delta against it. -->

# Plan 11 — the tree gets smaller by being written once

## Context

This repository is at 90.4% of its roadmap and holds 246,102 lines of source across three languages.
Nothing about that number is wrong on its own. What is wrong is how much of it is the same thing
written more than once: nine action classes with parallel `encode`/`decode`/`tag`, five inline JSON
builders in one control handler, 74 hand-rolled logger fields, 25 integration tests each standing up
the same mesh in its own 30–80 lines, a protocol specification stored as 449 lines of comment above
46 lines of code, and one Rust crate with three files over 890 lines apiece.

The programme's goal was a directional **30% reduction in code lines**, achieved by eliminating
redundancy, extracting shared primitives and moving specifications into `docs/` — never by removing
a feature. Repackaging `dev.nodera.research.*` out of the build was considered and rejected: that is
feature removal wearing a refactor's clothes.

> **Round 1 delivered 0.85% of a 30% target — 2.8% of the goal.** Five read-only analyses then
> measured every lever the plan had estimated, and four of the five estimates were wrong. The target
> is now **10%**, the levers are the measured ones, and a second objective of equal weight has been
> added: lowering the error rate. Everything from here to "Round 2" is the executed record of round
> 1, kept because every later claim is a delta against it. **The current plan is
> [§Round 2](#round-2--re-baselined-to-10-on-measured-numbers) and
> [§The error-rate programme](#the-error-rate-programme).**

**The 30% figure was a target, not a gate.** What CI *does* enforce is that the tree never grows past
its last stamped measurement without somebody stamping a new one in the same commit.

---

## The measurement

Before this plan there was no agreed way to count. Two different people counting this tree got two
different answers, and one of them was counting `app/ui/src` and calling it "TypeScript".

`scripts/loc-metrics.py` is now the answer, over `scripts/lib/loc_classify.py`, which is a lexer
rather than a regex — because a regex cannot tell `// comment` from `"https://example"`, cannot tell
Rust's `'a` lifetime from a `'x'` literal, and eats the rest of a file the first time it reads the
`/*` inside a TypeScript regex literal as a block comment. All three shapes exist here.

Only git-tracked files are counted. That is what makes the number reproducible on a clean clone and
what excludes `target/`, `build/` and `node_modules/` without maintaining a list of them. Generated
files are measured, reported, and then excluded from the ratchet: `nodera-codec`'s `kinds.rs` grows
by three lines every time a wire kind is appended, and a size gate that fails on that would be a
gate against adding messages.

### Baseline — 2026-08-05 at `9959df1`

| bucket | files | total | code | comment | blank | comment/code |
|---|---|---|---|---|---|---|
| `java.main` | 705 | 105,074 | 60,815 | 35,751 | 8,508 | 58.8% |
| `java.test` | 519 | 81,594 | 60,440 | 10,943 | 10,211 | 18.1% |
| `rust.main` | 111 | 43,208 | 31,448 | 8,348 | 3,412 | 26.5% |
| `rust.test` | 3 | 570 | 444 | 91 | 35 | 20.5% |
| `ts` | 82 | 15,656 | 11,405 | 3,239 | 1,012 | 28.4% |
| **TOTAL** | **1,420** | **246,102** | **164,552** | **58,372** | **23,178** | **35.5%** |

The first stamp of this table put `java.main` at 69,241 and `java.test` at 52,014, because it split
Java by Gradle source set. That is wrong for exactly one module: everything in `:testing` is test
code, including its `src/main`, which is compiled into `main` only because that is how one Gradle
module exposes types to another module's tests. Bucketing it by source set reports a test harness as
production code — and that is not a naming quibble. Phase 3 removed 1,484 lines of duplicated test
setup and it showed up as **+695 lines of production growth**, turning the size gate red for doing
precisely what it was asked to do. 8,426 code and 4,079 comment lines moved buckets. The tree did not
change; the question it was being asked did.

Generated and excluded from the ratchet: `library/rust/nodera-codec/src/kinds.rs`, 505 code lines.

This measurement corrected two figures the programme was originally scoped against. The Java
"121,224 code" that framed the 30% target was total *lines*, not code lines — real Java code is
121,255, of which 69,241 is production and 52,014 is test. And TypeScript was scoped at 21 files
because only `app/ui` had been looked at; the real surface is 82 files across `app/ui` (8,113 code),
`web` (3,179) and `library/ts/nodera-ui` (113).

### Where the code is

| component | total | code | comment | c/code |
|---|---|---|---|---|
| `:peer` | 70,084 | 45,533 | 17,468 | 38.4% |
| `:engine` | 30,400 | 21,636 | 5,510 | 25.5% |
| `:transport` (frozen) | 20,511 | 12,493 | 6,115 | 48.9% |
| `:neoforge-mod` | 20,282 | 12,874 | 5,734 | 44.5% |
| `nodera-core` | 17,724 | 12,365 | 3,961 | 32.0% |
| `:core` (frozen) | 15,536 | 9,803 | 4,037 | 41.2% |
| `:testing` | 14,753 | 9,163 | 4,194 | 45.8% |
| `nodera-app-ui` | 11,004 | 8,113 | 2,150 | 26.5% |
| `:storage` | 8,516 | 5,711 | 1,842 | 32.3% |
| `nodera-codec` | 7,142 | 5,217 | 1,409 | 27.0% |
| `nodera-tracker` | 6,170 | 4,846 | 876 | 18.1% |
| `:endpoint` (frozen) | 5,508 | 3,258 | 1,614 | 49.5% |
| `nodera-telemetry` | 4,549 | 3,488 | 691 | 19.8% |
| `nodera-site` | 4,380 | 3,179 | 957 | 30.1% |
| `nodera-rendezvous` | 4,243 | 3,345 | 564 | 16.9% |
| `nodera-service` | 3,211 | 2,292 | 632 | 27.6% |
| `nodera-app` | 1,365 | 844 | 414 | 49.1% |
| `:paper-plugin` | 1,078 | 784 | 180 | 23.0% |
| `nodera-ui` | 272 | 113 | 132 | 116.8% |

Regenerate either table with `scripts/loc-metrics.py` and `scripts/loc-metrics.py --by-module`.

---

## The levers

The programme has five, and four of them add code before they remove it. That is the point: a shared
primitive is more lines than any single call site it replaces and fewer lines than all of them.

| lever | where | mechanism |
|---|---|---|
| **A** — decompose the god-classes | Java's largest files | extract collaborators into shared support packages |
| **B** — generic base abstractions | `core/action/*`, dispatch tables, logging | sealed interfaces, a codec registry, a `Logged` mixin |
| **C** — one test harness | 25 `*IT.java`, Rust `#[cfg(test)]` fixtures | `PeerTestHarness`, a shared fixtures module |
| **D** — specifications out of comments | the high-density files | block comments become `docs/<category>/Task.<n>.md`, with a one-line pointer left behind |
| **E** — split the monoliths | `nodera-core`, `tracker/service.rs`, `app/ui/src` | focused modules, `ui/primitives.tsx` |

Two rules constrain all five. **Frozen modules** — `:core`, `:transport`, `:endpoint` — carry
versioned contracts, so in phases 1 through 4 their comments may move and their code may not change
at all. The single exception is phase 5, which is a version bump: `AGENTS.md` §4.4 says a frozen
contract changes only with one, and phase 5 is that one. `library/java/transport/.../MessageCodec.java`
(1,585 lines) is therefore phase 5's work and nobody else's.

And **wire tags are append-only**: phase 5 rewrites how a codec is dispatched, never what number it
answers to. Renumbering a tag is a network split, not a refactor.

---

## Phases

One task, one issue, one sub-branch, one PR. Every sub-branch merges into
`refactor/reuse-driven-shrink`; the integration branch reaches `main` only through a PR, after a
live `scripts/dev.sh --play` run.

| # | phase | issue | PR | `java.main.code` | other |
|---|---|---|---|---:|---|
| 0 | measure the tree, and make the measurement a gate | [#209](https://github.com/Ashu11-A/NoderaMC/issues/209) | [#215](https://github.com/Ashu11-A/NoderaMC/pull/215) | — | the tooling |
| 1 | delete what nothing can reach | [#210](https://github.com/Ashu11-A/NoderaMC/issues/210) | [#217](https://github.com/Ashu11-A/NoderaMC/pull/217) | **−182** | 45 methods |
| 2 | specifications move to `docs/` | [#211](https://github.com/Ashu11-A/NoderaMC/issues/211) | [#216](https://github.com/Ashu11-A/NoderaMC/pull/216) | 0 | −245 comment |
| 3 | one integration-test harness | [#212](https://github.com/Ashu11-A/NoderaMC/issues/212) | [#221](https://github.com/Ashu11-A/NoderaMC/pull/221) | 0 | **−768** test |
| 4 | decompose god-classes, extract primitives | [#213](https://github.com/Ashu11-A/NoderaMC/issues/213) | [#220](https://github.com/Ashu11-A/NoderaMC/pull/220) | **+26** | −42 Rust, −23 TS |
| 5 | one codec dispatch, one keep-alive, 0.2.0 | [#214](https://github.com/Ashu11-A/NoderaMC/issues/214) | [#219](https://github.com/Ashu11-A/NoderaMC/pull/219) | **−399** | 0 fixture bytes |

### The result

Measured on the six branches merged together, because a per-branch figure does not compose:

| bucket | baseline | merged | delta |
|---|---:|---:|---:|
| `java.main.code` | 60,815 | 60,260 | −555 |
| `java.test.code` | 60,440 | 59,672 | −768 |
| `rust.main.code` | 31,448 | 31,406 | −42 |
| `rust.test.code` | 444 | 436 | −8 |
| `ts.code` | 11,405 | 11,382 | −23 |
| **total code** | **164,552** | **163,156** | **−1,396 (−0.85%)** |

> **SUPERSEDED — this is round 1's headline and it stopped being the programme's two rounds ago.**
> It is kept because the reasoning below it is what re-scoped the plan, and because a superseded
> number that is still labelled is worth more than a corrected one nobody can trace. Two things about
> it: the buckets are the pre-correction ones (`.mjs`/`.js` counted as nothing, Rust `#[cfg(test)]`
> counted as production, `ts` unsplit), so neither figure is comparable to anything after
> §"The measurement was wrong a third and fourth time"; and re-measured under today's definitions the
> same two commits are **169,094 → 167,711, −1,383**, which is almost exactly the same answer. The
> −1,396 was never wrong. It was quoted as the programme's result long after two more rounds had
> landed, including in the pull-request body, which is the failure this note exists to stop.
> **The programme's result is [§Round 3](#round-3--the-correctness-round-and-the-programmes-final-numbers).**

The target was 30% and the answer is 0.85%. The plan said the headline was directional and that the
programme would report its honest number, so here it is, along with why.

**Two of the five levers moved the metric and three did not.** Deleting code that no longer runs
(−182) and collapsing a *literally duplicated* dispatch table — 76 `instanceof` arms plus a 76-case
`switch` for the same 76 tags (−399) — account for essentially all of the production-Java reduction.
Extracting shared primitives from god-classes, which the plan treated as lever A and the main event,
came out **+26**: a primitive carrying real Javadoc costs more lines than the short duplicated loops
it absorbs, and `HexKeyedStore` at ~95 lines replacing ~72 across two stores is the shape of it.
Migrating specifications into `docs/` moved 245 comment lines and zero code lines, because the
comment mass is not concentrated — outside `ControlProtocol.java` only two files in the frozen
modules exceed 150 comment lines, and ~92% of what was examined is invariants, past-bug
explanations and public-API contract that the phase's own policy protects.

**So the reduction hypothesis was wrong in a specific and useful way.** Line count falls when the
same *logic* is written twice and one copy can go. It does not fall when the same *shape* is written
twice and both call sites survive with a named collaborator between them — that trade buys
correctness, not size. Phase 4 is worth keeping on those grounds alone: it removed three divergent
JSON escapes, one of which emitted invalid output, and unified a path-traversal guard that existed
in two disagreeing versions.

**What the phases found that was not line count**, and is worth more than the 1,396 lines:

- `library/rust/nodera-codec/tests/mutation.rs` read its wire tag from the first two bytes of the
  `NDR2` **magic**, so every fixture fell through to `DiscoveryMessage` and the rendezvous, service
  and consensus frames were decode-failed and swallowed by an `if let Ok(...)`. The
  canonical-mutation invariant had only ever been asserted on one message family. Green throughout.
- `WorkerTelemetryService.escape` replaced `"` with `'` instead of escaping it, silently altering
  every error string carrying a quote in the `NODERA-TELEMETRY` reply.
- `PeerTrafficMeter#snapshot()` has no callers and is the per-peer table's only eviction path
  ([#218](https://github.com/Ashu11-A/NoderaMC/issues/218)).
- A shared fixture built through the widest constructor with `null`s orphaned the 11-argument
  `WorkerControlHandler` overload that `ConfigVerbIT` was the only caller of — a consolidation
  erasing an API's only exercise, visible to nothing but the structural report.
- `structureReport` records call edges only from production-origin bytecode, so 92 of 263 candidate
  "dead" methods are test-driven. **Phase 1 is downstream of phase 3, not parallel to it** — the
  plan had that ordering wrong.
- `jscpd` misses duplication that was *retyped* rather than pasted, so the nine validation ITs
  duplicating 30–80 lines apiece appear in no `REFACTORING.md` register at all.

**Three defects in the measurement tooling**, each surfaced by an agent declining to work around it:
gating comment counts made deleting documentation the cheapest way to go green; bucketing Java by
Gradle source set reported the `:testing` harness as production code, so a phase that removed 1,484
lines of duplicated setup read as +695 lines of growth; and a whole-tree ratchet does not compose
across parallel branches, so a branch can be red alone and green merged. All three are fixed.

**Ratchet files cannot be merged.** `#210` stamped `never_referenced_methods` 93 and `#213` stamped
134; the combined tree measures **91**. Taking either side silently loosens the gate, and a
too-generous limit never fails. Both `fixtures/structure/budget.json` and
`scripts/lib/loc-baseline.json` must be **re-measured on the merged tree**, never resolved as a text
conflict.

### Phase 1 is the only deletion phase

`fixtures/structure/budget.json` ratchets 267 unreachable methods, 136 methods nothing references at
all, 415 test-only methods and 29 test-only classes. `./gradlew :peer:structureReport` finds them and
its debugger-profiled run of the real `nodera-headless` worker confirms none of them execute. A
method no entry point can reach is not a feature.

The trap here is the limitation registers, which live one per category at
`docs/<category>/LIMITATIONS.md` — there is no `docs/LIMITATIONS.md` to open. A limitation row can be
staged on a green test that drives a class production never calls. Check `structureReport` §2.2
before deleting anything a row depends on, and never close an issue that any of those registers
tracks.

### Phase 2 keeps more than it moves

Public-API Javadoc stays. Consensus and wire invariants stay — those are the comments that stop
somebody breaking the protocol. What moves is historical narrative, restatements of what the next
line plainly does, private-method Javadoc, and license headers that duplicate `LICENSE`. Every moved
block leaves a pointer to the section that now holds it, so the code still says where to look.

### Phase 3's gate is the PASS count

`scripts/test-totals.sh --java` must report the same number of passing tests before and after. A
consolidation that quietly drops assertions is the most likely way this phase fails, and the count
is what catches it. Read the skipped column too: an `assumeTrue` guard against a path that moved is
a SKIP, not a failure.

### Phase 4's targets, with the paths verified

The programme was scoped against file sizes taken by `wc -l`. These are code lines, and the paths
are the ones that exist — the earlier scoping put `WorkerControlHandler` under `peer/control/` when
it lives in `peer/headless/`, which is exactly the drift a plan quietly dies of.

| file | code | comment |
|---|---|---|
| `peer/…/peer/validation/WorkerValidationService.java` | 2,026 | 546 |
| `peer/…/headless/WorkerControlHandler.java` | 1,439 | 579 |
| `peer/…/headless/WorldArchiveService.java` | 1,196 | 942 |
| `endpoints/neoforge-mod/…/common/NoderaHost.java` | 1,034 | 676 |
| `peer/…/peer/PeerRuntime.java` | 714 | 460 |
| `endpoints/neoforge-mod/…/common/NoderaPeerService.java` | 721 | 422 |
| `peer/…/headless/WorldHostingService.java` | 646 | 598 |
| `library/rust/nodera-core/src/core.rs` | 1,080 | 242 |
| `library/rust/nodera-core/src/settings.rs` | 1,058 | 397 |
| `library/rust/nodera-core/src/launch/mod.rs` | 692 | 121 |
| `app/ui/src/TrackerStores.tsx` | 939 | 113 |
| `app/ui/src/Settings.tsx` | 762 | 181 |
| `app/ui/src/components.tsx` | 691 | 155 |

Alongside them, three redundancies that are not files but patterns: nine `core/action/*` classes with
parallel `encode`/`decode`/`tag` (a sealed `GameAction` plus an `ActionCodec<T>` registry replaces
them, and the eight dead `decode(CanonicalReader)` entries the structural report flags are exactly
the decoders that disappear), 74 `LoggerFactory.getLogger(...)` sites, and five inline `*Json()`
builders in `WorkerControlHandler` open-coding string concatenation.

### Phase 5 needs two clients

Dropping `SessionKeepAlive` v1 is the one change here that no unit test fully clears. The exit is a
live `scripts/dev.sh --play` run after `scripts/nodera-test.sh all` is green.

---

## Gates

| gate | when | command |
|---|---|---|
| Java unit gate | every commit | `./gradlew check` |
| Rust gate | every commit | `cargo test && cargo fmt --check && cargo clippy --all-targets -- -D warnings` |
| Companion app | phases touching `app/` | the same three, inside `app/` — it is a separate cargo workspace |
| Size ratchet | every commit | `scripts/loc-metrics.py --selftest && scripts/loc-metrics.py --check` |
| Structural ratchet | phases 1 and 4 | `./gradlew :peer:structureReport` — `budget.json` must drop |
| Cross-language fixtures | phase 5 | `:transport:WireFixtureTest` + `cargo test`, byte-exact |
| Acceptance scenarios | every merge to the integration branch | `scripts/nodera-test.sh all` |
| Live two-player run | integration branch → `main` | `scripts/dev.sh --play` |

The size ratchet runs `--selftest` first, because the ratchet is worth exactly as much as the lexer
under it: a regression that made the classifier count comments as code would otherwise present as a
tree that shrank.

It gates the `*.code` limits only. Comment counts are measured, stamped and diffed — lever D is
nothing but a comment count — and a rise in them prints a note rather than failing. That exclusion
was bought with evidence: while phase 5 was extracting a codec table out of `MessageCodec`, the
comment bucket blocked the commit and the work was distorted to trim comments and pay for the new
file's header. Nothing valuable was lost that time; the documentation had moved with the code. But a
gate that makes deleting documentation the cheapest route to green is a gate aimed at the wrong
thing, and a programme whose whole justification is that comments carry the invariants cannot also
punish writing one.

---

## What this plan will not do

- **Remove a feature.** Not one, in any phase, under any measurement pressure.
- **Repackage `dev.nodera.research.*` out of the build.** Rejected; see Context.
- **Renumber a wire tag.**
- **Change code in `:core`, `:transport` or `:endpoint` outside phase 5.** Comments only until the
  version bump that is allowed to touch a frozen contract.
- **Lose test coverage.** The PASS count is flat or higher at every merge.
- **Hit 30% in Java by finding something else to call "not code".** If production Java lands at 24%,
  the plan reports 24%.

---
## Round 2 — re-baselined to 10%, on measured numbers

Round 1 targeted 30% and delivered 0.85%. Five read-only analyses then measured every lever the
plan had estimated. **Four of the five estimates were wrong, three of them badly, and in both
directions.** The target is now **10%**, no feature is removed, and every figure below came from
reading the tree rather than from reasoning about file sizes.

### The measurement was wrong a third and fourth time

Round 1 already corrected `:testing`'s bucket. The analyses found the same bug twice more, and it
is always the same mistake — splitting production from test by whatever is convenient for the
language rather than by what the lines are:

| | before | after | why |
|---|---:|---:|---|
| `.mjs` / `.js` | **not counted at all** | 4,555 code lines enter | 45 tracked files: both frontend test suites, the site's whole generator chain, two of `nodera-ui`'s three largest modules. Moving TypeScript into a `.mjs` file read as pure reduction |
| `rust.main` | 31,448 | **19,997** | Rust keeps unit tests in the file they test; 11,432 of 32,347 Rust code lines (35.3%) sat in `#[cfg(test)]` blocks counted as production |
| `ts` | one bucket | `ts.main` 12,720 + `ts.test` 3,217 | the split `java` and `rust` already had |

Cross-checked against two hand-counts made without the tool: `nodera-core` production 8,172 (agreed
exactly), `nodera-ui` 435 (agreed exactly), whole-tree Rust production 19,997 against 20,092.

**This is why `tracker/src/service.rs` appeared in round 1 as a 1,228-line monolith to split.** It is
417 lines of service and 811 lines of its own tests. Three of the four "Rust monoliths" round 1
named were ordinary files whose `wc -l` was dominated by a test module, and splitting all four
yields approximately zero.

### Baseline — 2026-08-06, corrected buckets

| bucket | files | code | comment |
|---|---:|---:|---:|
| `java.main` | 710 | 60,260 | 35,646 |
| `java.test` | 525 | 59,672 | 11,457 |
| `rust.main` | 111 | 19,997 | 7,476 |
| `rust.test` | 4 | 11,845 | 993 |
| `ts.main` | 97 | 12,720 | 4,172 |
| `ts.test` | 30 | 3,217 | 1,347 |
| **TOTAL** | **1,477** | **167,711** | **61,091** |

### The levers, measured

| lever | round 1 estimate | **measured** | who |
|---|---:|---:|---|
| Java production | 1,000–2,000 | **5,500 / 6,300 / 7,000** | Analyst 3 |
| Java test | 8,000–12,000 | **3,800 / 4,200 / 4,900** | Analyst 1 |
| Rust production | 1,000–2,000 | **700 / 900 / 1,000** | Analyst 4 |
| Rust inline tests | not sized | **400 / 550 / 800** | Analyst 4 |
| TypeScript | ~1,000 | **300 / 380 / 500** | Analyst 5 |
| Wire generation | 4,000–5,000 | **400 / 500 / 600** (TLV plane only) | Analyst 2 |
| **TOTAL** | | **11,100 / 12,830 / 14,800** | |
| **as % of 167,711** | | **6.6% / 7.7% / 8.8%** | |

**10% is 16,771 lines and the measured ceiling is 14,800.** The gap at the central estimate is
3,941 lines. That shortfall is stated here rather than closed by optimism, and §"Reaching the last
1.2 points" says what it would actually take.

### What each analysis overturned

**Java production is worth three times what round 1 thought, and phase 3 unlocked nothing.** Round 1
claimed 92 test-driven dead-code candidates became deletable once the test harness consolidated.
They did not: `test_only_methods` is still 415 and `test_only_classes` still 29, because a method a
test calls is still a method a test calls after the test moves. Phase 3 in fact *added* two
never-referenced constructors. The real reason phase 1 stopped at 45 methods is that it deleted
**methods** rather than **clusters**: a dead method inside a live class is a 5-line win, while the
retired central-coordinator design is 1,467 lines in one go. A transitive-closure over `java.main`
from real entry points finds **63 files, 3,933 code lines, unreachable from any production entry
point** — and `docs/network/REFACTORING.md` already carries an explicit *Delete* verdict on most of
them.

**The test lever is a third to a half of what round 1 assumed**, because round 1 read jscpd's
*percentages* as removable-line fractions. `HeldVersionBeatsAnUnreachableNewerOneTest` is "68.4%
duplicated" and is a 79-line file, of which about 15 survive the extraction cost. The calibration
was already in the programme's own record: phase 3 consolidated the largest cluster in the tree and
netted −768. The biggest lever in `java.test` turns out not to be duplication at all — it is
**7,207 lines of `import` and `package` declarations, 12.1% of the bucket**, which no register can
see because jscpd does not treat an import block as a clone.

**Wire generation should not be done as scoped.** Gross generatable is 3,737 lines, but the net after
annotations, the generator itself and the (c) exclusions is ~1,800 — and **50 of the 91 `Encodable`
types have no golden fixture at all**, nine of which are on-disk formats. Generating encoder and
decoder from one schema means the round-trip test passes while the bytes are wrong, and for those
nine the failure is not a network split but every existing player's saved world, node identity and
world registry becoming unreadable, with a green build. Only the TLV plane — fixture-covered,
unsigned, forward-compatible, totality-tested — is worth generating.

**The `Encodable` frame helper was buried inside that row and is worth doing on its own.** The decode
guard `int tag = r.readU16(); if (tag != TypeTags.X) throw …` appears **106 times across 86 files**,
5 source lines each; the encode pair appears 77 times. A `CanonicalReader#expectFrame(int)` and
`CanonicalWriter#writeFrame(int)` remove **~400 lines** and are byte-identical on the wire. This is
precisely the construct that produced phase 5's −399, and round 1 hid it inside a codegen programme
and deferred it.

### The order of work

Ranked by lines per unit of risk, not by lines.

| # | item | lines | confidence |
|---|---|---:|---|
| 1 | **Delete the 63 unreachable files** at cluster granularity — the retired coordinator design (1,467), the archival/discovery cluster (1,108), storage test doubles living in `src/main` (248), orphans with no register row (305) | 3,560 | high |
| 2 | **The 91 `never_referenced` methods inside surviving files** — no test change, no capability question | 710 | high |
| 3 | **`Encodable` frame helper** — `expectFrame` / `writeFrame` | 400 | high |
| 4 | **`@Nested` grouping of sibling test classes** — 12.1% of `java.test` is declarations | 2,000–2,500 | high |
| 5 | **One engine fixture instead of seven** (`CommFixtures`, `CoordFixtures`, `shadow/Fixtures`, `FbFixtures`, `simulation/TestFixtures`, `StoreFixtures`, `DistFixtures` = 606 lines), plus `executeTicks` ×18 and `blockAt` ×9 | 700–850 | high |
| 6 | **Consolidate the four network services onto `nodera-service`** — 1,020 jscpd-measured duplicated production lines; the three `build.rs` are **byte-identical** and have been since 2026-07-28 | 700 | high |
| 7 | **Residual `unreachable` methods** once their cluster roots go | 800 | medium |
| 8 | **`ControlServer.dispatch`** — a 27-arm string-constant ladder, 147 lines, to a verb table | 80 | medium |
| 9 | **`ArchiveMesh` fixture** for the eight-file archive cluster | 100–125 | high |
| 10 | **`SpawnedService` fixture** — and it is the fix for the 12 skips (see below) | 80–110 | high |
| 11 | **TypeScript**: 25 exported symbols with zero references (216), one `Banner`, `RowText`, `usePoll` | 380 | high |
| 12 | **TLV-plane generation** — only after the fixture gap is closed | 400–600 | medium |
| 13 | **`@ParameterizedTest`** on 9 measured clusters — it is on the classpath and used **zero times** in 525 files | 350–450 | medium |
| 14 | **`core.rs` retyped clusters** — the "a launch failure always closes its tunnel" rule is written **six** times, not the three the register records | 100 | high |
| 15 | **`nodera-core/telemetry.rs`** — the one hand-rolled parser in the Rust tree, replaced by a derive | 45 | high |

Items 1–3 are Java production and land 4,670 lines on their own. Items 4, 5, 9, 10 and 13 are the
test lever. Nothing in this list removes a feature and nothing removes an assertion.

### Reaching the last 1.2 points

The measured ceiling is 8.8%. Closing to 10% needs 1,971 more lines than the optimistic case, and
there are exactly three honest ways:

1. **Take item 4 at its full 3,002 rather than the discounted 2,000–2,500.** The discount exists
   because 47 files in `peer/…/headless` become ~12 files of 400+ lines, against a house style of
   one behaviour per class with a Javadoc naming the live failure it came from. That is a style
   decision, not a refactor, and it should be made deliberately rather than absorbed.
2. **Re-bucket `peer/src/jmh` out of `java.main`** — 482 lines, the same argument that moved
   `:testing`. This is a measurement correction, not a reduction, and must be reported as one.
3. **Accept that 10% is the wrong number and 8% is the right one.** Round 1 committed to reporting
   what it measured rather than what it hoped; the same rule applies here.

There is no fourth way that does not remove a feature.

---

## The error-rate programme

The second objective, and on the evidence of round 1 the more valuable one: five phases produced
1,396 lines and found defects worth considerably more. This section is what the analyses concluded
about *why*, and what would catch the next one mechanically.

### The pattern, stated once

Every confirmed defect in this programme is the same shape wearing different clothes: **the
repository is excellent at writing the mechanism and inconsistent at writing the edge that proves
the mechanism runs.**

- the mutation test read its tag from the `NDR2` magic → nothing asserted how many message families
  were exercised;
- `PeerTrafficMeter#snapshot()` → nothing asserted the eviction path has a caller;
- the orphaned 11-argument constructor → nothing asserted each overload has an exercise;
- three divergent JSON escapes → nothing asserted there is one escape;
- `assumeTrue` on a moved path → nothing asserted the guard passed;
- suites asserting on log strings → nothing asserted the string is the one production writes.

In every case a value was produced and **nothing asserted anything about the count of things that
value covered.** The fix class is identical in all six: *make the gate report a cardinality, and
ratchet it.* That is exactly what `fixtures/structure/budget.json`, `scripts/lib/loc-baseline.json`
and `token-audit.mjs`'s `inspected > 100` already do. The programme built the right instrument three
times and never generalised it.

### The checks, ranked by value ÷ false-positive cost

| # | check | flags **today** | false positives |
|---|---|---|---|
| 1 | **`scripts/test-totals.sh --tap`**, parsing `node:test`'s `# pass N`, wired into `build-app-ui.sh` and `build-site.sh` with a stamped count | 0 defects, but brings **209 uncounted frontend tests** under a gate | **zero** — arithmetic |
| 2 | **Build the binaries the 12 skips wait for** | **12 skips that have never run in the main gate** | zero |
| 3 | **A reference check** — every exported symbol has ≥1 reference outside its defining file, with a ratcheted allowlist | **25 TS symbols, 10 Rust `pub fn`** | ~20%, causes known: bare function references, trait dispatch, re-exports, `.mdx` consumers |
| 4 | **A vacuity guard** — an asserting loop over a derived collection needs a cardinality assertion first | **9 unguarded loops** | ~15%, → ~5% scoped per-file |
| 5 | **Duplicate-component-name check** (TS) | 1 (`Banner` ×2) | ~0 for the name half |

Checks 1 and 2 are free and should land first. They measure rather than judge, which is the lesson
the size ratchet already taught: *its value turned out not to be the reduction at all.*

### `node --test` on an empty glob exits 0

Verified by running it: `# pass 0`, `# fail 0`, **exit 0**. `app/ui/package.json` and
`web/package.json` both end their gate with `node --test "tests/*.test.mjs"`, and neither
`scripts/build-app-ui.sh` nor `scripts/build-site.sh` asserts a count. **209 frontend tests could
vanish from this repository without anything turning red.** `scripts/test-totals.sh`'s own header
says *"a job that skips into green asserts nothing and still renders passing"* — it has `--java` and
`--cargo` and no TAP mode.

### The 12 skips have never run in the main gate

All twelve enumerated: 7 × `TrackerServiceIT`, 1 × `RendezvousRelayIT:89`, 1 ×
`WorldContinuityIT:92`, 1 × `CompanionCrashSurvivalIT:117`, 2 × `TelemetryRegistryMirrorTest`. Every
one is "a cargo binary or the peer distribution was not built". `.github/workflows/build.yml:28`
runs `./gradlew check build` in the `java` job; `cargo build --release --bin nodera-tracker` is at
`:149` in a **different** job. So `2,423 passed / 12 skipped` overstates what CI proves, and
`docs/testing/TESTING.md`'s own rule — anything the build produces is built by the runner rather
than waited for — is the fix.

### Defects to file, ranked

Behavioural, user-visible or security-relevant:

1. `library/java/transport/.../RendezvousPeerTransport.java:535` — `onDrainNotice` has **no caller
   anywhere**, so `drainHandlers` is permanently empty. **The service-drain migration lane from
   PR #78 does not run on any node**; a peer never moves off a draining relay, it waits for the
   circuit to break.
2. `peer/.../distribution/WorldArchive.java:178` — the weakest of the tree's **three** path-traversal
   guards is the only one whose input is attacker-controlled (zip-entry names from a downloaded
   world archive). No `toAbsolutePath()`, and the class Javadoc claims an absolute-path refusal that
   is not in the code. Phase 4 already unified two disagreeing copies of this guard; this is the third.
3. `peer/.../validation/WorkerValidationService.java:1531` — `validateTransferPlan` (the proposer's
   own check) omits four clauses that `validateTransferSide` (every remote member's check) enforces.
   A proposer can broadcast a plan its own committee will unanimously refuse.
4. `tracker/src/deletion.rs:190-203` — the tombstone write is guarded, the **rename is not**, and
   neither failure is logged. A full disk silently un-deletes a world at the next restart — the
   resurrection the world-deletion design exists to prevent.
5. `library/rust/nodera-core/src/daemon.rs:542` — `while let Ok(Some(line))` treats a non-UTF-8 line
   as end-of-stream, permanently killing the worker log pump. The dashboard's log panel goes empty
   and stays empty with no error anywhere.
6. `rendezvous/src/config.rs:50` — `circuit_idle_timeout_seconds` is loaded, env-overridable and
   **validated**, and nothing reads it; the real timeout is hard-coded at `wire.rs:366`.
7. `tracker/src/service.rs:318` — `let _ = outcome;`. `ServiceOutcome`'s four variants have no reader
   in the tree, so a relay draining a service it never registered is told "accepted".
8. `library/rust/nodera-codec/tests/mutation.rs:38` — `WORLD_REVIVAL_GOSSIP` (76) falls through to
   the discovery decoder and every mutation of that fixture is silently skipped. Same shape as the
   magic-bytes bug already fixed in the same file; `fixtures.rs` handles tag 76 correctly, which is
   why the two disagree.
9. `library/rust/nodera-codec/src/tags.rs:157-160` — four tracker tags are declared supported and
   implemented in Rust, but their fixtures live in `java-only/`, which `tests/fixtures.rs` does not
   read. Four live tracker paths are never held to the Java bytes.
10. Eight NeoForge `Server*Event` handlers that do not self-catch, each beside a guarded sibling in
    the same file — `ServerBootstrap.java:227` `onPlayerLoggedOut` is the worst, six unguarded calls
    including archive seeding, while its login twin at `:190` is guarded with the comment "must never
    take the server down with the player mid-login".
11. `library/java/core/.../StableHashTest.java:21` — computes the expected value by calling the
    function under test, then asserts equality. Its Javadoc claims it detects any change to the
    mixing algorithm. It detects nothing.
12. `library/rust/nodera-core/src/settings.rs:977` — a caller-supplied closure runs while holding the
    settings lock; one panic poisons it and every subsequent `snapshot()` panics forever.

### What this round will not do

- Remove a feature. Not one, under any measurement pressure.
- Generate the strict canonical plane. 50 of 91 value types have no golden bytes; nine are on-disk
  formats where a wrong generated encoder is silent data loss.
- Convert enumerated rule tests to properties. A jqwik `@Property` reports **one** test where the
  cases it replaces reported N, so the flat-PASS-count gate and that lever are mutually exclusive as
  written — and for the redstone and fluid tests a generator would assert only determinism, which
  `DeterminismPropertyTest` already covers.
- Claim a re-bucketing as a reduction.

---

## Round 2 — what it delivered, and what round 3 must assume

Executed 2026-08-06 by five agents in parallel worktrees (#225 frame helper, #226 frontend+gates,
#227 rust services, #228 dead clusters, #229 test consolidation), then two reviewers, then three
fixers (#234 gate + regression tests, #235 documentation truth). Merged into
`refactor/reuse-driven-shrink` = PR #222.

**Result: 167,711 → 158,551 code lines, −9,160, −5.46%.** Both endpoints are stamped and re-derivable —
`git show f507eff:scripts/lib/loc-baseline.json` and `git show 4589d0c:…` — and this is round 2's
figure, not the programme's: round 3 was a correctness round and it bought its fixes with lines.
**The programme's result is [§Round 3](#round-3--the-correctness-round-and-the-programmes-final-numbers).**

| branch | claimed | measured |
|---|---:|---:|
| dead clusters | −7,947 | −7,947 ✅ |
| test consolidation | −976 | −976 ✅ |
| rust services | −426 | −426 ✅ |
| frontend + gates | −132 | −132 ✅ |
| frame helper | −204 | **−164 merged** (20 sites sat in files the deletion removed) |

Gate on the merged tree at round 2's end: `./gradlew check` green, **2,243 Java passed / 0 failed / 0
skipped**,
`cargo test` 730/0/1, clippy zero warnings, every script gate green.

### The skip count reached zero

Twelve tests had never once run in the main gate — each waited on a cargo binary or the peer
distribution that the `java` job never built, and an `assumeTrue` on a missing artefact reports as a
skip rather than a failure. The workflow now builds them before `check` and asserts `skipped == 0`,
and `SpawnedService` turns a missing binary into a failure at the suite. **2,423 / 12 skipped
became 2,243 / 0 skipped** — the fall in passes is 43 test files leaving with the design they
tested, verified class-by-class: every one referenced a deleted class and could not have compiled,
and every surviving class they also drove retains production callers and other tests.

The `2,423 / 12` end of that is verified: the `java` job on `61e0936`, the last commit before round 2
executed, reported `{"source":"java","passed":2423,"failed":0,"skipped":12}` (run 31070756099). The
`2,243` end is not — no retained CI run covers round 2's merge commit, so it is a figure this document
asserts and nothing re-derives. **Neither number describes the current tree**; round 3 added tests and
the head reports 2,272 / 0 / 0. §Round 3 carries the ladder.

### What round 3 must assume

Round 2 delivered −9,401 at merge against a re-baselined estimate of 11,100–14,800 — 85% of the
conservative case, **but only because 4,236 of those lines were test files deleted alongside dead
production code**, a windfall no lever in the estimate table accounted for and which is now
permanently spent. Excluding it, the levers the plan actually budgeted returned **5,165 of 11,100 —
47%**. The error is not random: every estimate built by *naming the specific files to change* was
accurate or conservative (item 1: 3,560 estimated, 3,711 landed; item 9: 125 estimated, 241
landed), and every estimate built by *extrapolating "this construct appears N times × M lines
each"* was wrong by 3–14× (item 4: 2,500 → 274 against a true greedy ceiling of 1,330; item 13:
450 → 32; item 3: 400 → 164; item 11's census of 25 dead TypeScript exports contained 13, and two
of those were live lanes that needed wiring, not deletion). **Round 3 must price no lever it cannot
express as a list of files, and must apply a ~50% discount to any construct-count extrapolation
that survives that test.** Three levers are measured, named and now at zero conflict risk: the
seven-into-one engine fixture consolidation (606 lines — never attempted; round 2 added an *eighth*
fixture and did only the `executeTicks` preamble), the `@Nested` groups deferred solely to avoid
conflicting with the deletion branch (~284 in `peer/…/validation` and `peer/…/distribution`), and
~49 remaining frame-helper sites outside `:core`/`:transport` (~150 lines). Roughly 1,000 lines of
*named-file* work, and it should be stated as such rather than grossed up.

**The round's most valuable output was not lines.** `--tap`, the stamped zero-skip gate and the
built service binaries brought 210 uncounted frontend tests and 12 never-executed integration tests
under a gate for the first time; eight of the twelve listed defects were fixed and the remaining
four are filed (#230 path traversal, #231 unguarded event handlers, #232 drain lane, #233 proposer
validation), plus #236 for a contradiction the round created. Round 3 should be budgeted with the
error-rate half first and the reduction half second, because that is the order in which round 2
actually created value.

### Traps this round confirmed

- **A ratchet file cannot be merged.** `budget.json` carried one branch's stamp into the merge while
  its own `measured` field claimed it had been re-measured. Both ratchets are now re-taken on the
  merge commit, every time.
- **`git stash` is shared across worktrees.** An agent ran it in its own worktree and popped a user's
  parked work. Never use it in agent work.
- **A test-count gate is only useful if it moves.** `test-counts.sh --check` failed on five stale
  rows after the Rust consolidation, and again on three after the follow-up fixer added regression
  tests. It said so both times.
- **A read-only analysis cannot see a test that enforces a call site.** Twelve "dead" TypeScript
  exports were held live by `ux-honesty.test.mjs` asserting every registered Tauri command has a
  frontend caller.

---

## Round 3 — the correctness round, and the programme's final numbers

Executed 2026-08-06/07 by five agents in parallel worktrees (the EventBus and traversal fixes, the
dead-lane wiring, the delegability decision, the named-file levers, the error-rate checks), then a
five-validator pass, then eight fixers. Head at the time of writing: `6ab62f1`.

**Round 3 was not a reduction round and its stamped baseline says so: `158,551 -> 159400`, a
deliberate rise, paid for a live symlink-traversal hole, a game-bus handler that could crash the
integrated server, a `SpawnedService` deadline that could never fire, a teardown guard that skipped
the tree's only caller of `PlayerNodeRegistry.forget`, a restored test for a class on the live
validation path, and two gates whose entire purpose is to fail.** The full accounting is in
`scripts/lib/loc-baseline.json`'s `measured` field, which is where it belongs: the ratchet permits a
rise only when it is stamped in the same commit, so the reason is a reviewable line in a diff.

### The one table

Three different headline results were in circulation — −0.85%, −5.46%, −4.96% — and neither of the two
a reviewer met was reproducible on the head they were reading. All three were true of something; none
of them was true of the tree. So, once, against one endpoint, on today's bucket definitions:

| bucket | `main` (`9959df1`) | head (round 4) | delta |
|---|---:|---:|---:|
| `java.main.code` | 60,815 | 56,395 | −4,420 |
| `java.test.code` | 60,440 | 55,131 | −5,309 |
| `kotlin.main.code` | 3,999 | 4,012 | **+13** |
| `kotlin.test.code` | 21 | 21 | 0 |
| `rust.main.code` | 20,058 | 19,698 | −360 |
| `rust.test.code` | 11,834 | 12,264 | **+430** |
| `ts.main.code` | 12,743 | 12,588 | −155 |
| `ts.test.code` | 3,204 | 3,544 | **+340** |
| **total code** | **173,114** | **163,653** | **−9,461 (−5.47%)** |
| total comment | 61,648 | 61,830 | **+182** |

**−9,461 code lines, −5.47%, against a 30% target re-scoped to 10% and a measured ceiling of 14,800.**
The rises are the programme buying correctness with tests, and they are the honest half of the number.

Two things about this table changed after round 4's review, and both are the reason it is stated with
its endpoint named rather than as a bare percentage:

* **Kotlin is in the gate now, and it was not when the earlier version of this table was written.** It
  adds 4,033 lines to the head and 4,020 to `main`, so it moves both endpoints and is a re-bucketing,
  not growth — but a reader comparing this total to any figure quoted before `c738443` is comparing two
  different lexers. The earlier table read `169,094 → 159,400 = −9,694, −5.73%`; every one of those
  four numbers was correct for the definitions it was measured under, and none of them is comparable to
  a number from this one. Excluding Kotlin from both ends gives 169,094 → 159,620, which is the same
  programme result seen through the older, narrower lens.
* **The Rust split moved without the tree moving**, by +54/−54 between `rust.main` and `rust.test`:
  `#[cfg(test)] mod test_support;` is an unbraced item, and the splitter used to run past it to the
  next depth-0 brace, so the whole body of `async fn main()` in both the tracker and the rendezvous
  was counted as test code. `scripts/lib/loc-baseline.json`'s `measured` field carries the full
  decomposition.

Reproduce the head column with `scripts/loc-metrics.py --json`. The `main` column needs one step of
setup, because **`scripts/loc-metrics.py` does not exist on `main` — the whole measurement apparatus,
this ratchet included, is introduced by this branch.** So it is run against `main`'s tracked file set
rather than from it:

```bash
git archive main | tar -x -C /tmp/main-tree
cd /tmp/main-tree && git init -q . && git add -A && git -c user.email=a@b -c user.name=a commit -qm x
cp -r <this-branch>/scripts/loc-metrics.py <this-branch>/scripts/lib /tmp/main-tree/scripts/
python3 scripts/loc-metrics.py --check         # OK — 173114 code, 61648 comment
python3 scripts/loc-metrics.py --json          # the same, per bucket; sum the sixteen for a total
```

Untracked files are not counted, which is what makes copying the tool in safe: the number describes
`main`'s tree and nothing else.

### Why `main` measures 169,094 and §The measurement says 164,552

The same commit, two definitions, and the difference reconciles exactly:

| | round 1's definition | today's | difference |
|---|---:|---:|---:|
| Java (main + test) | 121,255 | 121,255 | 0 |
| Rust (main + test) | 31,892 | 31,892 | 0 — a pure re-bucketing |
| TypeScript + `.mjs`/`.js` | 11,405 | 15,947 | **+4,542** |
| **total** | **164,552** | **169,094** | **+4,542** |

Every line of the difference is `.mjs`/`.js` that the tool did not count at all — the site's generator
chain, both frontend test suites, two of `nodera-ui`'s largest modules — and the Rust correction moved
11,409 lines from `main` to `test` without changing the total. Java is identical to the line.

This is why round 1's `−1,396 (−0.85%)` is labelled superseded rather than corrected. Re-measured
under today's definitions the same pair of commits is **169,094 → 167,711, −1,383**: the number was
right, and it was still being quoted as the programme's result two rounds later.

### The stamped ladder

Every step is `scripts/lib/loc-baseline.json` in this branch's history, so the whole programme is
re-derivable with `git show <commit>:scripts/lib/loc-baseline.json`:

| commit | what it stamps | total code |
|---|---|---:|
| — | `main`, measured with this branch's tool | 169,094 |
| `f507eff` | round-1 merge, buckets corrected for the third and fourth time | 167,711 |
| `b60a34d` | round-2 merge, re-measured rather than merged | 158,310 |
| `4589d0c` | round 2 after its review follow-ups | 158,551 |
| `0dcb74f` | round-3 merge | 159,048 |
| `ef48cea` | a restored test for `CommitteeMember` | 159,125 |
| `80007ac` | a frontend gate that had been counting nothing | 159,144 |
| `a1d54d3` | a `SpawnedService` deadline that could never fire | 159,298 |
| `6ab62f1` | the validation pass: the path guard, the handler self-catch, the teardown split | 159,400 |

`-8,311, -4.96%` — the figure `loc-baseline.json` reports — is this ladder from `f507eff` down, and it
is correct for what it measures: the two rounds that had a stamped starting line. The programme total
is the row above it, because the programme started on `main`.

### Gate state on this head

Measured, not remembered. Every counter below comes from a local `./gradlew check` on this head or
from a file in this commit, and each is reproducible by the command in its source column.

| gate | value on this head | source |
|---|---|---|
| Java tests | **2,271 passed / 0 failed / 0 skipped** | `scripts/test-totals.sh --java`; the `Nothing skipped into green` step fails on any skip at all |
| Java per module | `core` 314 · `endpoint` 114 · `engine` 446 · `neoforge-mod` 134 · `paper-plugin` 20 · `peer` 850 · `storage` 158 · `testing` 46 · `transport` 189 | `scripts/test-counts.sh --check java`, which now holds these nine cells instead of leaving them typed |
| `loc-metrics --check` | `OK — 163653 code, 61830 comment, within baseline` | zero headroom on all sixteen buckets, by construction of `--baseline` |
| `loc-metrics --selftest` | 54 cases (22 lexer, 32 gate) | five of them are round 4's regression tests for the lexer bugs found in this tooling |
| `reference-check --check` | 0 unreferenced, 18 allowlisted, 12 test-only, 153 exported-but-local | `fixtures/structure/reference-allow.json`; the walker now asks about `pub` items and fields, not only `pub fn` |
| `dead_classes` | 0 | `fixtures/structure/budget.json` |
| `test_only_classes` | 10 | same |
| `never_referenced_methods` | 2 | same — no headroom; both are filed defects kept fixable-by-wiring |
| `test_only_methods` | 309 | same |
| `unreachable_methods` | 112 | same |
| `huge_methods` | 0 | same |
| `severe_cost_findings` | 3 | same |
| `fixtures/wire/` | every tracked byte identical to `main`; four `.bin` files **moved** out of `java-only/` | `git diff main --stat -- fixtures/` |

The test ladder, for the same reason the line ladder is written out: **2,423 / 12 skipped** on
`61e0936` (run 31070756099, the last commit before round 2 executed) → **2,243 / 0** claimed at round
2's merge, which no retained run covers → **2,267 / 0** on `44069df` just after the round-3 merge (run
31137013033) → **2,272 / 0** at the round-3 merge → **2,271 / 0** on this head. The fall from 2,423 is
43 test files leaving with the design they tested; the rise across rounds 3 and 4 is the regression
tests they added. The last step is a fall of one that is really −5/+4: `SaveRegionTest` left with
`SaveRegion`, and the wire, harness and frame-refusal invariants round 4 pinned arrived.

### What this round is actually worth

Round 2 already recorded that its most valuable output was not lines. Round 3 is the round where that
stopped being a consolation and became the plan: it went **up** 849 lines and closed a traversal hole,
a crash path, a deadline that never fired, a cleanup step that was being jumped over, and two gates
that were reporting agreement about nothing. A programme whose only reportable number is a size
delta cannot price any of that, which is the limitation of the metric rather than of the work.

**And the metric had to be defended three separate times to mean anything at all.** Round 1 corrected
`:testing`'s bucket; round 2 found the same mistake twice more; the validation pass found `.mjs`
droppable from `_EXTENSIONS` with `--selftest` still 18/18 and `--check` still green, reporting a
phantom −4,882-line reduction, and found two `#[cfg(test)] mod test_support;` declarations pulling the
whole body of two `async fn main()` into `rust.test`. Every one of those was the tool making the tree
look smaller. **A reduction programme's tooling fails in exactly one direction, and a number nobody
can re-derive from a commit is not evidence — which is the whole reason this section exists in the
shape it does.**

### The next stamp is not this one

`fixtures/structure/budget.json` and `scripts/lib/loc-baseline.json` are re-measured on the merge
commit, every time, and the five fix branches in flight when this section was written each change the
tree. **The tables above describe `6ab62f1` and are expected to be superseded at the merge.** They are
dated and sourced so that superseding them is an edit somebody makes on purpose, rather than a number
that quietly stops being true — the failure this whole section is a correction of.
