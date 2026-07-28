# Engine Task 3 — Shadow Validation

<!-- AI-AGENT-INSTRUCTION: This task exists to FALSIFY the determinism bet, not to confirm it. When
     working here, treat every divergence as a finding to be reproduced and committed as a replay
     fixture — never as noise to be filtered. Keep this header's status accurate; the live half is
     owned by minecraft/Task.2.md and its evidence gap is tracked there, not here. -->

**Status:** ✅ COMPLETED (headless; live half → [minecraft 2](../minecraft/Task.2.md))
**Category:** engine · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [engine 2](Task.2.md), [network 1](../network/Task.1.md) (transport seam)
**Consumed by:** [engine 4](Task.4.md), [minecraft 2](../minecraft/Task.2.md)

---

## Goal

Prove — or break — determinism against the real game with **zero gameplay risk**. The server plays
vanilla and stays fully authoritative. Actions are captured and streamed with snapshots to workers;
each worker recomputes with the engine and reports its `StateRoot`; the server compares against its
own recomputation and reports divergence. Nothing the workers say affects the world.

This is the project's early-falsification experiment. If the engine cannot reproduce a real server's
outcomes, that must be discovered here, cheaply, rather than after committee validation is wired.

## Status detail

The Minecraft-free pipeline is complete and green. `ShadowValidationIT` runs 3 workers × 250 random
place/break batches with **zero divergence**, and separately catches a deliberately lying worker and
re-snapshots it.

The live half — NeoForge capture mixins, a multi-client soak on a real server, and the bandwidth and
interference numbers — is delivered by [`minecraft/Task.2.md`](../minecraft/Task.2.md).

## Dependencies

- [engine 2](Task.2.md) — the engine being shadowed.
- [network 1](../network/Task.1.md) — the transport seam and the message family that carries
  snapshots, batches, and reports.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `WorkerRuntime` — virtual-thread worker that recomputes batches | ✅ |
| 2 | `ReplicaStore` — per-region replica state | ✅ |
| 3 | `SnapshotDeltaApplier` — compare-and-set replica advance | ✅ |
| 4 | `ShadowWorker` / `ShadowCoordinator` — the pairing and the report flow | ✅ |
| 5 | `ServerRecompute` — the intra-JVM self-check | ✅ |
| 6 | `DivergenceTracker` — classification and reporting | ✅ |
| 7 | `InterferenceProbe` — counts foreign mutations that would have broken a delegated region | ✅ |
| 8 | Live capture mixins + real-server soak | → [minecraft 2](../minecraft/Task.2.md) |

## Design

**Shadow mode is the only safe way to test the bet.** Delegating a region to an unproven engine on a
live world risks the world. Shadowing costs bandwidth and CPU and risks nothing, so the experiment
can run for as long as it takes to build confidence.

**CAS replica advance, not blind apply.** `SnapshotDeltaApplier` advances a replica only if its
current version matches the delta's base. A worker that fell behind cannot quietly apply a delta to
the wrong base and then report a root that looks like a divergence — the failure mode is an explicit
stale-base rejection instead of a phantom finding.

**The `InterferenceProbe` predates the guard on purpose.** Before task 7 could suppress or convert
foreign writes, this probe *measured* them, so the guard's revoke-rate thresholds were derived from
observed data rather than guessed.

**A lying worker must be detectable without the server re-executing everything.** The shadow lane
proves the server's own recomputation catches a fabricated root, which is the property committee
validation (task 5) later generalises into a quorum with adaptive spot-checking.

## Files

- `java/engine/src/main/java/dev/nodera/shadow/{WorkerRuntime,ReplicaStore,SnapshotDeltaApplier,ShadowWorker,ShadowCoordinator,ServerRecompute,DivergenceTracker,InterferenceProbe}.java`
- `java/engine/src/test/java/dev/nodera/shadow/ShadowValidationIT.java`

## Testing

- `ShadowValidationIT`: 3 workers × 250 random batches, zero divergence; a lying worker is caught
  and re-snapshotted.
- Stale-base rejection tests on the applier.
- Divergence classification tests (which field differed, at which version).

## Acceptance criteria

1. ✅ A zero-divergence soak over randomised batches with multiple workers.
2. ✅ A fabricated root is detected and the worker is re-snapshotted rather than trusted.
3. ✅ A stale replica cannot advance on a mismatched base.
4. ⏳ Live: the same soak against a real `ServerLevel` with capture mixins, with bandwidth and
   interference numbers recorded — [minecraft 2](../minecraft/Task.2.md).

## Limitations

The live-evidence gap is tracked as **L-45** in
[`minecraft/LIMITATIONS.md`](../minecraft/LIMITATIONS.md) (no automated real-client harness in CI),
not as a row here.
