package dev.nodera.coordinator;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RegionAllocatorTest {

    private NodeRegistry registryWith(int count) {
        NodeRegistry reg = new NodeRegistry();
        for (int i = 0; i < count; i++) {
            NodeId id = CoordFixtures.node(100 + i);
            reg.register(id, CoordFixtures.caps());
            reg.setConnected(id, true);
        }
        return reg;
    }

    @Test
    void allocatesDistinctCommittee() {
        NodeRegistry reg = registryWith(4);
        RegionAllocator alloc = new RegionAllocator(new RendezvousPlacementPolicy(), reg, new ReliabilityLedger());
        Optional<RegionAllocator.Committee> committee = alloc.allocate(CoordFixtures.region(0, 0), 3);
        assertThat(committee).isPresent();
        RegionAllocator.Committee c = committee.get();
        assertThat(c.validators()).hasSize(2);
        assertThat(c.members()).doesNotHaveDuplicates().hasSize(3);
    }

    @Test
    void tooFewCandidatesYieldsEmpty() {
        NodeRegistry reg = registryWith(2);
        RegionAllocator alloc = new RegionAllocator(new RendezvousPlacementPolicy(), reg, new ReliabilityLedger());
        assertThat(alloc.allocate(CoordFixtures.region(0, 0), 3)).isEmpty();
    }

    @Test
    void reassignmentExcludesFailedNode() {
        NodeRegistry reg = registryWith(4);
        RegionAllocator alloc = new RegionAllocator(new RendezvousPlacementPolicy(), reg, new ReliabilityLedger());
        RegionId region = CoordFixtures.region(0, 0);
        NodeId firstPrimary = alloc.allocate(region, 3).orElseThrow().primary();

        RegionAllocator.Committee reassigned =
                alloc.allocate(region, 3, Set.of(firstPrimary)).orElseThrow();
        assertThat(reassigned.members()).doesNotContain(firstPrimary);
    }

    @Test
    void unreliableNodesAreNotEligible() {
        NodeRegistry reg = registryWith(3);
        ReliabilityLedger ledger = new ReliabilityLedger();
        // Drop every node below the floor.
        for (int i = 0; i < 3; i++) {
            ledger.slash(CoordFixtures.node(100 + i));
        }
        RegionAllocator alloc = new RegionAllocator(new RendezvousPlacementPolicy(), reg, ledger);
        assertThat(alloc.allocate(CoordFixtures.region(0, 0), 3)).isEmpty();
    }

    @Test
    void primaryLoadCapEnforced() {
        NodeRegistry reg = new NodeRegistry();
        NodeId solo = CoordFixtures.node(1L);
        reg.register(solo, CoordFixtures.caps(4, 0.99, 1, 8)); // maxPrimaryRegions = 1
        reg.setConnected(solo, true);
        RegionAllocator alloc = new RegionAllocator(new RendezvousPlacementPolicy(), reg, new ReliabilityLedger());

        assertThat(alloc.allocate(CoordFixtures.region(0, 0), 1)).isPresent();
        assertThat(alloc.primaryLoad(solo)).isEqualTo(1);
        // Second region cannot use the same node as primary (cap reached), and there is no other.
        assertThat(alloc.allocate(CoordFixtures.region(0, 1), 1)).isEmpty();
    }
}
