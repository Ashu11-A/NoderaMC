/**
 * Committee validation primitives (Task 7), Minecraft-free.
 *
 * <p>The novel Nodera layer on top of the MultiPaper-style single-owner model: instead of trusting
 * one executor, a committee of {@code 1 primary + 2 validators} <b>re-executes</b> the same batch,
 * each casts a signed ACCEPT vote on its own {@link dev.nodera.core.state.StateRoot}, and a
 * {@code 2-of-3} quorum on one root ({@link dev.nodera.consensus.MajorityQuorumPolicy#mvp()})
 * commits the corresponding delta through the coordinator's
 * {@link dev.nodera.coordinator.WorldMutationApplier}. A lying validator cannot form a quorum
 * alone; a lying primary is outvoted by the honest validators and penalised. On primary loss a
 * validator is promoted under a bumped epoch
 * ({@link dev.nodera.committee.CommitteeFailover}).
 *
 * <p>What lives here is the primitives — {@link dev.nodera.committee.CommitteeMember} signs,
 * {@link dev.nodera.committee.MemberBallot} carries a vote, {@link dev.nodera.committee.VotePersistence}
 * is the durability seam that makes a member prepare before it signs. <b>The session that drove
 * them is gone</b>: {@code CommitteeSession} plus the server-side audit lane
 * ({@code SpotCheckAuditor}, {@code SpotCheckPolicy}, {@code EquivocationDetector}) were deleted on
 * 2026-08-06 (Plan 11 round 2, issue #210) after the structural report showed no production entry
 * point could reach any of them. The batch loop that ships is
 * {@code dev.nodera.peer.validation.WorkerValidationService}, which drives these same primitives
 * over the real transport; there was never a second one running.
 *
 * @Thread-context single-thread-confined; see each type.
 */
package dev.nodera.committee;
