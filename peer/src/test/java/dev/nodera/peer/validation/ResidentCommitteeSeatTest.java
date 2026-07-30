package dev.nodera.peer.validation;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.region.RegionReplicaRole;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.assignment.RegionAssigned;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.event.InMemoryCertificateStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headless half of "a world is validated by the peers on the network, not by whoever is logged
 * in": an always-on worker takes the committee seat a hosting world hands it over the P2P
 * transport, and starts re-executing that region out of game.
 *
 * <p>The seat arrives as a {@link RegionAssigned} because a headless peer has no Minecraft
 * connection to receive the client lane-plan payload over. Everything else is reconstructed
 * locally: the lease from the message, and the base snapshot from
 * {@link EntityLaneBootstrap#initialSnapshot} — byte-identical to the primary's, which is what
 * lets the first proposal's {@code prevRoot} line up with no state transfer.
 */
final class ResidentCommitteeSeatTest {

    private final HashService hashes = new HashService();
    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);
    private final NodeIdentity self = NodeIdentity.generate();
    private final NodeId primary = NodeIdentity.generate().nodeId();

    private WorkerValidationService service() {
        LoopbackTransport tx = LoopbackTransport.LoopbackNetwork.newNetwork().register(self.nodeId());
        return new WorkerValidationService(self, tx,
                new FlatWorldRegionEngine(FlatWorldRules.RULES_VERSION,
                        FlatWorldRules.registryFingerprint(), hashes),
                hashes, new InMemoryCertificateStore(hashes), 1L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), 100L);
    }

    private RegionAssigned seat(RegionReplicaRole role, List<NodeId> committee, long epoch) {
        return new RegionAssigned(region, new RegionEpoch(epoch), role, SnapshotVersion.INITIAL,
                1_000L, committee);
    }

    private static PeerAddress from(NodeId who) {
        return PeerAddress.of(who, "loopback");
    }

    @Test
    void aValidatorSeatActivatesTheRegionOutOfGame() {
        WorkerValidationService service = service();
        assertThat(service.lease(region)).isEmpty();

        service.onMessage(from(primary),
                seat(RegionReplicaRole.VALIDATOR, List.of(primary, self.nodeId()), 1));

        assertThat(service.lease(region)).isPresent();
        RegionLease lease = service.lease(region).orElseThrow();
        assertThat(lease.primary()).isEqualTo(primary);
        assertThat(lease.validators()).contains(self.nodeId());
        assertThat(lease.expiresAtTick()).isEqualTo(1_000L);
        // The base snapshot must be the deterministic one the primary also starts from.
        RegionSnapshot expected = EntityLaneBootstrap.initialSnapshot(region);
        assertThat(service.currentSnapshot(region).orElseThrow().version())
                .isEqualTo(expected.version());
        assertThat(service.activeRegionIds()).containsExactly(region);
    }

    @Test
    void theLeaseWindowIsReconstructedFromTheAssignedExpiry() {
        WorkerValidationService service = service();
        service.onMessage(from(primary),
                seat(RegionReplicaRole.VALIDATOR, List.of(primary, self.nodeId()), 1));

        RegionLease lease = service.lease(region).orElseThrow();
        assertThat(lease.expiresAtTick() - NoderaConstants.LEASE_LENGTH_TICKS)
                .isEqualTo(lease.validFromTick());
    }

    @Test
    void aResidentIsNeverHandedPrimacy() {
        WorkerValidationService service = service();

        // Primacy is geometric — a peer with no player view is nowhere and must refuse it, even if
        // a malformed or hostile assignment names it first.
        service.onMessage(from(primary),
                seat(RegionReplicaRole.PRIMARY, List.of(self.nodeId(), primary), 1));
        assertThat(service.lease(region)).isEmpty();

        service.onMessage(from(primary),
                seat(RegionReplicaRole.VALIDATOR, List.of(self.nodeId(), primary), 1));
        assertThat(service.lease(region)).isEmpty();
    }

    @Test
    void anAssignmentThisNodeIsNotOnIsIgnored() {
        WorkerValidationService service = service();
        NodeId someoneElse = NodeIdentity.generate().nodeId();

        service.onMessage(from(primary),
                seat(RegionReplicaRole.VALIDATOR, List.of(primary, someoneElse), 1));

        assertThat(service.lease(region)).isEmpty();
    }

    @Test
    void reAssignmentAtTheSameEpochDoesNotRewindALiveReplica() {
        WorkerValidationService service = service();
        service.onMessage(from(primary),
                seat(RegionReplicaRole.VALIDATOR, List.of(primary, self.nodeId()), 1));

        // The hosting world re-plans on every membership or movement change and re-sends seats.
        // Replacing a live replica would reset its head root mid-round and make every subsequent
        // proposal fail its prevRoot check — so a same-epoch repeat must be a no-op.
        RegionSnapshot before = service.currentSnapshot(region).orElseThrow();
        service.onMessage(from(primary),
                seat(RegionReplicaRole.VALIDATOR, List.of(primary, self.nodeId()), 1));

        assertThat(service.currentSnapshot(region).orElseThrow()).isSameAs(before);
        assertThat(service.lease(region).orElseThrow().epoch()).isEqualTo(new RegionEpoch(1));
    }

    @Test
    void aNewerEpochReseatsTheRegion() {
        WorkerValidationService service = service();
        service.onMessage(from(primary),
                seat(RegionReplicaRole.VALIDATOR, List.of(primary, self.nodeId()), 1));
        NodeId newPrimary = NodeIdentity.generate().nodeId();

        service.onMessage(from(newPrimary),
                seat(RegionReplicaRole.VALIDATOR, List.of(newPrimary, self.nodeId()), 2));

        assertThat(service.lease(region).orElseThrow().epoch()).isEqualTo(new RegionEpoch(2));
        assertThat(service.lease(region).orElseThrow().primary()).isEqualTo(newPrimary);
    }

    @Test
    void bindingTheWorldSeedIsWhatMakesAWorkersReExecutionMatchThePrimarys() {
        WorkerValidationService service = service();
        long worldSeed = 8_675_309L;

        // A worker boots with a placeholder seed — it has no world yet. Binding is the handoff
        // that makes its DeterministicRandom stream agree with the region primaries it votes with.
        assertThat(service.bindWorld(worldSeed)).isTrue();
        assertThat(service.worldSeed()).isEqualTo(worldSeed);
    }

    @Test
    void theSeedCannotBeChangedUnderALiveCommittee() {
        WorkerValidationService service = service();
        service.bindWorld(42L);
        service.onMessage(from(primary),
                seat(RegionReplicaRole.VALIDATOR, List.of(primary, self.nodeId()), 1));

        // Re-binding mid-round would fork this node's execution from the rest of the committee.
        assertThat(service.bindWorld(99L)).isFalse();
        assertThat(service.worldSeed()).isEqualTo(42L);

        // Re-binding to the SAME seed is a harmless no-op (the mod re-meshes on every re-plan).
        assertThat(service.bindWorld(42L)).isTrue();
    }
}
