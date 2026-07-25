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
| L-30 | The P2P lane carries membership and certified state, and committee validation runs over it out of game — but the **live** mesh has still not been observed carrying validated state. `e2e-mesh-soak.sh` now drives the attempt (32 rounds of edits, mobs and movement over 180 s with two real clients) and reports what is actually there: the players' **client lanes** re-execute and vote (`active on 11 region(s)`), while all three headless workers report `votes_cast=0, votes_received=0, committee_commits=0`. The seats live on the clients, whose roots the control socket cannot see, so there are no two inspectable peers to compare — the same node question as **L-60**. The mechanism stays proven headlessly (`WorkerQuorumValidationIT`, `EventSyncOverTransportIT`); what is missing is a live topology where a worker holds a seat | [2](Task.2.md) | A sustained live session shows committee validation and certified event sync flowing over the same `PeerTransport`, with roots equal across peers at the end | OPEN |
| L-33 | No asynchronous client chunk pipeline: a region renders only after its whole snapshot arrives. The **edit** half of the guard is done — `WorldMutationApplier` consults the `ChunkEditability` seam in its verify pass, so a delta touching a piece-locked chunk aborts atomically before any write, with halo positions failing closed. Remaining: the **render**-on-arrival half in a GUI environment | [4](Task.4.md) | Pieces render on arrival; an un-arrived section is locked against edit; the manifest hash validates before render; reassembly from seeders each holding < 40% | RETIRING |

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
| L-62 | The **production** content store has no byte budget. L-37 retired on `BoundedClientWorldStore` (quota + oldest-cold-first eviction + pinned-never-evicted + repair signalling), but nothing constructs it: the worker stores blobs in `FsContentStore` and the in-memory `InMemoryContentStore` backs `EventSourcedWorldStore`, neither of which is bounded. What holds the disk down today is narrower — a per-world archive retention window (**L-61**) and the replication lane's own `NODERA_REPLICATION_BUDGET` for worlds this node does not host — so nothing bounds the total. The policy is written and tested; the wiring is missing | [7](Task.7.md) | `FsContentStore` enforces a byte budget with the L-37 policy (pinned assigned-region state never evicted, eviction signalled to repair), proven against a real directory, and the budget is an operator setting | OPEN |
