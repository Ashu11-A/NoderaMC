package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link CustodyDigest}: canonical ordering, equal state → equal root, and inclusion proofs. */
class CustodyDigestTest {

    private final HashService hasher = new HashService();

    private static RegionId region(int x, int z) {
        return new RegionId(DimensionKey.overworld(), x, z);
    }

    private static Map<RegionId, SnapshotVersion> world(int count) {
        Map<RegionId, SnapshotVersion> heads = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            heads.put(region(i % 7 - 3, i / 7 - 3), new SnapshotVersion(100 + i));
        }
        return heads;
    }

    @Test
    @DisplayName("two honest nodes with the same state produce the same root, whatever order they enumerated in")
    void orderIndependent() {
        Map<RegionId, SnapshotVersion> heads = world(23);
        List<RegionId> shuffled = new ArrayList<>(heads.keySet());
        Collections.shuffle(shuffled, new Random(4242));
        Map<RegionId, SnapshotVersion> reordered = new LinkedHashMap<>();
        shuffled.forEach(r -> reordered.put(r, heads.get(r)));

        assertThat(CustodyDigest.of(reordered, hasher).root())
                .isEqualTo(CustodyDigest.of(heads, hasher).root());
        assertThat(CustodyDigest.of(heads, hasher).regions())
                .isSortedAccordingTo(dev.nodera.storage.RegionOrder.BY_DIMENSION_XZ);
    }

    @Test
    @DisplayName("one changed head changes the root")
    void oneChangedHeadChangesTheRoot() {
        Map<RegionId, SnapshotVersion> heads = world(23);
        Bytes before = CustodyDigest.of(heads, hasher).root();

        RegionId changed = region(0, -3);
        heads.put(changed, heads.get(changed).next());

        assertThat(CustodyDigest.of(heads, hasher).root()).isNotEqualTo(before);
    }

    @Test
    @DisplayName("a missing region changes the root — the loss cannot hide behind the others")
    void aMissingRegionChangesTheRoot() {
        Map<RegionId, SnapshotVersion> heads = world(23);
        Bytes before = CustodyDigest.of(heads, hasher).root();
        heads.remove(region(-1, -1));

        assertThat(CustodyDigest.of(heads, hasher).root()).isNotEqualTo(before);
    }

    @Test
    @DisplayName("every region's inclusion proof verifies against the root, at every world size")
    void everyProofVerifies() {
        for (int size : new int[] {1, 2, 3, 5, 8, 23, 64, 100}) {
            CustodyDigest digest = CustodyDigest.of(world(size), hasher);
            assertThat(digest.regionCount()).isEqualTo(size);
            for (RegionId region : digest.regions()) {
                CustodyDigest.Proof proof = digest.proofFor(region).orElseThrow();
                assertThat(CustodyDigest.verify(digest.root(), proof, hasher))
                        .as("size %d, %s", size, region)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("a proof quoting the wrong head does not verify")
    void aForgedHeadDoesNotVerify() {
        CustodyDigest digest = CustodyDigest.of(world(23), hasher);
        RegionId region = digest.regions().get(5);
        CustodyDigest.Proof honest = digest.proofFor(region).orElseThrow();
        CustodyDigest.Proof forged =
                new CustodyDigest.Proof(region, honest.head().next(), honest.path());

        assertThat(CustodyDigest.verify(digest.root(), forged, hasher)).isFalse();
    }

    @Test
    @DisplayName("a proof from a different world does not verify against this root")
    void aProofFromAnotherWorldDoesNotVerify() {
        CustodyDigest mine = CustodyDigest.of(world(23), hasher);
        Map<RegionId, SnapshotVersion> other = world(23);
        other.put(region(2, 2), new SnapshotVersion(999));
        CustodyDigest theirs = CustodyDigest.of(other, hasher);
        CustodyDigest.Proof proof = theirs.proofFor(region(2, 2)).orElseThrow();

        assertThat(CustodyDigest.verify(mine.root(), proof, hasher)).isFalse();
    }

    @Test
    @DisplayName("a region the digest does not cover has no proof, and the empty world still has a root")
    void uncoveredAndEmpty() {
        CustodyDigest digest = CustodyDigest.of(world(9), hasher);
        assertThat(digest.proofFor(region(50, 50))).isEmpty();
        assertThat(digest.head(region(50, 50))).isEmpty();

        CustodyDigest empty = CustodyDigest.of(Map.of(), hasher);
        assertThat(empty.regionCount()).isZero();
        assertThat(empty.root().length()).isEqualTo(32);
        assertThat(empty.root()).isNotEqualTo(digest.root());
    }

    @Test
    @DisplayName("the head the digest commits to is readable, and the digest is a value")
    void headsAreReadable() {
        Map<RegionId, SnapshotVersion> heads = world(9);
        CustodyDigest digest = CustodyDigest.of(heads, hasher);
        for (Map.Entry<RegionId, SnapshotVersion> entry : heads.entrySet()) {
            assertThat(digest.head(entry.getKey())).isEqualTo(Optional.of(entry.getValue()));
        }
        assertThat(CustodyDigest.of(heads, hasher).root()).isEqualTo(digest.root());
    }
}
