package dev.nodera.peer;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.distribution.EmergencyFlush;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.RegionSnapshotSplitter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Task-24 shutdown ordering, deadline, hook-registration, and once-only behavior. */
final class PeerShutdownHookTest {

    @Test
    void gracefulAndRegisteredHookShareFlushBeforeStopSequenceExactlyOnce() {
        RegionSnapshotSplitter.Layout layout = onePieceLayout(1);
        NodeId self = node(1);
        NodeId target = node(2);
        List<String> events = new ArrayList<>();
        EmergencyFlush flush = new EmergencyFlush(
                (manifest, index, departing, current) -> List.of(target),
                (ignored, manifest, index, payload) -> {
                    events.add("flush:" + index);
                    return CompletableFuture.completedFuture(
                            manifest.verifyPiece(index, payload));
                });
        PeerShutdownHook hook = new PeerShutdownHook(
                self, flush, () -> List.of(local(layout, self)), Duration.ofSeconds(1),
                () -> events.add("stop"));

        assertThat(hook.register()).isTrue();
        assertThat(hook.register()).isFalse();
        PeerShutdownHook.ShutdownResult first = hook.gracefulShutdown();
        PeerShutdownHook.ShutdownResult duplicate = hook.gracefulShutdown();

        assertThat(first).isEqualTo(duplicate);
        assertThat(first.flush().stored()).isEqualTo(1);
        assertThat(first.afterFlushCompleted()).isTrue();
        assertThat(events).containsExactly("flush:0", "stop");
        assertThat(hook.hasStarted()).isTrue();
    }

    @Test
    void unreachableHolderTimesOutThenShutdownStillContinues() {
        RegionSnapshotSplitter.Layout layout = onePieceLayout(2);
        NodeId self = node(10);
        NodeId target = node(11);
        List<String> events = new ArrayList<>();
        EmergencyFlush flush = new EmergencyFlush(
                (manifest, index, departing, current) -> List.of(target),
                (ignored, manifest, index, payload) -> {
                    events.add("flush-start");
                    return new CompletableFuture<>();
                });
        PeerShutdownHook hook = new PeerShutdownHook(
                self, flush, () -> List.of(local(layout, self)), Duration.ofMillis(75),
                () -> events.add("stop"));

        long started = System.nanoTime();
        PeerShutdownHook.ShutdownResult result = hook.gracefulShutdown();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result.flush().timedOut()).isTrue();
        assertThat(result.flush().remainingUnderReplicatedPieces()).isEqualTo(1);
        assertThat(events).containsExactly("flush-start", "stop");
        assertThat(elapsed).isLessThan(Duration.ofMillis(600));
    }

    @Test
    void overlappingGracefulCallsWaitForSameFlush() throws Exception {
        RegionSnapshotSplitter.Layout layout = onePieceLayout(3);
        NodeId self = node(20);
        NodeId target = node(21);
        CompletableFuture<Boolean> storageAck = new CompletableFuture<>();
        CountDownLatch transferStarted = new CountDownLatch(1);
        AtomicInteger transfers = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        EmergencyFlush flush = new EmergencyFlush(
                (manifest, index, departing, current) -> List.of(target),
                (ignored, manifest, index, payload) -> {
                    transfers.incrementAndGet();
                    transferStarted.countDown();
                    return storageAck;
                });
        PeerShutdownHook hook = new PeerShutdownHook(
                self, flush, () -> List.of(local(layout, self)), Duration.ofSeconds(1),
                stops::incrementAndGet);

        CompletableFuture<PeerShutdownHook.ShutdownResult> first =
                CompletableFuture.supplyAsync(hook::gracefulShutdown);
        assertThat(transferStarted.await(1, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<PeerShutdownHook.ShutdownResult> second =
                CompletableFuture.supplyAsync(hook::gracefulShutdown);
        storageAck.complete(true);

        assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo(second.get(1, TimeUnit.SECONDS));
        assertThat(transfers).hasValue(1);
        assertThat(stops).hasValue(1);
    }

    private static EmergencyFlush.LocalHolding local(
            RegionSnapshotSplitter.Layout layout, NodeId self) {
        Map<Integer, Set<NodeId>> holders = new LinkedHashMap<>();
        for (int i = 0; i < layout.manifest().pieceCount(); i++) {
            holders.put(i, Set.of(self));
        }
        return new EmergencyFlush.LocalHolding(
                layout.manifest(), layout.blob(), 1, holders);
    }

    private static RegionSnapshotSplitter.Layout onePieceLayout(long version) {
        RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);
        List<ChunkColumnState> chunks = new ArrayList<>(64);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                chunks.add(new ChunkColumnState(x, z, new int[] {(int) version}, 0, 1));
            }
        }
        RegionSnapshot snapshot = new RegionSnapshot(
                region, new SnapshotVersion(version), version * 2, chunks);
        RegionSnapshotSplitter.Layout layout =
                RegionSnapshotSplitter.split(snapshot, Integer.MAX_VALUE);
        assertThat(layout.manifest().pieceCount()).isEqualTo(1);
        return layout;
    }

    private static NodeId node(long id) {
        return new NodeId(new UUID(0L, id));
    }
}
