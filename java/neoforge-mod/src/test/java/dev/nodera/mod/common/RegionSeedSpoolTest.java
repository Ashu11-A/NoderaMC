package dev.nodera.mod.common;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Worker L-41's mod half: committed regions reach the always-on worker without the commit path ever
 * waiting on disk or a socket.
 *
 * <p>These tests are mostly about the answers that are <b>not</b> failures. A single-player world
 * that was never shared has no world id, a worker that is offline accepts nothing, and a backlog
 * that fills has to drop something — none of those may propagate into the lane that produced the
 * snapshot, because a region that failed to seed costs availability and a commit that failed costs
 * the world.
 */
final class RegionSeedSpoolTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final RegionId OTHER = new RegionId(DimensionKey.overworld(), 1, 0);

    private static RegionSnapshot snapshot(RegionId region, long version) {
        List<ChunkColumnState> chunks = new ArrayList<>();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                chunks.add(new ChunkColumnState(region.originChunkX() + dx,
                        region.originChunkZ() + dz, new int[24], -64, 24));
            }
        }
        return new RegionSnapshot(region, new SnapshotVersion(version), version, chunks);
    }

    /** Records what was pushed, and can block or fail on demand. */
    private static final class RecordingPusher implements RegionSeedSpool.Pusher {
        private final List<String> pushed = java.util.Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch arrived;
        private volatile CountDownLatch hold;
        private volatile boolean accept = true;

        RecordingPusher(int expected) {
            this.arrived = new CountDownLatch(expected);
        }

        @Override
        public boolean push(String worldIdHex, Path snapshotFile) {
            CountDownLatch gate = hold;
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // The file must still exist when the worker would read it — the spool deletes it only
            // after this returns.
            pushed.add(worldIdHex + " " + java.nio.file.Files.exists(snapshotFile));
            arrived.countDown();
            return accept;
        }
    }

    @Test
    @DisplayName("a committed snapshot reaches the worker, with its file still readable")
    void aCommitIsPushed(@TempDir Path dir) throws Exception {
        RecordingPusher pusher = new RecordingPusher(1);
        RegionSeedSpool spool = new RegionSeedSpool(() -> "abc123", () -> dir, pusher, 0L);

        assertThat(spool.offer(snapshot(REGION, 1))).isTrue();

        assertThat(pusher.arrived.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(pusher.pushed).containsExactly("abc123 true");
        spool.close();
    }

    @Test
    @DisplayName("without a world id nothing is pushed, and that is the ordinary case")
    void noWorldIdentityIsNotAFailure(@TempDir Path dir) {
        RecordingPusher pusher = new RecordingPusher(1);
        // A single-player world that was never shared has no world id to file content under.
        RegionSeedSpool spool = new RegionSeedSpool(() -> "", () -> dir, pusher, 0L);

        assertThat(spool.offer(snapshot(REGION, 1))).isFalse();
        assertThat(pusher.pushed).isEmpty();
        spool.close();
    }

    @Test
    @DisplayName("one region is pushed once per throttle interval, however often it commits")
    void commitsAreThrottledPerRegion(@TempDir Path dir) throws Exception {
        RecordingPusher pusher = new RecordingPusher(2);
        RegionSeedSpool spool =
                new RegionSeedSpool(() -> "w", () -> dir, pusher, 60_000L);

        assertThat(spool.offer(snapshot(REGION, 1))).isTrue();
        for (long version = 2; version <= 20; version++) {
            assertThat(spool.offer(snapshot(REGION, version))).isFalse();
        }
        // The throttle is per region: a different region is not silenced by a busy neighbour.
        assertThat(spool.offer(snapshot(OTHER, 1))).isTrue();

        assertThat(pusher.arrived.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(pusher.pushed).hasSize(2);
        spool.close();
    }

    @Test
    @DisplayName("a stalled worker drops snapshots instead of queueing them without limit")
    void afullBacklogDropsRatherThanGrows(@TempDir Path dir) throws Exception {
        RecordingPusher pusher = new RecordingPusher(1);
        pusher.hold = new CountDownLatch(1);
        RegionSeedSpool spool = new RegionSeedSpool(() -> "w", () -> dir, pusher, 0L);

        // One offer occupies the single thread; the rest fill the bounded queue and then drop.
        int accepted = 0;
        for (int i = 0; i < RegionSeedSpool.QUEUE_DEPTH + 20; i++) {
            if (spool.offer(snapshot(new RegionId(DimensionKey.overworld(), i, 0), 1))) {
                accepted++;
            }
        }

        assertThat(accepted)
                .as("the backlog is bounded, so a stalled worker cannot grow it without limit")
                .isLessThanOrEqualTo(RegionSeedSpool.QUEUE_DEPTH + 1);
        pusher.hold.countDown();
        spool.close();
    }

    @Test
    @DisplayName("a dropped snapshot does not also silence its region for a throttle interval")
    void aDropDoesNotSilenceTheRegion(@TempDir Path dir) throws Exception {
        RecordingPusher pusher = new RecordingPusher(1);
        pusher.hold = new CountDownLatch(1);
        RegionSeedSpool spool = new RegionSeedSpool(() -> "w", () -> dir, pusher, 60_000L);

        // Fill the pipe with other regions so this one's offer is rejected outright.
        for (int i = 1; i < RegionSeedSpool.QUEUE_DEPTH + 10; i++) {
            spool.offer(snapshot(new RegionId(DimensionKey.overworld(), i, 7), 1));
        }
        boolean droppedRegion = false;
        for (int attempt = 0; attempt < 40 && !droppedRegion; attempt++) {
            droppedRegion = !spool.offer(snapshot(REGION, 1));
        }
        assertThat(droppedRegion).isTrue();

        // Let the pipe drain, then the SAME region must be eligible again immediately: a drop is
        // not a push, so it must not start a throttle interval.
        pusher.hold.countDown();
        AtomicReference<Boolean> retried = new AtomicReference<>(false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!retried.get() && System.nanoTime() < deadline) {
            retried.set(spool.offer(snapshot(REGION, 2)));
            if (!retried.get()) {
                Thread.sleep(10);
            }
        }
        assertThat(retried.get()).isTrue();
        spool.close();
    }

    @Test
    @DisplayName("a worker that refuses, and a spool that cannot write, cost nothing but the region")
    void failuresAreContained(@TempDir Path dir) throws Exception {
        RecordingPusher refusing = new RecordingPusher(1);
        refusing.accept = false;
        RegionSeedSpool spool = new RegionSeedSpool(() -> "w", () -> dir, refusing, 0L);
        assertThat(spool.offer(snapshot(REGION, 1))).isTrue();
        assertThat(refusing.arrived.await(10, TimeUnit.SECONDS)).isTrue();
        spool.close();

        // A pusher that throws, and a spool directory that cannot be created, must both be
        // survivable: the offer already returned before either could matter.
        RegionSeedSpool exploding = new RegionSeedSpool(() -> "w",
                () -> dir.resolve("file-in-the-way").resolve("nested"),
                (world, file) -> {
                    throw new IllegalStateException("worker on fire");
                }, 0L);
        java.nio.file.Files.write(dir.resolve("file-in-the-way"), new byte[]{1});
        assertThat(exploding.offer(snapshot(REGION, 1))).isTrue();
        exploding.close();
    }

    @Test
    @DisplayName("nulls are ignored at the call and refused at construction")
    void argumentsAreChecked(@TempDir Path dir) {
        RegionSeedSpool spool =
                new RegionSeedSpool(() -> "w", () -> dir, (world, file) -> true, 0L);
        assertThat(spool.offer(null)).isFalse();
        spool.close();

        assertThatThrownBy(() -> new RegionSeedSpool(null, () -> dir, (w, f) -> true, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegionSeedSpool(() -> "w", null, (w, f) -> true, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegionSeedSpool(() -> "w", () -> dir, null, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
