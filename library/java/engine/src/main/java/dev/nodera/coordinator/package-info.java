/**
 * The world-mutation choke point and the region pipeline (Task 6), Minecraft-free.
 *
 * <p>All world mutation is behind the {@link dev.nodera.coordinator.MutableWorldView} seam, so the
 * real {@code ServerLevel} is the only thing the NeoForge mod supplies, and every write goes
 * through the single writer {@link dev.nodera.coordinator.WorldMutationApplier} (two-pass
 * compare-and-set, all-or-nothing). Around it: {@link dev.nodera.coordinator.LeaseManager}
 * reassigns under a bumped epoch so a stale-epoch proposal is dropped,
 * {@link dev.nodera.coordinator.ReliabilityLedger} is the frozen correctness source for a peer's
 * standing, {@link dev.nodera.coordinator.LagHandoffPolicy} decides when a lagging primary hands
 * off, and {@code interference} is the single mutation-guard choke point.
 *
 * <p><b>The server-authoritative half of this package no longer exists.</b> Task 30 retired the
 * dedicated server, and ownership is decided by
 * {@link dev.nodera.core.region.ViewOwnershipPlanner} from each player's render-distance disc. The
 * classes that assumed a central coordinator — a region allocator, a proposal manager, a
 * server-side verifier, a heartbeat monitor over a registry of nodes, and the multi-factor
 * reliability scorer layered on the ledger — were deleted on 2026-08-06 (Plan 11 round 2, issue
 * #210): the structural report showed nothing that ships could reach them, and a whole
 * server-authoritative layer was still being compiled into every jar.
 *
 * @Thread-context single-thread-confined (the server main thread); see each type.
 */
package dev.nodera.coordinator;
