package dev.nodera.storage;

import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.storage.io.AtomicFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The shared storage support types: EventChainGuard, RegionOrder, AtomicFileWriter. */
class StorageSupportTest {

    // --- EventChainGuard ---

    @Test
    void chainGuardAcceptsFirstEventOnEmptyLog() {
        CommittedEventEnvelope first = StoreFixtures.chainedEvent(StoreFixtures.REGION, 0);
        EventChainGuard.checkAppend(first, -1L, null);
    }

    @Test
    void chainGuardAcceptsChainedAppend() {
        CommittedEventEnvelope next = StoreFixtures.chainedEvent(StoreFixtures.REGION, 1);
        EventChainGuard.checkAppend(next, 0L, StoreFixtures.chainRoot(0));
    }

    @Test
    void chainGuardRejectsNullEvent() {
        assertThatThrownBy(() -> EventChainGuard.checkAppend(null, -1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chainGuardRejectsNonMonotonicId() {
        CommittedEventEnvelope skipped = StoreFixtures.chainedEvent(StoreFixtures.REGION, 2);
        assertThatThrownBy(() -> EventChainGuard.checkAppend(skipped, 0L, StoreFixtures.chainRoot(0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-monotonic");
    }

    @Test
    void chainGuardRejectsBrokenRootChain() {
        CommittedEventEnvelope wrongPrev = StoreFixtures.event(
                StoreFixtures.REGION, 1, StoreFixtures.root("not-the-head"), StoreFixtures.chainRoot(1));
        assertThatThrownBy(() -> EventChainGuard.checkAppend(wrongPrev, 0L, StoreFixtures.chainRoot(0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken event chain");
    }

    // --- RegionOrder ---

    @Test
    void regionOrderSortsByDimensionThenXThenZ() {
        RegionId aOverworld = new RegionId(DimensionKey.overworld(), 0, 0);
        RegionId bOverworldX = new RegionId(DimensionKey.overworld(), 1, -5);
        RegionId cOverworldZ = new RegionId(DimensionKey.overworld(), 1, 3);
        List<RegionId> sorted = Stream.of(cOverworldZ, aOverworldX(), bOverworldX, aOverworld)
                .sorted(RegionOrder.BY_DIMENSION_XZ)
                .toList();
        assertThat(sorted).containsExactly(aOverworld, aOverworldX(), bOverworldX, cOverworldZ);
    }

    private static RegionId aOverworldX() {
        return new RegionId(DimensionKey.overworld(), 0, 7);
    }

    // --- AtomicFileWriter ---

    @Test
    void atomicWriteCreatesParentsAndReplaces(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("nested/deeper/value.bin");
        AtomicFileWriter.write(target, new byte[] {1, 2, 3});
        assertThat(Files.readAllBytes(target)).containsExactly(1, 2, 3);

        AtomicFileWriter.write(target, new byte[] {9});
        assertThat(Files.readAllBytes(target)).containsExactly(9);
        try (Stream<Path> files = Files.list(target.getParent())) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".tmp"))).isEmpty();
        }
    }

    @Test
    void atomicWriteRejectsNulls(@TempDir Path dir) {
        assertThatThrownBy(() -> AtomicFileWriter.write(null, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AtomicFileWriter.write(dir.resolve("x"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
