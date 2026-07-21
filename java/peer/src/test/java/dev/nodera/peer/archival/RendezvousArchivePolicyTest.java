package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Placement is the foundation the rest of Task 21 builds on, and it is a pure function — so the
 * acceptance property (#1) is directly testable: two peers with the same inputs compute the
 * identical expected set, the set holds R distinct partial peers plus the host, and the host never
 * counts toward R.
 *
 * <p>Thread-context: single test thread.
 */
final class RendezvousArchivePolicyTest {

    private static List<NodeId> partials(int n) {
        List<NodeId> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(ArchivalFixtures.node(i));
        }
        return out;
    }

    @Test
    void expectedHoldersIsAPureFunctionAgreedOnByEveryPeer() {
        RendezvousArchivePolicy policy = new RendezvousArchivePolicy();
        Bytes manifest = ArchivalFixtures.manifestHash(42);
        List<NodeId> eligible = partials(20);

        // Two "peers" compute expected holders from the SAME inputs but present the eligible list
        // in opposite orders — the result must be identical.
        List<NodeId> a = policy.expectedHolders(manifest, ArchiveObjectClass.SNAPSHOT,
                eligible, Set.of());
        List<NodeId> b = policy.expectedHolders(manifest, ArchiveObjectClass.SNAPSHOT,
                new ArrayList<>(reversed(eligible)), Set.of());

        assertThat(a).isEqualTo(b);
        // SNAPSHOT factor = 5 → 5 distinct partial holders.
        assertThat(a).hasSize(5);
        assertThat(new HashSet<>(a)).hasSize(5);
    }

    @Test
    void selectsTheHighestSignedScoresInDescendingOrder() {
        RendezvousArchivePolicy policy = new RendezvousArchivePolicy();
        Bytes manifest = ArchivalFixtures.manifestHash(0x5c0);
        List<NodeId> eligible = partials(20);
        long rootScore = StableHash.of(manifest.toHex());

        List<NodeId> independentlyRanked = new ArrayList<>(eligible);
        independentlyRanked.sort(Comparator
                .comparingLong((NodeId peer) -> StableHash.of(
                        rootScore, StableHash.of(peer.value())))
                .reversed()
                .thenComparing(peer -> peer.value().toString()));

        assertThat(policy.expectedHolders(
                manifest, ArchiveObjectClass.SNAPSHOT, eligible, Set.of()))
                .containsExactlyElementsOf(independentlyRanked.subList(0, 5));
    }

    @Test
    void holdsRForEveryClassAndEveryoneForCheckpointAndGenesis() {
        RendezvousArchivePolicy policy = new RendezvousArchivePolicy();
        Bytes manifest = ArchivalFixtures.manifestHash(7);
        List<NodeId> eligible = partials(30);

        assertThat(policy.expectedHolders(manifest, ArchiveObjectClass.SNAPSHOT, eligible, Set.of()))
                .hasSize(5);
        assertThat(policy.expectedHolders(manifest, ArchiveObjectClass.RECENT_LOG, eligible, Set.of()))
                .hasSize(4);
        assertThat(policy.expectedHolders(manifest, ArchiveObjectClass.COMPACTED_LOG, eligible, Set.of()))
                .hasSize(3);
        // "Everyone" classes: every eligible peer.
        assertThat(new HashSet<>(policy.expectedHolders(
                manifest, ArchiveObjectClass.CHECKPOINT, eligible, Set.of())))
                .containsExactlyInAnyOrderElementsOf(eligible);
        assertThat(new HashSet<>(policy.expectedHolders(
                manifest, ArchiveObjectClass.GENESIS, eligible, Set.of())))
                .containsExactlyInAnyOrderElementsOf(eligible);
    }

    @Test
    void theHostIsAlwaysInTheExpectedSetAndDoesNotCountTowardR() {
        RendezvousArchivePolicy policy = new RendezvousArchivePolicy();
        Bytes manifest = ArchivalFixtures.manifestHash(99);
        NodeId host = ArchivalFixtures.node(999);
        List<NodeId> eligible = new ArrayList<>(partials(20));
        eligible.add(host);

        List<NodeId> expected = policy.expectedHolders(manifest, ArchiveObjectClass.SNAPSHOT,
                eligible, Set.of(host));

        // Host is present…
        assertThat(expected).contains(host);
        // …first (hosts lead the list)…
        assertThat(expected.get(0)).isEqualTo(host);
        // …and R=5 PARTIAL peers follow, so the set is R+1 and losing the host still leaves R.
        assertThat(expected).hasSize(6);
        assertThat(expected.subList(1, 6)).doesNotContain(host);
    }

    @Test
    void differentManifestsSelectDifferentHoldersSoReplicationSpreadsAcrossContent() {
        RendezvousArchivePolicy policy = new RendezvousArchivePolicy();
        List<NodeId> eligible = partials(20);

        Set<NodeId> forOne = new HashSet<>(policy.expectedHolders(
                ArchivalFixtures.manifestHash(1), ArchiveObjectClass.SNAPSHOT, eligible, Set.of()));
        Set<NodeId> forAnother = new HashSet<>(policy.expectedHolders(
                ArchivalFixtures.manifestHash(2), ArchiveObjectClass.SNAPSHOT, eligible, Set.of()));

        // Two distinct manifests must not land on exactly the same 5 peers, or one peer would hold
        // a disproportionate share of the world.
        assertThat(forOne).isNotEqualTo(forAnother);
    }

    @Test
    void capsAtTheEligibleCountWhenTheNetworkIsSmallerThanR() {
        RendezvousArchivePolicy policy = new RendezvousArchivePolicy();
        List<NodeId> eligible = partials(3);   // fewer than the snapshot factor of 5

        assertThat(policy.expectedHolders(ArchivalFixtures.manifestHash(5),
                ArchiveObjectClass.SNAPSHOT, eligible, Set.of())).hasSize(3);
    }

    @Test
    void rejectsAFullArchiveHostNotInTheEligibleSet() {
        RendezvousArchivePolicy policy = new RendezvousArchivePolicy();
        NodeId stranger = ArchivalFixtures.node(777);
        assertThatThrownBy(() -> policy.expectedHolders(ArchivalFixtures.manifestHash(1),
                ArchiveObjectClass.SNAPSHOT, partials(10), Set.of(stranger)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the eligible set");
    }

    @Test
    void replicationFactorsAreTheSpecValues() {
        ReplicationFactors f = ReplicationFactors.spec();
        assertThat(f.snapshot()).isEqualTo(5);
        assertThat(f.recentLog()).isEqualTo(4);
        assertThat(f.compacted()).isEqualTo(3);
        // checkpoint/genesis = everyone.
        assertThat(f.factor(ArchiveObjectClass.CHECKPOINT, 17)).isEqualTo(17);
        assertThat(f.factor(ArchiveObjectClass.GENESIS, 17)).isEqualTo(17);
        // countable classes are min(factor, N).
        assertThat(f.factor(ArchiveObjectClass.SNAPSHOT, 3)).isEqualTo(3);
    }

    private static List<NodeId> reversed(List<NodeId> in) {
        List<NodeId> out = new ArrayList<>(in);
        Collections.reverse(out);
        return out;
    }
}
