/**
 * Replica delta application and interference probing, Minecraft-free.
 *
 * <p>What remains of Task 5's shadow lane after Task 30 retired the dedicated server. The
 * server-authoritative half — a coordinator streaming captured actions to worker runtimes, a
 * server-side recompute producing the reference root, and a divergence tracker comparing the two —
 * was deleted on 2026-08-06 (Plan 11 round 2, issue #210) because no production entry point could
 * reach any of it: in the decentralized model every node re-executes for itself and there is no
 * server to be the reference.
 *
 * <p>{@link dev.nodera.shadow.SnapshotDeltaApplier} is the piece that outlived the design and is
 * reached from production ({@code WorkerValidationService}, {@code LocalReplicaView},
 * {@code EntityTransferCoordinator}): it applies a committed delta to a local replica under a
 * compare-and-set guard, throwing {@link dev.nodera.shadow.ReplicaDriftException} when the replica
 * has drifted so the caller re-snapshots instead of committing a divergence. Timing is measured
 * OUTSIDE the hashed path (nanoTime around the engine, never inside it).
 * {@link dev.nodera.shadow.InterferenceProbe} backs the mod's {@code /nodera} diagnostic that
 * compares a re-extracted snapshot against the expected one.
 *
 * <p>Pure Java over {@code core} + {@code simulation}: no clocks in the hashed path, no NeoForge,
 * no IO.
 *
 * @Thread-context see each type; the delta applier is confined to whichever thread owns the replica.
 */
package dev.nodera.shadow;
