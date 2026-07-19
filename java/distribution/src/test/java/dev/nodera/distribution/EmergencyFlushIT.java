package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task-24 graceful-exit proof over real manifests and physically verified destination stores. */
final class EmergencyFlushIT {

    @Test
    void underReplicatedPiecesReachFactorElsewhereOnlyAfterPhysicalAck() {
        RegionSnapshotSplitter.Layout layout = layout(1);
        NodeId departing = DistFixtures.node(100);
        NodeId survivor = DistFixtures.node(101);
        NodeId nextA = DistFixtures.node(102);
        NodeId nextB = DistFixtures.node(103);
        Map<Integer, Set<NodeId>> holders = holders(
                layout.manifest(), Set.of(departing, survivor));
        Map<NodeId, Map<Integer, Bytes>> physical = new LinkedHashMap<>();
        seed(physical, survivor, layout);
        List<NodeId> attempts = new ArrayList<>();

        EmergencyFlush flush = new EmergencyFlush(
                (manifest, index, leaving, current) ->
                        List.of(leaving, survivor, nextA, nextB),
                (target, manifest, index, payload) -> {
                    attempts.add(target);
                    if (!manifest.verifyPiece(index, payload)) {
                        return CompletableFuture.completedFuture(false);
                    }
                    physical.computeIfAbsent(target, ignored -> new HashMap<>())
                            .put(index, payload);
                    boolean stored = payload.equals(physical.get(target).get(index));
                    return CompletableFuture.completedFuture(stored);
                });

        EmergencyFlush.FlushResult result = flush.flush(
                departing,
                List.of(new EmergencyFlush.LocalHolding(
                        layout.manifest(), layout.blob(), 3, holders)),
                Duration.ofSeconds(2));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.remainingUnderReplicatedPieces()).isZero();
        assertThat(result.stored()).isEqualTo(2 * layout.manifest().pieceCount());
        assertThat(attempts).doesNotContain(departing, survivor);
        for (int index = 0; index < layout.manifest().pieceCount(); index++) {
            assertThat(physical.get(survivor).get(index)).isNotNull();
            assertThat(physical.get(nextA).get(index)).isNotNull();
            assertThat(physical.get(nextB).get(index)).isNotNull();
            assertThat(layout.manifest().verifyPiece(index, physical.get(nextA).get(index))).isTrue();
            assertThat(layout.manifest().verifyPiece(index, physical.get(nextB).get(index))).isTrue();
        }
    }

    @Test
    void negativeStorageAckNeverCountsAsReplica() {
        RegionSnapshotSplitter.Layout layout = layout(2);
        NodeId departing = DistFixtures.node(200);
        NodeId survivor = DistFixtures.node(201);
        NodeId target = DistFixtures.node(202);
        EmergencyFlush flush = new EmergencyFlush(
                (manifest, index, leaving, current) -> List.of(target),
                (ignoredTarget, manifest, index, payload) ->
                        CompletableFuture.completedFuture(false));

        EmergencyFlush.FlushResult result = flush.flush(
                departing,
                List.of(new EmergencyFlush.LocalHolding(
                        layout.manifest(), layout.blob(), 2,
                        holders(layout.manifest(), Set.of(departing, survivor)))),
                Duration.ofSeconds(1));

        assertThat(result.stored()).isZero();
        assertThat(result.remainingUnderReplicatedPieces())
                .isEqualTo(layout.manifest().pieceCount());
        assertThat(result.skipped()).isGreaterThanOrEqualTo(layout.manifest().pieceCount());
    }

    @Test
    void oneNeverCompletingHolderCannotResetWholeFlushDeadline() {
        RegionSnapshotSplitter.Layout layout = layout(3);
        NodeId departing = DistFixtures.node(300);
        NodeId target = DistFixtures.node(301);
        EmergencyFlush flush = new EmergencyFlush(
                (manifest, index, leaving, current) -> List.of(target),
                (ignoredTarget, manifest, index, payload) -> new CompletableFuture<>());

        long started = System.nanoTime();
        EmergencyFlush.FlushResult result = flush.flush(
                departing,
                List.of(new EmergencyFlush.LocalHolding(
                        layout.manifest(), layout.blob(), 1,
                        holders(layout.manifest(), Set.of(departing)))),
                Duration.ofMillis(75));
        Duration wallElapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.stored()).isZero();
        assertThat(result.remainingUnderReplicatedPieces())
                .isEqualTo(layout.manifest().pieceCount());
        assertThat(wallElapsed).isLessThan(Duration.ofMillis(600));
        assertThatThrownBy(() -> flush.flush(
                departing,
                List.of(new EmergencyFlush.LocalHolding(
                        layout.manifest(), layout.blob(), 1,
                        holders(layout.manifest(), Set.of(departing)))),
                Duration.ofNanos(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void vulnerablePiecesThenNewestManifestReceivePriority() {
        RegionSnapshotSplitter.Layout older = layout(4);
        RegionSnapshotSplitter.Layout newer = layout(5);
        NodeId departing = DistFixtures.node(400);
        NodeId survivor = DistFixtures.node(401);
        NodeId targetA = DistFixtures.node(402);
        NodeId targetB = DistFixtures.node(403);
        List<String> order = new ArrayList<>();
        EmergencyFlush flush = new EmergencyFlush(
                (manifest, index, leaving, current) -> List.of(targetA, targetB),
                (target, manifest, index, payload) -> {
                    order.add(manifest.version().value() + ":" + index);
                    return CompletableFuture.completedFuture(true);
                });

        EmergencyFlush.LocalHolding lowReplication = new EmergencyFlush.LocalHolding(
                older.manifest(), older.blob(), 2,
                holders(older.manifest(), Set.of(departing)));
        EmergencyFlush.LocalHolding newerButLessVulnerable = new EmergencyFlush.LocalHolding(
                newer.manifest(), newer.blob(), 2,
                holders(newer.manifest(), Set.of(departing, survivor)));

        EmergencyFlush.FlushResult result = flush.flush(
                departing, List.of(newerButLessVulnerable, lowReplication), Duration.ofSeconds(2));

        assertThat(result.remainingUnderReplicatedPieces()).isZero();
        assertThat(order).isNotEmpty();
        // Every zero-survivor v4 piece is more vulnerable than one-survivor v5. Once each v4 piece
        // gains one holder, both manifests tie at one and newest v5 wins the next transfer.
        assertThat(order.subList(0, older.manifest().pieceCount()))
                .allMatch(entry -> entry.startsWith("4:"));
        assertThat(order.get(older.manifest().pieceCount())).startsWith("5:");
    }

    private static Map<Integer, Set<NodeId>> holders(
            PieceManifest manifest, Set<NodeId> nodes) {
        Map<Integer, Set<NodeId>> out = new LinkedHashMap<>();
        for (int i = 0; i < manifest.pieceCount(); i++) {
            out.put(i, new LinkedHashSet<>(nodes));
        }
        return out;
    }

    private static void seed(
            Map<NodeId, Map<Integer, Bytes>> physical,
            NodeId holder,
            RegionSnapshotSplitter.Layout layout) {
        Map<Integer, Bytes> pieces = physical.computeIfAbsent(holder, ignored -> new HashMap<>());
        for (int i = 0; i < layout.manifest().pieceCount(); i++) {
            Piece piece = layout.manifest().piece(i);
            pieces.put(i, new Bytes(
                    layout.blob().toArray(),
                    Math.toIntExact(piece.offset()),
                    Math.toIntExact(piece.length())));
        }
    }

    private static RegionSnapshotSplitter.Layout layout(long version) {
        RegionSnapshot initial = DistFixtures.variedSnapshot(
                DistFixtures.region(7, 8), new SnapshotVersion(version), version * 2);
        return RegionSnapshotSplitter.split(initial, 512);
    }
}
