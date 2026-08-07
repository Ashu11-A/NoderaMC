package dev.nodera.headless;

import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The attribution rule between a commit and a world (worker L-41).
 *
 * <p>A region snapshot carries a region id, which is a coordinate; a seeded manifest is filed under
 * a world id, which is an identity. Nothing in the snapshot bridges the two, so the only honest
 * answer when this node hosts several worlds is to seed nothing — a snapshot filed under the wrong
 * world would be advertised to peers fetching a different world entirely.
 */
final class CommittedRegionSeederTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final StateRoot ROOT = new StateRoot(new HashService().sha256("root".getBytes()));

    private static RegionSnapshot snapshot() {
        List<ChunkColumnState> chunks = new ArrayList<>();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                chunks.add(new ChunkColumnState(dx, dz, new int[24], -64, 24));
            }
        }
        return new RegionSnapshot(REGION, SnapshotVersion.INITIAL, 0, chunks);
    }

    /** Records (worldId, region) pairs the seeder decided to seed. */
    private static final class Seeded {
        private final List<String> worlds = new ArrayList<>();

        void accept(String worldId, RegionSnapshot snapshot) {
            worlds.add(worldId);
        }
    }

    @Test
    @DisplayName("a commit is filed under the one world this node hosts")
    void oneHostedWorldIsUnambiguous() {
        Seeded seeded = new Seeded();
        CommittedRegionSeeder seeder =
                new CommittedRegionSeeder(() -> List.of("abc123"), seeded::accept);

        seeder.accept(snapshot(), ROOT);

        assertThat(seeded.worlds).containsExactly("abc123");
    }

    @Test
    @DisplayName("hosting nothing seeds nothing — there is no world to file it under")
    void noHostedWorldSeedsNothing() {
        Seeded seeded = new Seeded();
        new CommittedRegionSeeder(List::of, seeded::accept).accept(snapshot(), ROOT);
        assertThat(seeded.worlds).isEmpty();
    }

    @Test
    @DisplayName("hosting several worlds seeds nothing rather than guessing")
    void ambiguousAttributionIsRefused() {
        Seeded seeded = new Seeded();
        CommittedRegionSeeder seeder =
                new CommittedRegionSeeder(() -> List.of("aaa", "bbb"), seeded::accept);

        seeder.accept(snapshot(), ROOT);

        // Seeding under a guess would advertise this region to peers fetching the OTHER world.
        assertThat(seeded.worlds).isEmpty();
    }

    @Test
    @DisplayName("the hosted list is read per commit, so hosting a world later starts seeding")
    void hostedWorldsAreResolvedFreshly() {
        Seeded seeded = new Seeded();
        AtomicReference<Collection<String>> hosted = new AtomicReference<>(List.of());
        CommittedRegionSeeder seeder = new CommittedRegionSeeder(hosted::get, seeded::accept);

        seeder.accept(snapshot(), ROOT);
        assertThat(seeded.worlds).isEmpty();

        hosted.set(List.of("later"));
        seeder.accept(snapshot(), ROOT);

        assertThat(seeded.worlds).containsExactly("later");
    }

    @Test
    @DisplayName("a seeding fault costs availability, never the commit that produced it")
    void aFailedSeedIsContained() {
        // `doesNotThrowAnyException` alone would also pass if the seeder never called the sink —
        // which is the opposite behaviour, and the one the "seed nothing when ambiguous" rule above
        // produces. So the throwing sink counts its calls, and the test asserts the fault was
        // actually raised before asserting that it was contained.
        AtomicInteger attempts = new AtomicInteger();
        CommittedRegionSeeder seeder = new CommittedRegionSeeder(() -> List.of("w"),
                (world, snapshot) -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("content store on fire");
                });

        assertThatCode(() -> seeder.accept(snapshot(), ROOT)).doesNotThrowAnyException();
        assertThat(attempts)
                .as("the seed was attempted — a contained fault has to have happened first")
                .hasValue(1);

        // And the commit lane keeps working afterwards: a failure is not a latch.
        seeder.accept(snapshot(), ROOT);
        assertThat(attempts).hasValue(2);
    }

    @Test
    @DisplayName("a null snapshot is ignored and null constructor arguments are refused")
    void argumentsAreChecked() {
        Seeded seeded = new Seeded();
        assertThatThrownBy(() -> new CommittedRegionSeeder(null, seeded::accept))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommittedRegionSeeder(List::of, null))
                .isInstanceOf(IllegalArgumentException.class);

        new CommittedRegionSeeder(() -> List.of("w"), seeded::accept).accept(null, ROOT);
        assertThat(seeded.worlds).isEmpty();
    }
}
