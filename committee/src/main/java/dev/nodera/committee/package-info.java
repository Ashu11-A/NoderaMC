/**
 * Phase 3 committee validation (Task 7 — the MVP gate), Minecraft-free.
 *
 * <p>The novel Nodera layer on top of the MultiPaper-style single-owner model: instead of trusting
 * one executor, a committee of {@code 1 primary + 2 validators} <b>re-executes</b> the same batch,
 * each casts a signed ACCEPT vote on its own {@link dev.nodera.core.state.StateRoot}, and a
 * {@code 2-of-3} quorum on one root ({@link dev.nodera.consensus.MajorityQuorumPolicy#mvp()})
 * commits the corresponding delta through the coordinator's
 * {@link dev.nodera.coordinator.WorldMutationApplier}. A lying validator cannot form a quorum alone;
 * a lying primary is outvoted by the honest validators and penalised. Equivocation
 * ({@link dev.nodera.consensus.EquivocationDetector}) slashes reliability; the server audits a
 * deterministic sample of committed batches ({@link dev.nodera.consensus.SpotCheckPolicy}) as the
 * safety net against a fully-colluding committee. On primary loss a validator is promoted under a
 * bumped epoch.
 *
 * <p>Everything runs headlessly over the {@link dev.nodera.simulation.engine.FlatWorldRegionEngine}
 * and the {@link dev.nodera.coordinator.MutableWorldView} seam — the executable stand-in for the
 * Task 7 MVP-gate scenario.
 *
 * @Thread-context the session is single-thread-confined (server main thread); see each type.
 */
package dev.nodera.committee;
