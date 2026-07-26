# Network — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the network category. "Permanent" is a banned classification.
     Every §B row has an owning task and an EXIT TEST and retires only when that exit test is green —
     verify against the exit clause, never the prose. A newly discovered limitation enters §B as OPEN
     with a path, an owner, and an exit test BEFORE the discovering PR merges. Never delete a row:
     move it to LIMITATIONS.fixed.md with its evidence. Update in the SAME commit that stages or
     retires a row. -->

**Category:** network · **Last audit:** 2026-07-25 · Open or retiring rows: **3**

Status values: `OPEN` → `RETIRING` → `RETIRED` (row moves to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

---

## §A — Envelope constraints (never denied, engineered around)

| ID | Constraint (immovable fact) | Mechanism that hides it | Owner |
|---|---|---|---|
| A-2 | All-to-all validation is O(n²) | Regional committees — messaging is O(committee) per region ([engine 5](../engine/Task.5.md)); bulk traffic goes direct P2P ([task 2](Task.2.md)) | engine 5, network 2 |
| A-3 | Under partition, safety beats liveness: a minority partition cannot commit its regions | Regions pause, they never fork; forward sync on rejoin ([task 3](Task.3.md)); dynamic committee reconfiguration shrinks the blast radius ([engine 12](../engine/Task.12.md)) | network 3 → engine 12 |
| A-4 | NeoForge payload caps (≤ 1 MiB clientbound, < 32 KiB serverbound) | Chunked zstd streams ([task 1](Task.1.md)); content-addressed multi-seeder transfer ([task 4](Task.4.md)); a direct P2P bulk lane ([task 2](Task.2.md)) | network 1, 2, 4 |

---

## §B — Staged capabilities (the burn-down list)

| ID | Limitation today | Owner | Exit test | Status |
|---|---|---|---|---|
| L-30 | The P2P lane carries membership and certified state, and committee validation runs over it out of game — but the **live** mesh has still not been observed carrying validated state. `e2e-mesh-soak.sh` now drives the attempt (32 rounds of edits, mobs and movement over 180 s with two real clients) and reports what is actually there: the players' **client lanes** re-execute and vote (`active on 11 region(s)`), while all three headless workers report `votes_cast=0, votes_received=0, committee_commits=0`. The seats live on the clients, whose roots the control socket cannot see, so there are no two inspectable peers to compare — the same node question as **L-60**. The mechanism stays proven headlessly (`WorkerQuorumValidationIT`, `EventSyncOverTransportIT`). **Diagnosed 2026-07-26, and it is the test topology rather than the code.** A seat-count diagnostic added to the branch a live host actually takes (the field-of-view case where the server owns nothing) reported `resident peer(s) holding 0 committee seat(s)` on its first run. That discriminates the two candidate bugs: the seats are never *sent*, not sent-and-ignored. The reason is by design — `NoderaHost` does pass `residents.keySet()` into `EntityLaneBootstrap.plan`, and the planner staffs residents into every committee's **leftover** validator seats, so with three players and `QUORUM_MVP_SIZE = 3` the player nodes fill each committee and no leftover exists. Every suite that could show a worker validating runs two or three players, which is exactly the population that leaves residents unseated. **So the exit is reachable today without new code:** a drive with ONE player and the resident peers up gives every committee two leftover seats, and then there are two inspectable peers whose roots can be compared — which is all this row has ever asked for | [2](Task.2.md) | A sustained live session shows committee validation and certified event sync flowing over the same `PeerTransport`, with roots equal across peers at the end | OPEN |
| L-33 | No asynchronous client chunk pipeline: a region renders only after its whole snapshot arrives. The **edit** half of the guard is done — `WorldMutationApplier` consults the `ChunkEditability` seam in its verify pass, so a delta touching a piece-locked chunk aborts atomically before any write, with halo positions failing closed. Remaining: the **render**-on-arrival half in a GUI environment | [4](Task.4.md) | Pieces render on arrival; an un-arrived section is locked against edit; the manifest hash validates before render; reassembly from seeders each holding < 40% | RETIRING |
| L-76 | Nothing measured NoderaMC in the wild. **The emitter now exists** — `dev.nodera.telemetry` (consent gate, bucketing, bounded spool, sender), proven by `TelemetryEmitterTest` (21), the cross-language `TelemetryRegistryMirrorTest`, and `scripts/e2e-telemetry.sh` end to end against the real binaries. What is still missing is the thing the row is actually about: **evidence from the wild**, which needs a deployment with a population that has opted in | [12](Task.12.md) | The first dashboard answering a `plans/Plan.6.md` §1.1 question from real reports, cited in `PROGRESS.md` | RETIRING |

---

## Reading guide for the implementing model

- The trust model is the constraint that shapes most decisions here: services are **hints**, peers
  verify everything. Any proposal that makes a service load-bearing for correctness is a design bug,
  not a limitation.
- Bounding is a security property in this category. Any new cache keyed by remote input must be
  bounded in the commit that introduces it.
- Frozen wire discipline: an appended tag needs a Java record, a golden fixture, and the Rust mirror
  in the same commit. A tag appended on one side alone fails CI, by design.
- When a design choice trades against a §B entry, prefer the choice that keeps the exit test
  achievable.
