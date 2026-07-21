package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.peer.discovery.ArchiveInventory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit turns "expected vs actual" into a repair plan. The key behaviour: a promoted peer (one
 * that entered the expected set because a higher-ranked peer died) appears as a target missing
 * every piece, while a surviving expected peer that already holds everything appears nowhere.
 *
 * <p>Thread-context: single test thread.
 */
final class ArchiveAuditTaskTest {

    private static final Bytes WORLD = ArchivalFixtures.manifestHash(0);
    private static final Bytes MANIFEST = ArchivalFixtures.manifestHash(1);

    private record Placed(List<NodeId> expected, List<NodeId> partials, NodeId host) {}

    /** Run placement once to learn the expected set, then build the test around it. */
    private static Placed place(int partialCount, boolean withHost) {
        RendezvousArchivePolicy policy = new RendezvousArchivePolicy();
        List<NodeId> partials = new ArrayList<>();
        for (int i = 1; i <= partialCount; i++) {
            partials.add(ArchivalFixtures.node(i));
        }
        NodeId host = withHost ? ArchivalFixtures.node(999) : null;
        List<NodeId> eligible = new ArrayList<>(partials);
        Set<NodeId> full = new java.util.HashSet<>();
        if (host != null) {
            eligible.add(host);
            full.add(host);
        }
        List<NodeId> expected = policy.expectedHolders(
                MANIFEST, ArchiveObjectClass.SNAPSHOT, eligible, full);
        return new Placed(expected, partials, host);
    }

    @Test
    void aFullyReplicatedManifestIsHealthy() {
        Placed placed = place(8, false);
        ArchiveInventory inventory = new ArchiveInventory();
        int pieces = 6;
        // Every expected holder holds every piece.
        for (NodeId h : placed.expected) {
            inventory.record(WORLD, MANIFEST, h, bitmapOf(pieces));
        }

        ArchiveAuditTask audit = new ArchiveAuditTask(new RendezvousArchivePolicy());
        ArchiveAuditTask.AuditResult result = audit.audit(MANIFEST, pieces,
                ArchiveObjectClass.SNAPSHOT, eligibleOf(placed), Set.of(), inventory);

        assertThat(result.isHealthy()).isTrue();
        assertThat(result.repairTargets()).isEmpty();
        assertThat(result.missingPieces()).isZero();
    }

    @Test
    void aPromotedPeerMissingAllPiecesBecomesARepairTarget() {
        Placed placed = place(8, false);
        ArchiveInventory inventory = new ArchiveInventory();
        int pieces = 6;
        // Only the FIRST expected holder has the pieces; the rest (promoted or not) are empty.
        inventory.record(WORLD, MANIFEST, placed.expected.get(0), bitmapOf(pieces));

        ArchiveAuditTask audit = new ArchiveAuditTask(new RendezvousArchivePolicy());
        ArchiveAuditTask.AuditResult result = audit.audit(MANIFEST, pieces,
                ArchiveObjectClass.SNAPSHOT, eligibleOf(placed), Set.of(), inventory);

        // 5 expected holders, one satisfied → 4 targets each missing all 6 pieces = 24 missing.
        assertThat(result.expectedHolders()).hasSize(5);
        assertThat(result.repairTargets()).hasSize(4);
        assertThat(result.missingPieces()).isEqualTo(4 * 6);
        // The satisfied holder is not a target.
        assertThat(result.repairTargets())
                .noneMatch(t -> t.assignee().equals(placed.expected.get(0)));
    }

    @Test
    void killingAHoldersPromotesTheNextRankedPeerIntoTheExpectedSet() {
        // 8 partial peers; expected = top-5.
        Placed whole = place(8, false);
        List<NodeId> top5 = whole.expected;
        NodeId killed = top5.get(0);
        // Survivors = everyone except the killed peer.
        List<NodeId> survivors = new ArrayList<>(whole.partials);
        survivors.remove(killed);

        ArchiveInventory inventory = new ArchiveInventory();
        int pieces = 4;
        // Before the kill, the top-5 each held all pieces. After the kill, the killed peer is gone;
        // the next-ranked survivor (rank 6) is promoted but holds nothing.
        for (NodeId h : top5) {
            if (!h.equals(killed)) {
                inventory.record(WORLD, MANIFEST, h, bitmapOf(pieces));
            }
        }

        ArchiveAuditTask audit = new ArchiveAuditTask(new RendezvousArchivePolicy());
        ArchiveAuditTask.AuditResult result = audit.audit(MANIFEST, pieces,
                ArchiveObjectClass.SNAPSHOT, survivors, Set.of(), inventory);

        // Still 5 expected holders (the promoted rank-6 peer fills the dead one's slot).
        assertThat(result.expectedHolders()).hasSize(5);
        assertThat(result.expectedHolders()).doesNotContain(killed);
        // Exactly one target: the promoted peer, missing all pieces.
        assertThat(result.repairTargets()).hasSize(1);
        assertThat(result.repairTargets().get(0).pieceIndexes())
                .containsExactly(0, 1, 2, 3);
    }

    @Test
    void anEmptyInventoryMeansEveryExpectedHolderIsATarget() {
        Placed placed = place(6, false);
        ArchiveAuditTask audit = new ArchiveAuditTask(new RendezvousArchivePolicy());
        ArchiveAuditTask.AuditResult result = audit.audit(MANIFEST, 5,
                ArchiveObjectClass.SNAPSHOT, eligibleOf(placed), Set.of(), new ArchiveInventory());

        assertThat(result.repairTargets()).hasSize(5);
        assertThat(result.missingPieces()).isEqualTo(5 * 5);
    }

    private static List<NodeId> eligibleOf(Placed placed) {
        List<NodeId> all = new ArrayList<>(placed.partials);
        if (placed.host != null) {
            all.add(placed.host);
        }
        return all;
    }

    private static dev.nodera.core.Bytes bitmapOf(int pieces) {
        java.util.Set<Integer> set = new java.util.LinkedHashSet<>();
        for (int i = 0; i < pieces; i++) {
            set.add(i);
        }
        return dev.nodera.protocol.content.PieceBitmap.of(set);
    }
}
