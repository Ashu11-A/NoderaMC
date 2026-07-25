# Network — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the network category. "Permanent" is a banned classification.
     Every §B row has an owning task and an EXIT TEST and retires only when that exit test is green —
     verify against the exit clause, never the prose. A newly discovered limitation enters §B as OPEN
     with a path, an owner, and an exit test BEFORE the discovering PR merges. Never delete a row:
     move it to LIMITATIONS.fixed.md with its evidence. Update in the SAME commit that stages or
     retires a row. -->

**Category:** network · **Last audit:** 2026-07-25 · Open or retiring rows: **2**

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
| L-30 | The P2P lane carries membership and certified state, and committee validation runs over it out of game — but the **live** mesh has not yet been observed carrying validated state under real client churn for a sustained period. The mechanism is proven (`WorkerQuorumValidationIT` for committee-over-transport, `EventSyncOverTransportIT` for certified forward sync with an uncertified tail refused); what is missing is the live-mesh soak | [2](Task.2.md) | A sustained live session shows committee validation and certified event sync flowing over the same `PeerTransport`, with roots equal across peers at the end | OPEN |
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
