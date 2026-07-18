package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The manager is the per-peer counterpart to the global audit: it keeps assigned-region current
 * state and evicts only over-cap unassigned content.
 *
 * <p>Thread-context: single test thread.
 */
final class ArchiveManagerTest {

    @Test
    void aPeerSeedsExactlyTheManifestsItIsAnExpectedHolderOf() {
        RendezvousArchivePolicy policy = new RendezvousArchivePolicy();
        ArchiveManager manager = new ArchiveManager(policy);
        NodeId self = ArchivalFixtures.node(1);

        List<NodeId> eligible = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            eligible.add(ArchivalFixtures.node(i));
        }
        // 5 manifests; the policy picks 5 distinct holders per snapshot among the 20.
        Map<Bytes, ArchiveObjectClass> classes = new LinkedHashMap<>();
        for (int m = 1; m <= 5; m++) {
            classes.put(ArchivalFixtures.manifestHash(m), ArchiveObjectClass.SNAPSHOT);
        }

        Set<Bytes> assigned = manager.assignedManifests(self, new java.util.ArrayList<>(classes.keySet()),
                classes::get, eligible, Set.of());

        // Whatever it is, it equals the set of manifests for which placement selected this peer.
        Set<Bytes> expected = new java.util.LinkedHashSet<>();
        for (Bytes root : classes.keySet()) {
            if (policy.expectedHolders(root, ArchiveObjectClass.SNAPSHOT, eligible, Set.of()).contains(self)) {
                expected.add(root);
            }
        }
        assertThat(assigned).isEqualTo(expected);
    }

    @Test
    void neverEvictsAssignedRegionCurrentStateEvenAboveTheCap() {
        ArchiveManager manager = new ArchiveManager(new RendezvousArchivePolicy());
        NodeId self = ArchivalFixtures.node(1);
        Bytes assigned = ArchivalFixtures.manifestHash(10);

        // The peer holds only its assigned manifest, but "too much" of it (100% of a tiny world).
        // It must retain all of it — assigned-region current state is never evicted.
        Map<Bytes, Integer> held = new LinkedHashMap<>();
        held.put(assigned, 100);

        ArchiveManager.ReconcilePlan plan = manager.reconcile(
                self, held, Set.of(assigned), 100, 5, 200, false);

        assertThat(plan.retained()).containsEntry(assigned, 100);
        assertThat(plan.evictable()).isEmpty();
        assertThat(plan.isBalanced()).isTrue();
    }

    @Test
    void evictsUnassignedContentOverTheCapForNonHostPeers() {
        ArchiveManager manager = new ArchiveManager(new RendezvousArchivePolicy());
        NodeId self = ArchivalFixtures.node(1);
        // N=200, R=5 → cap = 5% = 5 pieces of 100.
        int total = 100;
        Map<Bytes, Integer> held = new LinkedHashMap<>();
        // 10 unassigned manifests × 1 piece each = 10 pieces = 10% > 5% cap → evict down to 5.
        for (int m = 1; m <= 10; m++) {
            held.put(ArchivalFixtures.manifestHash(m), 1);
        }

        ArchiveManager.ReconcilePlan plan = manager.reconcile(
                self, held, Set.of(), total, 5, 200, false);

        int retained = plan.retained().values().stream().mapToInt(Integer::intValue).sum();
        assertThat(retained).isLessThanOrEqualTo(5);
        assertThat(plan.evictable()).isNotEmpty();
    }

    @Test
    void theFullArchiveHostIsNeverAskedToEvict() {
        ArchiveManager manager = new ArchiveManager(new RendezvousArchivePolicy());
        NodeId host = ArchivalFixtures.node(999);
        Map<Bytes, Integer> held = new LinkedHashMap<>();
        for (int m = 1; m <= 50; m++) {
            held.put(ArchivalFixtures.manifestHash(m), 10);   // 500 pieces = 500% — way over any cap
        }

        ArchiveManager.ReconcilePlan plan = manager.reconcile(
                host, held, Set.of(), 100, 5, 200, true);

        assertThat(plan.evictable()).isEmpty();
        assertThat(plan.retained()).isEqualTo(held);
    }
}
