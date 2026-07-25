# Engine — Category Charter

<!-- AI-AGENT-INSTRUCTION: This is the CHARTER for the `engine` documentation category. It is not an
     implementation task. It defines the component, its architecture, its dependency edges, and the
     index of its 12 tasks. Read it before touching any engine task. When a task file contradicts
     this charter, fix the task file. Update the task index below whenever a task is added, split,
     or completed, and keep it in agreement with ../ROADMAP.md §2. -->

**Category:** `engine` · **Status:** 🚧 IN PROGRESS (7 of 12 tasks completed) ·
**Last audit:** 2026-07-25

---

## 1. What this category is

The **Minecraft-free Java validation stack**: the project's central bet and everything built
directly on it. A pure-Java, **bit-for-bit deterministic** simulator for one 8×8-chunk region —

```
(RegionSnapshot, ActionBatch, RegionExecutionContext) → (RegionDelta, StateRoot)
```

— identical on every JVM, every OS, every run, wrapped by the full validation stack: shadow
validation (which exists to falsify determinism early), the coordinator (leases, epochs,
compare-and-set world application), committee quorum validation with Byzantine handling and
failover (the MVP gate), the server-fallback/cross-region lane, and the interference guard that
makes delegated regions safe on real worlds. The parity program then brings entities, redstone, the
environment, mobs, and the player lane into the validated lane until no capability is excluded.

**Nothing in this category may import a Minecraft or NeoForge type.** The live wiring is
[`minecraft/Task.2.md`](../minecraft/Task.2.md) and consumes seams defined here.

## 2. Why determinism is the bet

If two honest peers cannot reproduce the same `StateRoot` from the same inputs, committee validation
is meaningless — every disagreement becomes indistinguishable from an attack. So determinism is not
a quality goal here, it is a **correctness precondition**, and it is enforced mechanically:

- One engine. The Java `dev.nodera.simulation` package is the **only** code permitted to re-execute
  a region — no Rust port, no second implementation, in any language, ever.
- No wall clocks, no ambient entropy, no unordered iteration, no IO, no static mutable state, and
  **no floats or doubles in hashed state** (Q32.32 fixed point via `FixedVec3`).
- Enforced by ArchUnit (`simulation/ForbiddenApiTest`) and property tests
  (`simulation/DeterminismPropertyTest`), not by review.
- Every divergence ever found becomes a committed replay fixture so it can never return silently.

## 3. Architecture

```
                    ┌──────────────────────────────────────────────┐
   actions ────────►│  RegionEngine (dev.nodera.simulation)        │────► RegionDelta + StateRoot
                    │  rules · DeterministicRandom · halo guards   │
                    └──────────────────────────────────────────────┘
                                        ▲
      ┌───────────────┬─────────────────┼─────────────────┬──────────────────┐
      │               │                 │                 │                  │
 shadow (T3)     coordinator (T4)  committee (T5)   fallback (T6)   interference (T7)
 re-execute      leases, epochs,   propose, vote,   route to the    one write choke
 and compare     CAS apply         quorum, failover server lane     point, convert
                                                                    foreign writes
```

- **Shadow validation** runs the engine beside a live vanilla server with zero gameplay risk and
  reports divergence. It is the falsification experiment for the whole project.
- **The coordinator** owns assignment: which peer is primary for a region, under which epoch, with
  what lease — and applies a committed delta to the world through a two-pass compare-and-set.
- **The committee** replaces "the server re-executes everything" with "every member re-executes and
  votes"; a 2-of-3 quorum on the resulting root commits, a lying member is out-voted and penalised,
  and equivocation slashes.
- **The fallback lane** classifies actions the committee cannot own (unassigned region, cross-region,
  disputed, collapsed committee) onto a server lane, and measures the committee-commit ratio.
- **The interference guard** makes delegated regions safe on real worlds: every foreign write —
  random ticks, fluids, gravity, fire, mobs, fake players, other mods — is either suppressed or
  converted into a certified `ExternalDelta` at one choke point.

## 4. Dependencies

**This category depends on:** nothing inside the project for tasks 1–2. Task 3 onward consumes
[`network/Task.1.md`](../network/Task.1.md) for the `PeerTransport` seam and the wire messages that
carry proposals and votes; task 8 consumes [`network/Task.3.md`](../network/Task.3.md) for durable
storage.

**This category is consumed by:** [`network/`](../network/Task.0.md) (types and certificates),
[`minecraft/`](../minecraft/Task.0.md) (which delivers every live half),
[`worker/`](../worker/Task.0.md) (which runs the committee out of game).

## 5. Task index

| Task | Title | Status |
|---|---|---|
| [1](Task.1.md) | Domain types, crypto, canonical encoding | ✅ COMPLETED |
| [2](Task.2.md) | Deterministic region engine | ✅ COMPLETED |
| [3](Task.3.md) | Shadow validation | ✅ COMPLETED (headless) |
| [4](Task.4.md) | Coordinator: leases, epochs, propose → verify → commit | ✅ COMPLETED (headless) |
| [5](Task.5.md) | Committee validation — the MVP gate | ✅ COMPLETED (headless) |
| [6](Task.6.md) | Server-fallback lane + cross-region router | ✅ COMPLETED (headless) |
| [7](Task.7.md) | Interference guard, chunk lifecycle, delegability, mod compatibility | ✅ COMPLETED (headless) |
| [8](Task.8.md) | Entity & mob lane | 🚧 IN PROGRESS |
| [9](Task.9.md) | Validated redstone + contraption migration | 🚧 IN PROGRESS |
| [10](Task.10.md) | Environment lane | 🚧 IN PROGRESS |
| [11](Task.11.md) | Deterministic entity simulation | 🚧 IN PROGRESS |
| [12](Task.12.md) | Player lane & trustless closure | 🚧 IN PROGRESS |

Status ledger: [`PROGRESS.md`](PROGRESS.md) · tests: [`TESTING.md`](TESTING.md) · open gaps:
[`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps: [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) ·
third-party rule packs: [`SDK.md`](SDK.md).

## 6. Files

| Path | Contents |
|---|---|
| `java/core/` | Identities, regions, actions, state, events, certificates, JDK-only crypto, canonical encoding |
| `java/engine/` | `dev.nodera.simulation` (the engine), `consensus`, `shadow`, `coordinator`, `committee`, `fallback` |
| `java/testing/` | `LoopbackTransport`, `FakeRegion`, fixture IO — the shared test library |
| `COMPATIBILITY.md` (repo root) | The normative mod-compatibility contract, written by task 7 |

Package architecture: [`java/core/README.md`](../../java/core/README.md),
[`java/engine/README.md`](../../java/engine/README.md),
[`java/testing/README.md`](../../java/testing/README.md).

## 7. Conventions specific to this category

- **Headless-first is absolute.** A phase without a Minecraft-free proof does not merge. Live
  wiring goes to [`minecraft/Task.2.md`](../minecraft/Task.2.md) and must be *adapters over existing
  seams* (`MutableWorldView`, `CommitListener`, capture sinks), never a rewrite. If a seam is
  missing, add it here, headless-tested, then consume it there.
- **Frozen contracts.** The canonical encoding (task 1) never changes without a version bump; wire
  tags are append-only on both language sides in the same commit.
- **Do not implement a limitation's owner scope early**, but never build anything that structurally
  blocks it. When a design choice trades against a `LIMITATIONS.md` row, prefer the choice that
  keeps the exit test achievable.
- **`@Invariant(n)` tags** map tests to the plan's numbered invariants ([`../plans/Plan.0.md`](../plans/Plan.0.md) §8).
  A parity task is not done until its invariants have green tests.
