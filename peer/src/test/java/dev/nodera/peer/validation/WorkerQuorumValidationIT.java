package dev.nodera.peer.validation;

import dev.nodera.coordinator.LeaseManager;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.GameAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.fallback.CrossRegionRouter;
import dev.nodera.fallback.RoutingDecision;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.entity.ItemEntityRules;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.ContentId;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.RegionFixtures;
import dev.nodera.testkit.peer.ValidationNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The L-48 / L-30 exit scenario: three <b>companion-only worker nodes</b> (no Minecraft process
 * anywhere) form a committee over the {@code PeerTransport} and validate region batches
 * out-of-game — the primary proposes, the validators re-execute with THE engine and vote over the
 * wire, quorum commits, and every worker ends at the byte-identical root with the co-signed
 * certificate persisted in its own store. Then the primary is lost, a validator is promoted under
 * a bumped epoch, and the surviving 2-member committee keeps committing. Finally the fallback lane
 * routes an unassigned-region action through the server lane and the Phase-4 soak ratio holds.
 */
final class WorkerQuorumValidationIT {

    private final PeerTestHarness harness = PeerTestHarness.create();
    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);
    private NodeIdentity actor;

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void companionWorkersValidateInQuorumOverTheTransport() {
        actor = NodeIdentity.generate();
        ValidationNode a = harness.validationNode().build();
        ValidationNode b = harness.validationNode().build();
        ValidationNode c = harness.validationNode().build();
        List<ValidationNode> all = List.of(a, b, c);
        ValidationNode.mesh(all, actor);

        // --- committee: A primary, B/C validators, all replicas at the same base snapshot ---
        RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
        LeaseManager leases = new LeaseManager(200);
        LeaseManager survivorLeases = new LeaseManager(200);
        RegionLease lease = leases.issue(region, a.nodeId(),
                List.of(b.nodeId(), c.nodeId()), 0);
        survivorLeases.issue(region, a.nodeId(), List.of(b.nodeId(), c.nodeId()), 0);
        for (ValidationNode w : all) {
            w.service().activateRegion(base, lease);
        }

        ActionEnvelope signed = place(1, 0, 5, 70, 5, 1);
        ActionEnvelope tampered = new ActionEnvelope(
                signed.actor(), signed.playerSeq(), signed.serverSeq(), signed.targetTick(),
                signed.region(), new PlaceBlockAction(new NBlockPos(5, 70, 5), 4, 1),
                signed.signature());
        assertThatThrownBy(() -> a.service().proposeBatch(region, 0, 1, List.of(tampered)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unauthenticated");

        // --- batch 1: distributed quorum commit ---
        List<ActionEnvelope> batch1 = List.of(
                place(1, 0, 5, 70, 5, 1),
                place(2, 0, 40, 100, 40, 4));
        Optional<StateRoot> committed1 = a.service().proposeBatch(region, 0, 1, batch1);
        assertThat(committed1).isPresent();

        // the reference engine agrees (one engine, one root)
        StateRoot expected1 = referenceRoot(base, lease, SnapshotVersion.INITIAL, 0, 1, batch1);
        assertThat(committed1).contains(expected1);

        // every worker converged on the same head root and persisted the co-signed certificate
        awaitHeads(all, expected1);
        for (ValidationNode w : all) {
            var cert = w.service().latestCertificate(region).orElseThrow();
            assertThat(cert.resultingRoot()).isEqualTo(expected1);
            CanonicalWriter certW = new CanonicalWriter();
            cert.encode(certW);
            assertThat(w.certificates().has(
                    ContentId.of(harness.hashes(), certW.toByteArray()))).isTrue();
        }

        // --- primary loss: promote a validator (epoch + 1) on the survivors ---
        RegionLease promotedB = b.service().failover(region, leases, 100);
        RegionLease promotedC = c.service().failover(region, survivorLeases, 100);
        assertThat(promotedB).isNotNull();
        assertThat(promotedB.epoch().value()).isEqualTo(1);
        assertThat(promotedC.epoch()).isEqualTo(promotedB.epoch());
        assertThat(promotedB.primary()).isNotEqualTo(a.nodeId());
        assertThat(promotedC.primary()).isEqualTo(promotedB.primary());

        // --- batch 2: the surviving 2-member committee keeps play going ---
        ValidationNode newPrimary = promotedB.primary().equals(b.nodeId()) ? b : c;
        ValidationNode survivor = newPrimary == b ? c : b;
        RegionSnapshot afterBatch1 = snapshotOf(newPrimary, expected1);
        List<ActionEnvelope> batch2 = List.of(place(100, 3, 12, 60, 12, 2));
        Optional<StateRoot> committed2 = newPrimary.service().proposeBatch(region, 2, 3, batch2);
        assertThat(committed2).isPresent();
        StateRoot expected2 = referenceRoot(afterBatch1, promotedB,
                SnapshotVersion.INITIAL.next(), 2, 3, batch2);
        assertThat(committed2).contains(expected2);
        awaitHeads(List.of(newPrimary, survivor), expected2);

        // --- fallback lane: an unassigned-region action commits through the server lane ---
        RegionId elsewhere = new RegionId(DimensionKey.overworld(), 7, 7);
        RegionSnapshot elsewhereBase = RegionFixtures.fullUniformSnapshot(elsewhere, 0);
        NBlockPos inElsewhere = new NBlockPos(
                elsewhere.originChunkX() * 16 + 5, 70, elsewhere.originChunkZ() * 16 + 5);
        ActionEnvelope stray = RegionFixtures.signed(actor, elsewhere, 999, 5,
                new PlaceBlockAction(inElsewhere, 3, 1));
        RoutingDecision decision = newPrimary.service().routeAndMaybeFallback(
                stray, CrossRegionRouter.RegionStatus.UNASSIGNED, elsewhereBase);
        assertThat(decision.isFallback()).isTrue();
        assertThat(newPrimary.service().snapshot().fallbackCommits()).isEqualTo(1);

        // the committee lane dominates: the Phase-4 soak ratio holds on the router
        for (int i = 0; i < 50; i++) {
            newPrimary.service().routeAndMaybeFallback(place(2000 + i, 10, 6 + (i % 100), 70, 6, 1),
                    CrossRegionRouter.RegionStatus.DELEGATED_HEALTHY, elsewhereBase);
        }
        assertThat(newPrimary.service().soakMetrics().meetsPhase4ExitCriterion()).isTrue();

        // telemetry counters moved — the worker STATE JSON has real validation data to report
        WorkerValidationService.Snapshot telemetry = newPrimary.service().snapshot();
        assertThat(telemetry.committeeCommits()).isEqualTo(2);
        assertThat(telemetry.votesCast()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void borderIntentUsesBothCommitteesAndCommitsSameEntityId() {
        ValidationNode a = harness.validationNode().build();
        ValidationNode b = harness.validationNode().build();
        ValidationNode c = harness.validationNode().build();
        List<ValidationNode> all = List.of(a, b, c);
        ValidationNode.mesh(all);

        RegionId targetRegion = new RegionId(DimensionKey.overworld(), 1, 0);
        PersistedEntityState crossing = new PersistedEntityState(
                new NetworkEntityId(77), EntityKind.ITEM, 42,
                FixedVec3.ofBlock(127, 5, 1), FixedVec3.ofBlock(1, 0, 0),
                10, ItemEntityRules.DESPAWN_AGE_TICKS, ItemEntityRules.payload(42, 3));
        RegionSnapshot source = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0,
                RegionFixtures.fullUniformSnapshot(region, 0).chunks(), List.of(crossing));
        RegionSnapshot target = new RegionSnapshot(targetRegion, SnapshotVersion.INITIAL, 1,
                RegionFixtures.fullUniformSnapshot(targetRegion, 0).chunks(), List.of());
        LeaseManager leases = new LeaseManager(200);
        RegionLease sourceLease = leases.issue(
                region, a.nodeId(), List.of(b.nodeId(), c.nodeId()), 0);
        RegionLease targetLease = leases.issue(
                targetRegion, b.nodeId(), List.of(a.nodeId(), c.nodeId()), 0);
        for (ValidationNode worker : all) {
            worker.service().activateRegion(source, sourceLease);
            worker.service().activateRegion(target, targetLease);
        }

        Optional<StateRoot> committed = all.getFirst().service().proposeBatch(
                region, 1, 1, List.of());
        assertThat(committed).isPresent();
        awaitHeads(all, region, committed.orElseThrow());
        StateRoot targetRoot = all.getFirst().service().headRoot(targetRegion).orElseThrow();
        awaitHeads(all, targetRegion, targetRoot);
        for (ValidationNode worker : all) {
            assertThat(worker.service().currentSnapshot(region).orElseThrow().entities()).isEmpty();
            assertThat(worker.service().currentSnapshot(targetRegion).orElseThrow().entities())
                    .singleElement().satisfies(entity -> {
                        assertThat(entity.id()).isEqualTo(crossing.id());
                        assertThat(entity.pos().blockX()).isEqualTo(128);
                    });
        }
    }

    @Test
    void disjointCommitteesValidateAndApplyOnlyTheirTransferSide() {
        List<ValidationNode> all = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            all.add(harness.validationNode().build());
        }
        ValidationNode.mesh(all);
        ValidationNode sourcePrimary = all.get(0);
        List<ValidationNode> sourceCommittee = all.subList(0, 3);
        List<ValidationNode> targetCommittee = all.subList(3, 6);
        RegionId targetRegion = new RegionId(DimensionKey.overworld(), 1, 0);
        PersistedEntityState crossing = new PersistedEntityState(
                new NetworkEntityId(88), EntityKind.ITEM, 42,
                FixedVec3.ofBlock(127, 5, 1), FixedVec3.ofBlock(1, 0, 0),
                10, ItemEntityRules.DESPAWN_AGE_TICKS, ItemEntityRules.payload(42, 3));
        RegionSnapshot source = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0,
                RegionFixtures.fullUniformSnapshot(region, 0).chunks(), List.of(crossing));
        RegionSnapshot target = new RegionSnapshot(targetRegion, SnapshotVersion.INITIAL, 1,
                RegionFixtures.fullUniformSnapshot(targetRegion, 0).chunks(), List.of());
        LeaseManager leases = new LeaseManager(200);
        RegionLease sourceLease = leases.issue(
                region, sourcePrimary.nodeId(),
                List.of(all.get(1).nodeId(), all.get(2).nodeId()), 0);
        RegionLease targetLease = leases.issue(
                targetRegion, all.get(3).nodeId(),
                List.of(all.get(4).nodeId(), all.get(5).nodeId()), 0);
        for (ValidationNode worker : all) {
            worker.service().registerLease(sourceLease);
            worker.service().registerLease(targetLease);
        }
        for (ValidationNode worker : sourceCommittee) {
            worker.service().activateRegion(source, sourceLease);
        }
        // Source host owns the live target state but has no target-committee vote.
        sourcePrimary.service().activateRegion(target, targetLease);
        for (ValidationNode worker : targetCommittee) {
            worker.service().activateRegion(target, targetLease);
        }

        Optional<StateRoot> committed = sourcePrimary.service().proposeBatch(
                region, 1, 1, List.of());

        assertThat(committed).isPresent();
        awaitHeads(sourceCommittee, region, committed.orElseThrow());
        StateRoot targetRoot = sourcePrimary.service().headRoot(targetRegion).orElseThrow();
        List<ValidationNode> targetReplicas = new ArrayList<>();
        targetReplicas.add(sourcePrimary);
        targetReplicas.addAll(targetCommittee);
        awaitHeads(targetReplicas, targetRegion, targetRoot);
        for (ValidationNode worker : sourceCommittee) {
            assertThat(worker.service().currentSnapshot(region).orElseThrow().entities()).isEmpty();
        }
        for (ValidationNode worker : targetReplicas) {
            assertThat(worker.service().currentSnapshot(targetRegion).orElseThrow().entities())
                    .singleElement().extracting(PersistedEntityState::id).isEqualTo(crossing.id());
        }
        for (ValidationNode worker : all.subList(1, 3)) {
            assertThat(worker.service().currentSnapshot(targetRegion)).isEmpty();
        }
        for (ValidationNode worker : targetCommittee) {
            assertThat(worker.service().currentSnapshot(region)).isEmpty();
        }
    }

    // --- helpers -------------------------------------------------------------------------

    private void awaitHeads(List<ValidationNode> workers, StateRoot expected) {
        awaitHeads(workers, region, expected);
    }

    private void awaitHeads(List<ValidationNode> workers, RegionId targetRegion,
                            StateRoot expected) {
        Await.quietly(5_000, () -> workers.stream()
                .allMatch(w -> w.headRoot(targetRegion).map(expected::equals).orElse(false)));
        for (ValidationNode w : workers) {
            assertThat(w.headRoot(targetRegion)).contains(expected);
        }
    }

    private StateRoot referenceRoot(RegionSnapshot base, RegionLease lease, SnapshotVersion version,
                                    long tickFrom, long tickTo, List<ActionEnvelope> actions) {
        ActionBatch batch = new ActionBatch(region, lease.epoch(), version,
                tickFrom, tickTo, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(region, lease.epoch(), version,
                tickFrom, tickTo, RegionFixtures.WORLD_SEED, FlatWorldRules.RULES_VERSION,
                FlatWorldRules.registryFingerprint());
        return harness.honestEngine()
                .execute(new RegionExecutionRequest(ctx, base, batch)).resultingRoot();
    }

    private RegionSnapshot snapshotOf(ValidationNode w, StateRoot expectedHead) {
        assertThat(w.headRoot(region)).contains(expectedHead);
        // The replica advanced; rebuild the same snapshot deterministically from a fresh re-run so
        // the reference-engine comparison uses identical bytes. (The service itself holds the
        // advanced snapshot internally.)
        return w.service().currentSnapshot(region).orElseThrow();
    }

    private ActionEnvelope place(long seq, long tick, int x, int y, int z, int stateId) {
        GameAction action = new PlaceBlockAction(new NBlockPos(x, y, z), stateId, 1);
        return RegionFixtures.signed(actor, region, seq, tick, action);
    }
}
