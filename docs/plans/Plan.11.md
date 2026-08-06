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

The trap here is `docs/LIMITATIONS.md`: a limitation row can be staged on a green test that drives a
class production never calls. Check `structureReport` §2.2 before deleting anything a row depends
on, and never close an issue that `docs/LIMITATIONS.md` tracks.

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
