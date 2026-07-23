package dev.nodera.peer.validation;

import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.coordinator.PipelineState;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.event.InMemoryCertificateStore;
import dev.nodera.testkit.LoopbackTransport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guard-rail unit tests for the worker validation lane. */
class WorkerValidationServiceTest {

    private final HashService hashes = new HashService();
    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);

    private WorkerValidationService service(NodeIdentity id) {
        LoopbackTransport tx = LoopbackTransport.LoopbackNetwork.newNetwork().register(id.nodeId());
        return new WorkerValidationService(id, tx,
                new FlatWorldRegionEngine(FlatWorldRules.RULES_VERSION,
                        FlatWorldRules.registryFingerprint(), hashes),
                hashes, new InMemoryCertificateStore(hashes), 1L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), 100L);
    }

    @Test
    void proposingOnAnUnactivatedRegionThrows() {
        WorkerValidationService s = service(NodeIdentity.generate());
        assertThatThrownBy(() -> s.proposeBatch(region, 0, 1, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not activated");
    }

    @Test
    void unactivatedRegionExposesNoState() {
        WorkerValidationService s = service(NodeIdentity.generate());
        assertThat(s.headRoot(region)).isEmpty();
        assertThat(s.lease(region)).isEmpty();
        assertThat(s.currentSnapshot(region)).isEmpty();
        assertThat(s.latestCertificate(region)).isEmpty();
        assertThat(s.snapshot().activeRegions()).isZero();
    }

    @Test
    void nullConstructorArgumentsAreRejected() {
        assertThatThrownBy(() -> new WorkerValidationService(null, null, null, null, null,
                0L, 0, 0L, 0L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void soloCommitteeCommitsWithoutRemoteValidators() {
        // A decentralized FOV plan yields a one-member committee for a solo host: quorum must be
        // a majority of the LEASE's committee (1-of-1), not the fixed 2-of-3 profile — which made
        // every solo batch time out and revoke.
        NodeIdentity primary = NodeIdentity.generate();
        WorkerValidationService service = service(primary);
        RegionLease lease = new RegionLease(
                region, RegionEpoch.INITIAL, primary.nodeId(), List.of(), 0, 200);
        service.activateRegion(new RegionSnapshot(
                region, SnapshotVersion.INITIAL, 0, List.of()), lease);

        assertThat(service.proposeBatch(region, 1, 1, List.of())).isPresent();
        assertThat(service.pipelineState(region)).isNotEqualTo(PipelineState.REVOKED);
        assertThat(service.latestCertificate(region)).isPresent();
    }

    @Test
    void actorAdmitsUnderAnyRegisteredKeyAndRejectsUnregisteredSigners() {
        // L-50 per-joiner identities: an actor may be admissible under several signer keys —
        // its own member node's key plus the interim session signer. Either signature admits;
        // an unregistered signer never does.
        NodeIdentity primary = NodeIdentity.generate();
        NodeIdentity memberKey = NodeIdentity.generate();   // the actor's own node
        NodeIdentity interimKey = NodeIdentity.generate();  // the session's capture-point signer
        NodeIdentity strangerKey = NodeIdentity.generate();
        dev.nodera.core.identity.NodeId actor =
                new dev.nodera.core.identity.NodeId(new java.util.UUID(7, 7));
        WorkerValidationService service = service(primary);
        RegionLease lease = new RegionLease(
                region, RegionEpoch.INITIAL, primary.nodeId(), List.of(), 0, 200);
        java.util.List<dev.nodera.core.state.ChunkColumnState> columns = new java.util.ArrayList<>();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                columns.add(new dev.nodera.core.state.ChunkColumnState(
                        dx, dz, new int[24], -64, 24));
            }
        }
        service.activateRegion(new RegionSnapshot(
                region, SnapshotVersion.INITIAL, 0, columns), lease);
        service.registerActor(actor, memberKey.publicKeyBytes());
        service.registerActor(actor, interimKey.publicKeyBytes());

        java.util.function.BiFunction<NodeIdentity, Long, dev.nodera.core.action.ActionEnvelope>
                place = (signer, seq) -> {
                    var action = new dev.nodera.core.action.PlaceBlockAction(
                            new dev.nodera.core.state.NBlockPos(5, 70, 5), 1, 1);
                    var unsigned = new dev.nodera.core.action.ActionEnvelope(
                            actor, seq, seq, seq, region, action, dev.nodera.core.Bytes.empty());
                    return new dev.nodera.core.action.ActionEnvelope(
                            actor, seq, seq, seq, region, action,
                            signer.sign(unsigned.signedPortion()));
                };

        assertThat(service.proposeBatch(region, 1, 1, List.of(place.apply(memberKey, 1L))))
                .as("the actor's own member-node key admits").isPresent();
        assertThat(service.proposeBatch(region, 2, 2, List.of(place.apply(interimKey, 2L))))
                .as("the interim session signer still admits").isPresent();
        assertThatThrownBy(() ->
                service.proposeBatch(region, 3, 3, List.of(place.apply(strangerKey, 3L))))
                .as("an unregistered signer is refused")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unauthenticated");
    }

    @Test
    void failedQuorumRevokesRegionInsteadOfLeavingSignedBatchStuck() {
        NodeIdentity primary = NodeIdentity.generate();
        WorkerValidationService service = service(primary);
        RegionLease lease = new RegionLease(
                region, RegionEpoch.INITIAL, primary.nodeId(),
                List.of(NodeIdentity.generate().nodeId(), NodeIdentity.generate().nodeId()),
                0, 200);
        service.activateRegion(new RegionSnapshot(
                region, SnapshotVersion.INITIAL, 0, List.of()), lease);

        assertThat(service.proposeBatch(region, 1, 1, List.of())).isEmpty();
        assertThat(service.pipelineState(region)).isEqualTo(PipelineState.REVOKED);
    }
}
