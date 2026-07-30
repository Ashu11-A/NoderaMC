package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task-5b extractor digests (L-50): bit-complete section commitment — the property the interim
 * 8-corner sample could not give (interior edits aliased the digest).
 */
final class SnapshotExtractorTest {

    private final SnapshotExtractor extractor = new SnapshotExtractor(new HashService());

    private static int[] section(int fill) {
        int[] ids = new int[SnapshotExtractor.SECTION_VOLUME];
        java.util.Arrays.fill(ids, fill);
        return ids;
    }

    @Test
    void interiorSingleBlockChangeChangesTheDigest() {
        int[] a = section(1);
        int[] b = section(1);
        // The exact aliasing case the corner sample missed: a NON-corner, interior block.
        b[8 * 256 + 8 * 16 + 8] = 2;
        assertThat(extractor.sectionDigest(a)).isNotEqualTo(extractor.sectionDigest(b));
    }

    @Test
    void sameContentSameDigestAcrossInstances() {
        SnapshotExtractor other = new SnapshotExtractor(new HashService());
        assertThat(extractor.sectionDigest(section(7)))
                .isEqualTo(other.sectionDigest(section(7)));
    }

    @Test
    void orderIsPartOfTheCommitment() {
        int[] a = section(0);
        int[] b = section(0);
        a[0] = 5;
        b[SnapshotExtractor.SECTION_VOLUME - 1] = 5;
        assertThat(extractor.sectionDigest(a)).isNotEqualTo(extractor.sectionDigest(b));
    }

    @Test
    void markersAreDistinctFromEachOtherAndFromContent() {
        Bytes empty = extractor.emptySectionMarker();
        Bytes missing = extractor.missingChunkMarker();
        Bytes airContent = extractor.sectionDigest(section(0));
        assertThat(empty).isNotEqualTo(missing);
        assertThat(empty).isNotEqualTo(airContent);
        assertThat(missing).isNotEqualTo(airContent);
    }

    @Test
    void regionDigestCommitsToSectionOrder() {
        Bytes s1 = extractor.sectionDigest(section(1));
        Bytes s2 = extractor.sectionDigest(section(2));
        assertThat(extractor.regionDigest(List.of(s1, s2)))
                .isNotEqualTo(extractor.regionDigest(List.of(s2, s1)));
    }

    @Test
    void regionDigestIsStableForSameInput() {
        Bytes s1 = extractor.sectionDigest(section(1));
        Bytes missing = extractor.missingChunkMarker();
        assertThat(extractor.regionDigest(List.of(s1, missing)))
                .isEqualTo(extractor.regionDigest(List.of(s1, missing)));
    }

    @Test
    void malformedSectionsAreRejected() {
        assertThatThrownBy(() -> extractor.sectionDigest(new int[7]))
                .isInstanceOf(IllegalArgumentException.class);
        int[] negative = section(0);
        negative[0] = -1;
        assertThatThrownBy(() -> extractor.sectionDigest(negative))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> extractor.regionDigest(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
