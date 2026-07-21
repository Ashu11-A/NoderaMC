package dev.nodera.shadow;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterferenceProbeTest {

    @Test
    void identicalSnapshotsShowNoInterference() {
        RegionId region = Fixtures.region(0, 0);
        RegionSnapshot expected = Fixtures.fullUniformSnapshot(region, 1);
        RegionSnapshot reExtracted = Fixtures.fullUniformSnapshot(region, 1);

        InterferenceProbe probe = new InterferenceProbe();
        InterferenceProbe.Report report = probe.probe(expected, reExtracted);

        assertThat(report.interfered()).isFalse();
        assertThat(report.changedSections()).isZero();
        assertThat(probe.checks()).isEqualTo(1);
        assertThat(probe.interferedChecks()).isZero();
    }

    @Test
    void foreignMutationIsDetectedAndCounted() {
        RegionId region = Fixtures.region(0, 0);
        RegionSnapshot expected = Fixtures.fullUniformSnapshot(region, 1); // all STONE

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
    }
}
