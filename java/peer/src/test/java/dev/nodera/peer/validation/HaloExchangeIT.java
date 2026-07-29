package dev.nodera.peer.validation;

import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.simulation.rules.FluidRules;
import dev.nodera.storage.event.InMemoryCertificateStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine L-2, end to end over the real wire: a river crosses a region boundary between two
 * separate workers.
 *
 * <p>Everything the engine needed was already there — {@code RegionHalo} could hold neighbour
 * columns and the fluid automaton flowed correctly once it did. What was missing was that nobody
 * ever cut a slice and nobody ever received one: {@code HaloUpdate} (wire tag 56) had zero
 * producers and zero consumers, so every halo on the network was empty and a river stopped dead at
 * the boundary. This test drives the production path only: worker A activates ORIGIN and its
 * commit path emits {@code HaloUpdate}; the frame crosses a real transport; worker B consumes it
 * and its own committee round then floods the border cell.
 */
final class HaloExchangeIT {

    private static final int MIN_Y = -64;
    private static final int SECTIONS = 24;
    private static final int AIR_SECTION = 8;
    private static final int Y = MIN_Y + AIR_SECTION * 16;
    private static final long WORLD_SEED = 12345L;

    private static final RegionId ORIGIN = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final RegionId EAST = new RegionId(DimensionKey.overworld(), 1, 0);
    /** The border cell of EAST: the far bank of the river. */
    private static final NBlockPos FAR_BANK = new NBlockPos(EAST.originChunkX() * 16, Y, 0);

    private final HashService hashes = new HashService();
    private final List<LoopbackTransport> transports = new ArrayList<>();
    private final AtomicInteger haloFrames = new AtomicInteger();

    @AfterEach
    void stopTransports() {
        transports.forEach(LoopbackTransport::stop);
    }

    @Test
    @DisplayName("a neighbour's committed edge crosses the wire and floods the far bank")
    void waterCrossesTheBoundaryBetweenTwoWorkers() throws Exception {
        LoopbackTransport.LoopbackNetwork net = LoopbackTransport.LoopbackNetwork.newNetwork();
        NodeIdentity idA = NodeIdentity.generate();
        NodeIdentity idB = NodeIdentity.generate();
        Worker a = worker(net, idA);
        Worker b = worker(net, idB);
        a.service.registerPeer(idB.nodeId(), PeerAddress.of(idB.nodeId(), "loopback"),
                idB.publicKeyBytes());
        b.service.registerPeer(idA.nodeId(), PeerAddress.of(idA.nodeId(), "loopback"),
                idA.publicKeyBytes());

        RegionLease leaseEast = solo(EAST, idB);
        RegionLease leaseOrigin = solo(ORIGIN, idA);
        b.service.activateRegion(floored(EAST), leaseEast);
        // A learns who owns EAST — the committee its edge columns are addressed to — and B learns
        // who owns ORIGIN, which is whose signatures it will require on a slice. Both directions
        // are the same neighbour-lease knowledge the cross-region entity-transfer lane already
        // depends on; a border is not verifiable without knowing who holds the other side.
        a.service.registerLease(leaseEast);
        b.service.registerLease(leaseOrigin);

        // No halo yet: the far bank is dry, and the receiving region is right to keep it dry.
        assertThat(b.service.halo(EAST).isEmpty()).isTrue();
        assertThat(b.service.proposeBatch(EAST, 0, 40, List.of())).isPresent();
        assertThat(blockAt(b.service.currentSnapshot(EAST).orElseThrow(), FAR_BANK))
                .as("with an empty halo the border cell stays air — the bug, stated as a test")
                .isEqualTo(FlatWorldRules.AIR);

        // A activates the river. The production path cuts its eastern edge and sends it to EAST's
        // committee; nothing in this test touches HaloUpdate by hand.
        a.service.activateRegion(originWithEasternWater(), leaseOrigin);

        awaitHalo(b);
        assertThat(haloFrames.get())
                .as("the slice travelled as a real encoded HaloUpdate frame")
                .isPositive();
        assertThat(b.service.halo(EAST).versionOf(ORIGIN))
                .as("and it names the version it was cut at, so staleness is detectable")
                .isPresent();

        assertThat(b.service.proposeBatch(EAST, 41, 120, List.of())).isPresent();

        int farBank = blockAt(b.service.currentSnapshot(EAST).orElseThrow(), FAR_BANK);
        assertThat(farBank)
                .as("the river reaches the far bank — cross-region fluid spread, end to end")
                .isNotEqualTo(FlatWorldRules.AIR);
        assertThat(FluidRules.isWater(farBank))
                .as("and it is water, decided by the receiving region's own automaton")
                .isTrue();
    }

    @Test
    @DisplayName("a MULTI-MEMBER committee floods water across a region boundary and agrees")
    void multiMemberCommitteesAgreeOnACrossBoundaryFlood() throws Exception {
        LoopbackTransport.LoopbackNetwork net = LoopbackTransport.LoopbackNetwork.newNetwork();
        // ORIGIN is held by a committee of two, and so is EAST. Neither region has a single
        // authority over its own edge, and neither has a single reader of the neighbour's.
        NodeIdentity idA = NodeIdentity.generate();   // ORIGIN primary
        NodeIdentity idD = NodeIdentity.generate();   // ORIGIN validator
        NodeIdentity idB = NodeIdentity.generate();   // EAST primary
        NodeIdentity idC = NodeIdentity.generate();   // EAST validator
        Worker a = worker(net, idA);
        Worker d = worker(net, idD);
        Worker b = worker(net, idB);
        Worker c = worker(net, idC);
        List<Worker> all = List.of(a, d, b, c);
        for (Worker w : all) {
            for (Worker other : all) {
                if (other != w) {
                    w.service.registerPeer(other.id().nodeId(),
                            PeerAddress.of(other.id().nodeId(), "loopback"),
                            other.id().publicKeyBytes());
                }
            }
        }

        RegionLease leaseOrigin = new RegionLease(
                ORIGIN, RegionEpoch.INITIAL, idA.nodeId(), List.of(idD.nodeId()), 0, 10_000);
        RegionLease leaseEast = new RegionLease(
                EAST, RegionEpoch.INITIAL, idB.nodeId(), List.of(idC.nodeId()), 0, 10_000);
        // Every node learns BOTH leases: the receiving side needs the source committee's
        // membership to know whose signatures count, and the sending side needs the neighbour's
        // to know where to address the slice.
        for (Worker w : all) {
            w.service.registerLease(leaseOrigin);
            w.service.registerLease(leaseEast);
        }
        b.service.activateRegion(floored(EAST), leaseEast);
        c.service.activateRegion(floored(EAST), leaseEast);

        // The river activates on BOTH members of ORIGIN's committee. Each signs the slice it cut
        // from its own snapshot; neither one alone is enough to open EAST's border.
        a.service.activateRegion(originWithEasternWater(), leaseOrigin);
        assertThat(b.service.halo(EAST).isEmpty())
                .as("one member's endorsement is not a committee's — no quorum, no halo")
                .isTrue();
        d.service.activateRegion(originWithEasternWater(), leaseOrigin);

        awaitHalo(b);
        awaitHalo(c);
        assertThat(b.service.halo(EAST).versionOf(ORIGIN))
                .isEqualTo(c.service.halo(EAST).versionOf(ORIGIN));

        long before = b.service.currentSnapshot(EAST).orElseThrow().version().value();
        assertThat(b.service.proposeBatch(EAST, 41, 120, List.of()))
                .as("a two-member committee reaches quorum with the halo as a named input")
                .isPresent();
        assertThat(b.service.currentSnapshot(EAST).orElseThrow().version().value())
                .as("the round committed rather than timing out")
                .isGreaterThan(before);
        assertThat(b.service.divergences())
                .as("no member computed a different world: the halo was an agreed input")
                .isZero();
        assertThat(c.service.divergences()).isZero();
        awaitCertificate(c);
        assertThat(c.service.latestCertificate(EAST).orElseThrow().votes())
                .as("the commit is co-signed — this is a committee, not a solo host")
                .hasSize(2);

        int farBank = blockAt(b.service.currentSnapshot(EAST).orElseThrow(), FAR_BANK);
        assertThat(FluidRules.isWater(farBank))
                .as("water crossed the boundary under a real multi-member committee")
                .isTrue();
        assertThat(FluidRules.isWater(
                blockAt(c.service.currentSnapshot(EAST).orElseThrow(), FAR_BANK)))
                .as("and the validator's replica holds the identical world")
                .isTrue();
    }

    @Test
    @DisplayName("two neighbour replicas on one worker exchange edges without any wire at all")
    void colocatedRegionsSeedEachOtherLocally() {
        LoopbackTransport.LoopbackNetwork net = LoopbackTransport.LoopbackNetwork.newNetwork();
        NodeIdentity id = NodeIdentity.generate();
        Worker w = worker(net, id);

        w.service.activateRegion(floored(EAST), solo(EAST, id));
        w.service.activateRegion(originWithEasternWater(), solo(ORIGIN, id));

        assertThat(w.service.halo(EAST).isEmpty())
                .as("a solo host is the common live case and has no second peer to route through")
                .isFalse();
        assertThat(w.service.proposeBatch(EAST, 0, 120, List.of())).isPresent();
        assertThat(FluidRules.isWater(
                blockAt(w.service.currentSnapshot(EAST).orElseThrow(), FAR_BANK))).isTrue();

        // A revoked replica must not keep reading a border it no longer participates in.
        w.service.revokeRegion(EAST);
        assertThat(w.service.halo(EAST).isEmpty()).isTrue();
    }

    /** The validator learns of the commit by announcement, so its certificate is polled for. */
    private static void awaitCertificate(Worker worker) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline
                && worker.service.latestCertificate(EAST).isEmpty()) {
            Thread.sleep(10L);
        }
    }

    /** Delivery is off the sender's thread on this transport, so the receipt is polled for. */
    private static void awaitHalo(Worker worker) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && worker.service.halo(EAST).isEmpty()) {
            Thread.sleep(10L);
        }
        assertThat(worker.service.halo(EAST).isEmpty())
                .as("the neighbour's edge slice must arrive over the wire")
                .isFalse();
    }

    // --- fixtures ------------------------------------------------------------------------

    private record Worker(NodeIdentity id, WorkerValidationService service) {
    }

    private Worker worker(LoopbackTransport.LoopbackNetwork net, NodeIdentity id) {
        LoopbackTransport tx = net.register(id.nodeId());
        transports.add(tx);
        WorkerValidationService service = new WorkerValidationService(id, tx,
                new FlatWorldRegionEngine(FlatWorldRules.RULES_VERSION,
                        FlatWorldRules.registryFingerprint(), hashes),
                hashes, new InMemoryCertificateStore(hashes), WORLD_SEED,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), 5_000L);
        tx.setHandler(new MessageHandler() {
            @Override
            public void onMessage(PeerAddress from, byte[] frame) {
                var message = WireCodec.decode(frame);
                if (message instanceof dev.nodera.protocol.simulationmsg.HaloUpdate) {
                    haloFrames.incrementAndGet();
                }
                service.onMessage(from, message);
            }

            @Override
            public void onPeerDown(PeerAddress peer) {
            }
        });
        tx.start();
        return new Worker(id, service);
    }

    private static RegionLease solo(RegionId region, NodeIdentity primary) {
        return new RegionLease(region, RegionEpoch.INITIAL, primary.nodeId(), List.of(), 0, 10_000);
    }

    /** An all-air region with a stone floor, so a flow has support. */
    private static RegionSnapshot floored(RegionId region) {
        List<ChunkColumnState> chunks = new ArrayList<>();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                int[] sections = new int[SECTIONS];
                sections[AIR_SECTION - 1] = FlatWorldRules.STONE;
                chunks.add(new ChunkColumnState(region.originChunkX() + dx,
                        region.originChunkZ() + dz, sections, MIN_Y, SECTIONS));
            }
        }
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0, chunks);
    }

    /** ORIGIN with water filling the chunk columns that EAST reads as its western halo. */
    private static RegionSnapshot originWithEasternWater() {
        List<ChunkColumnState> chunks = new ArrayList<>();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                int[] sections = new int[SECTIONS];
                sections[AIR_SECTION - 1] = FlatWorldRules.STONE;
                if (dx == 7) {
                    sections[AIR_SECTION] = FlatWorldRules.WATER_SOURCE;
                }
                chunks.add(new ChunkColumnState(ORIGIN.originChunkX() + dx,
                        ORIGIN.originChunkZ() + dz, sections, MIN_Y, SECTIONS));
            }
        }
        return new RegionSnapshot(ORIGIN, SnapshotVersion.INITIAL, 0, chunks);
    }

    private static int blockAt(RegionSnapshot snapshot, NBlockPos pos) {
        for (ChunkColumnState col : snapshot.chunks()) {
            if (col.chunkX() == Math.floorDiv(pos.x(), 16)
                    && col.chunkZ() == Math.floorDiv(pos.z(), 16)) {
                return col.blockAt(Math.floorDiv(pos.y() - col.minY(), 16),
                        Math.floorMod(pos.x(), 16), Math.floorMod(pos.y() - col.minY(), 16),
                        Math.floorMod(pos.z(), 16));
            }
        }
        return FlatWorldRules.AIR;
    }
}
