package dev.nodera.distribution;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 19 acceptance #5: two selectors given the same {@code (manifest, holderSet)} must request
 * pieces in the same order. That is a consensus-adjacent property — if it fails, two peers fetching
 * the same world make different requests and the "deterministic rarest-first" contract Task 21's
 * placement audit relies on evaporates.
 *
 * <p>Thread-context: single test thread.
 */
final class PieceSelectorTest {

    private static PieceManifest manifest(int pieces) {
        byte[] blob = new byte[pieces * 64];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (i * 17 + 5);
        }
        List<Piece> list = PieceSplitter.splitFixed(blob, 64);
        dev.nodera.core.Bytes hash = DistFixtures.hashes().sha256(blob);
        return PieceManifest.of(
                DistFixtures.region(0, 0), new SnapshotVersion(1L), 1L,
                dev.nodera.core.state.StateRoot.of(hash),
                new dev.nodera.storage.ContentId(hash, blob.length,
                        dev.nodera.storage.Compression.NONE),
                blob.length, list);
    }

    private static Set<Integer> setOf(int... values) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int v : values) {
            out.add(v);
        }
        return out;
    }

    @Test
    void ordersRarestFirst() {
        PieceManifest m = manifest(6);
        Map<NodeId, Set<Integer>> holders = new LinkedHashMap<>();
        holders.put(DistFixtures.node(1), setOf(0, 1, 2, 3, 4));
        holders.put(DistFixtures.node(2), setOf(0, 1, 2, 3));
        holders.put(DistFixtures.node(3), setOf(0, 1, 2));
        // piece 5 is held by nobody; 4 by one peer; 3 by two; 0-2 by three.

        List<Integer> order = PieceSelector.order(m, holders, List.of(0, 1, 2, 3, 4, 5));

        assertThat(order).hasSize(6);
        assertThat(order.get(0)).isEqualTo(5);   // 0 holders
        assertThat(order.get(1)).isEqualTo(4);   // 1 holder
        assertThat(order.get(2)).isEqualTo(3);   // 2 holders
        assertThat(order.subList(3, 6)).containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    void twoSelectorsAgreeOnOrderRegardlessOfHolderMapIterationOrder() {
        PieceManifest m = manifest(24);

        Map<NodeId, Set<Integer>> insertionOrdered = new LinkedHashMap<>();
        Map<NodeId, Set<Integer>> hashOrdered = new HashMap<>();
        for (int peer = 1; peer <= 5; peer++) {
            Set<Integer> held = new LinkedHashSet<>();
            for (int piece = 0; piece < 24; piece++) {
                if ((piece + peer) % (peer + 1) == 0) {
                    held.add(piece);
                }
            }
            insertionOrdered.put(DistFixtures.node(peer), held);
            hashOrdered.put(DistFixtures.node(peer), held);
        }
        List<Integer> wanted = new ArrayList<>();
        for (int i = 23; i >= 0; i--) {
            wanted.add(i);   // deliberately reversed: input order must not leak into output
        }

        List<Integer> a = PieceSelector.order(m, insertionOrdered, wanted);
        List<Integer> b = PieceSelector.order(m, hashOrdered, List.of(
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void tieBreakIsNotIndexOrderSoConcurrentFetchersDoNotAllGrabPieceZero() {
        PieceManifest m = manifest(16);
        Map<NodeId, Set<Integer>> holders = new LinkedHashMap<>();
        Set<Integer> all = new LinkedHashSet<>();
        for (int i = 0; i < 16; i++) {
            all.add(i);
        }
        holders.put(DistFixtures.node(1), all);

        List<Integer> order = PieceSelector.order(m, holders,
                List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15));

        // Every holder count is equal, so the order is decided entirely by the hash tie-break.
        // If it degenerated to index order, all fetchers would serialise on the same seeder.
        assertThat(order).containsExactlyInAnyOrderElementsOf(all);
        assertThat(order).isNotEqualTo(new ArrayList<>(all));
    }

    @Test
    void holderChoiceIsDeterministicSkipsExcludedPeersAndSpreadsAcrossPieces() {
        PieceManifest m = manifest(8);
        Map<NodeId, Set<Integer>> holders = new LinkedHashMap<>();
        Set<Integer> all = setOf(0, 1, 2, 3, 4, 5, 6, 7);
        NodeId a = DistFixtures.node(11);
        NodeId b = DistFixtures.node(22);
        NodeId c = DistFixtures.node(33);
        holders.put(a, all);
        holders.put(b, all);
        holders.put(c, all);

        Set<NodeId> chosen = new HashSet<>();
        for (int piece = 0; piece < 8; piece++) {
            NodeId first = PieceSelector.chooseHolder(m.manifestRoot(), piece, holders, Set.of());
            NodeId again = PieceSelector.chooseHolder(m.manifestRoot(), piece, holders, Set.of());
            assertThat(again).isEqualTo(first);
            chosen.add(first);
        }
        // Rendezvous by (root, piece, node): different pieces prefer different seeders, which is
        // what actually parallelises a swarm.
        assertThat(chosen).hasSizeGreaterThan(1);

        NodeId picked = PieceSelector.chooseHolder(m.manifestRoot(), 0, holders, Set.of());
        NodeId alternate = PieceSelector.chooseHolder(m.manifestRoot(), 0, holders, Set.of(picked));
        assertThat(alternate).isNotNull().isNotEqualTo(picked);

        assertThat(PieceSelector.chooseHolder(m.manifestRoot(), 0, holders, Set.of(a, b, c)))
                .isNull();
    }

    @Test
    void holderChoiceReturnsNullWhenNobodyHoldsThePiece() {
        PieceManifest m = manifest(4);
        Map<NodeId, Set<Integer>> holders = new LinkedHashMap<>();
        holders.put(DistFixtures.node(1), setOf(0, 1));

        assertThat(PieceSelector.chooseHolder(m.manifestRoot(), 3, holders, Set.of())).isNull();
        assertThat(PieceSelector.holderCount(holders, 3)).isZero();
        assertThat(PieceSelector.holderCount(holders, 0)).isEqualTo(1);
    }
}
