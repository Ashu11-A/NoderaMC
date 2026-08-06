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

The programme's goal is a directional **30% reduction in code lines**, achieved by eliminating
redundancy, extracting shared primitives and moving specifications into `docs/` — never by removing
a feature. Repackaging `dev.nodera.research.*` out of the build was considered and rejected: that is
feature removal wearing a refactor's clothes.

**The 30% figure is a target, not a gate.** TypeScript and Rust are expected to reach it;
production Java is expected to land nearer 24% once the reuse work is exhausted. CI does not fail
because Java lands at 26%. What CI *does* enforce is that the tree never grows past its last stamped
measurement without somebody stamping a new one in the same commit.

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

## What would actually reach 30%

The programme delivered 1,396 of the 49,366 lines a 30% cut requires — **2.8% of the goal**. The
question that matters now is not "why so little" but "what shape of work reaches the rest", and the
tree answers it before any plan does.

### The distribution rules out per-file work

Measured on the merged tree, 163,630 code lines across 1,430 files:

| | code | share |
|---|---:|---:|
| top 10 files | 11,433 | 7.0% |
| top 25 files | 21,931 | 13.4% |
| top 50 files | 34,974 | 21.4% |
| top 100 files | 53,285 | 32.6% |
| top 400 files | 107,990 | 66.0% |

Java production has a median file of **47.5 code lines**, and the 418 files at or below 60 lines —
59% of all production files — hold 19% of the code. This is a long tail, not a few monoliths.

The consequence is arithmetic: **deleting the fifty largest files in the repository outright would
yield 21.4%, still short of the target**, and those files are the ones that cannot be deleted. Any
plan built on "decompose the god-classes" is bounded well under 10% before it starts, which is why
phase 4 came out at +26 lines and would have come out near zero even if it had reached all twelve of
its targets. A 30% cut has to be a change that touches hundreds of files at once.

### What is reachable, with the measurement behind each

| lever | measured basis | realistic yield |
|---|---|---:|
| **Generate the wire layer** | 75 `Encodable` types = 6,116 lines, ~1,726 inside hand-written `encode`/`decode`; Java `CodecRegistry`+`MessageCodec` 878; `InfrastructureCodec` 711; Rust `wire`+`codec`+`tags`+`frame` 1,476; `rendezvous/src/wire.rs` 740; `MessageSamples` 450 | **4,000–5,000** |
| **Continue test consolidation** | `java.test` is 59,672 lines — 36.5% of all code, with a test:production ratio of 1.07 in `:peer` and 1.24 in `:engine`. 176 candidate rows remain open across nine `REFACTORING.md` registers; the `ArchiveFixture` cluster alone is six files at 36–68% duplication | **8,000–12,000** |
| **Phase 1 re-run after phase 3** | 92 of 263 dead-code candidates were test-driven and unlandable until the harness consolidated; that blocker is now gone | **1,000–2,000** |
| **Rust monolith splits** | `core.rs` 1,080, `settings.rs` 1,016, `tracker/src/service.rs` 1,228, `update.rs` 782 — plus hand-written serde parsing that `#[derive(Deserialize)]` replaces | **1,000–2,000** |
| **TypeScript primitives** | `TrackerStores.tsx` 916, `Settings.tsx` 762 — phase 4 reached neither | **~1,000** |

**Ceiling: roughly 15,000–22,000 lines, or 9–13% of the tree.** That is what disciplined,
behaviour-preserving reduction can produce here, and it is worth doing — the test lever alone is
larger than everything the five phases delivered combined.

### 30% requires removing scope, not restructuring it

The remaining ~27,000 lines do not exist because the code is badly written. They exist because the
project does that much. Reaching 30% means one of:

1. **One wire implementation instead of two.** Java and Rust each carry a full encoder, decoder and
   tag table for the same protocol — roughly 10,400 lines across both sides. The repository already
   generates `library/rust/nodera-codec/src/kinds.rs` from `WireRegistry.java`, so the mechanism
   exists and is trusted; extending it from the tag table to the message bodies is the single
   largest structural lever available, and generated lines are excluded from the ratchet because
   they are not maintained lines.
2. **Fewer delivery targets.** `paper-plugin` (784), the telemetry plane (3,488), the Android lane,
   the website — each is real scope, and each is a product decision rather than a refactor.
3. **A lower test:production ratio.** Going from ~1.1 to ~0.6 removes ~25,000 lines. That is only
   honest if enumerated cases are replaced by property-based ones that cover strictly more, not by
   deleting assertions — and phase 3's flat PASS count is the discipline that would have to hold.

### Recommendation

Re-baseline the target. **30% is not a property this codebase can have while doing what it does**,
and the plan committed to reporting that if the work showed it. Replace it with two commitments the
tree supports:

- **A measured 10% code reduction** from the levers above, in the order given — test consolidation
  first, because it is the largest and the phase-1 unblock depends on it.
- **The ratchet stays.** Its value turned out not to be the reduction at all. Across five phases it
  caught three defects in its own design, and the phases it gated found a mutation test asserting on
  one message family, an escape corrupting telemetry replies, an unbounded map, and an orphaned API.
  A gate that makes the tree's size a reviewable number is worth keeping whatever the number does.
