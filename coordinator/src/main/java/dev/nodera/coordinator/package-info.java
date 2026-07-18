/**
 * Phase 2 coordinator (Task 6), Minecraft-free.
 *
 * <p>Turns shadow lanes (Task 5) into the real delegated pipeline: the assigned primary client
 * executes first and proposes a delta+root; the server {@link dev.nodera.coordinator.ServerVerifier}
 * re-executes, compares roots, and — on {@link dev.nodera.consensus.VerificationOutcome#MATCH} —
 * commits the client's delta to the world through the single writer,
 * {@link dev.nodera.coordinator.WorldMutationApplier} (two-pass compare-and-set, all-or-nothing).
 * Failures penalise the proposer ({@link dev.nodera.coordinator.ReliabilityLedger}) and resync; a
 * missed heartbeat ({@link dev.nodera.coordinator.HeartbeatMonitor}) revokes the lease and the
 * {@link dev.nodera.coordinator.LeaseManager} reassigns under a bumped epoch, so a stale-epoch
 * proposal is dropped.
 *
 * <p>All world mutation is behind the {@link dev.nodera.coordinator.MutableWorldView} seam, so the
 * real {@code ServerLevel} is the only thing the NeoForge mod supplies; the coordinator logic itself
 * runs entirely headlessly under JUnit (see {@code CoordinatorIT}).
 *
 * @Thread-context the coordinator is single-thread-confined (server main thread); see each type.
 */
package dev.nodera.coordinator;
