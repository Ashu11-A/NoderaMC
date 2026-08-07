package dev.nodera.shadow;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.testkit.engine.EngineFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterferenceProbeTest {

    @Test
    void identicalSnapshotsShowNoInterference() {
        RegionId region = EngineFixtures.region(0, 0);
        RegionSnapshot expected = EngineFixtures.fullUniformSnapshot(region, 1);
        RegionSnapshot reExtracted = EngineFixtures.fullUniformSnapshot(region, 1);

        InterferenceProbe probe = new InterferenceProbe();
        InterferenceProbe.Report report = probe.probe(expected, reExtracted);

        assertThat(report.interfered()).isFalse();
        assertThat(report.changedSections()).isZero();
        assertThat(probe.checks()).isEqualTo(1);
        assertThat(probe.interferedChecks()).isZero();
    }

    @Test
    void foreignMutationIsDetectedAndCounted() {
        RegionId region = EngineFixtures.region(0, 0);
        RegionSnapshot expected = EngineFixtures.fullUniformSnapshot(region, 1); // all STONE

        // A foreign mutation (random tick / fluid / another mod) painted one section DIRT in one chunk.
        List<ChunkColumnState> cols = new ArrayList<>(expected.chunks());
        ChunkColumnState first = cols.get(0);
        int[] palette = first.paletteStateIdsPerSection();
        palette[3] = 2; // DIRT
        cols.set(0, new ChunkColumnState(first.chunkX(), first.chunkZ(), palette,
                first.minY(), first.sectionCount()));
        RegionSnapshot reExtracted = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L, cols);

        InterferenceProbe probe = new InterferenceProbe();
        InterferenceProbe.Report report = probe.probe(expected, reExtracted);

        assertThat(report.interfered()).isTrue();
        assertThat(report.changedSections()).isEqualTo(1);
        assertThat(probe.interferedChecks()).isEqualTo(1);
        assertThat(probe.changedSectionsTotal()).isEqualTo(1);
        // A whole section repainted is 4096 blocks — the coarse count reads it as "1".
        assertThat(report.changedBlocks()).isEqualTo(ChunkColumnState.SECTION_VOLUME);
    }

    @Test
    void oneBlockChangedInsideASectionIsInvisibleToTheCoarseCountAndExactToTheFineOne() {
        RegionId region = EngineFixtures.region(0, 0);
        RegionSnapshot expected = EngineFixtures.fullUniformSnapshot(region, 1); // all STONE

        // What a live extraction produces once a player mines a single block: the section stops
        // being uniform and arrives as a dense array. The per-section palette entry of a dense
        // section is pinned to 0 by ChunkColumnState, so the coarse comparison sees a difference
        // that tells it nothing about size — this is precisely why the exact count exists.
        List<ChunkColumnState> cols = new ArrayList<>(expected.chunks());
        ChunkColumnState first = cols.get(0);
        int[] blocks = new int[ChunkColumnState.SECTION_VOLUME];
        java.util.Arrays.fill(blocks, 1);
        blocks[17] = 0; // one block mined out
        cols.set(0, new ChunkColumnState(first.chunkX(), first.chunkZ(),
                first.paletteStateIdsPerSection(), first.minY(), first.sectionCount(),
                List.of(new ChunkColumnState.DenseSection(3, blocks))));
        RegionSnapshot reExtracted = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L, cols);

        InterferenceProbe probe = new InterferenceProbe();
        InterferenceProbe.Report report = probe.probe(expected, reExtracted);

        assertThat(report.interfered()).isTrue();
        assertThat(report.changedBlocks()).isEqualTo(1);
        assertThat(probe.changedBlocksTotal()).isEqualTo(1);
    }

    @Test
    void aDenseSectionThatMatchesItsUniformCounterpartIsNotInterference() {
        RegionId region = EngineFixtures.region(0, 0);
        RegionSnapshot expected = EngineFixtures.fullUniformSnapshot(region, 1);

        // A dense section whose 4096 ids are all STONE is re-sparsified by ChunkColumnState, so
        // the two snapshots must compare equal on both counts: the extractor's shape must never
        // decide whether something counts as interference.
        List<ChunkColumnState> cols = new ArrayList<>(expected.chunks());
        ChunkColumnState first = cols.get(0);
        int[] blocks = new int[ChunkColumnState.SECTION_VOLUME];
        java.util.Arrays.fill(blocks, 1);
        cols.set(0, new ChunkColumnState(first.chunkX(), first.chunkZ(),
                first.paletteStateIdsPerSection(), first.minY(), first.sectionCount(),
                List.of(new ChunkColumnState.DenseSection(3, blocks))));
        RegionSnapshot reExtracted = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L, cols);

        InterferenceProbe.Report report = new InterferenceProbe().probe(expected, reExtracted);

        assertThat(report.changedBlocks()).isZero();
        assertThat(report.changedSections()).isZero();
        assertThat(report.interfered()).isFalse();
    }
}
