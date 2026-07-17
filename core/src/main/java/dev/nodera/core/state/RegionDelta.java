package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A delta between two {@link SnapshotVersion}s of one region (Task 2 state/). The
 * {@code blockMutations} list is canonical: it is sorted by position in {@link NBlockPos}'s
 * canonical order {@code (y, z, x)} and stored unmodifiable, so two replicas that apply the same
 * mutations encode identical bytes regardless of arrival order.
 *
 * <p>MVP is block-only. Reserved lists (block entities, entities, inventories, scheduled ticks) are
 * appended by later tasks as additional sorted lists after {@code resultingRoot} is finalised —
 * they MUST NOT be inserted before existing fields, to keep the encoding forward-safe.
 *
 * <p>Wire form: {@code [u16 REGION_DELTA][u16 ENCODING_VERSION][RegionId][SnapshotVersion baseVersion]
     * [SnapshotVersion resultingVersion][list BlockMutation][StateRoot resultingRoot]}.
 *
 * @Thread-context immutable, any thread.
 */
public record RegionDelta(
        RegionId region,
        SnapshotVersion baseVersion,
        SnapshotVersion resultingVersion,
        List<BlockMutation> blockMutations,
        StateRoot resultingRoot
) implements Encodable {

    private static final Comparator<BlockMutation> MUTATION_ORDER =
            Comparator.comparing(BlockMutation::pos);

    /**
     * Compact constructor. Defensive-copies {@code blockMutations} into an unmodifiable list sorted
     * by {@code (y, z, x)} (via {@link NBlockPos#compareTo}) so the encoded form is byte-stable.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public RegionDelta {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (baseVersion == null) {
            throw new IllegalArgumentException("baseVersion must not be null");
        }
        if (resultingVersion == null) {
            throw new IllegalArgumentException("resultingVersion must not be null");
        }
        if (blockMutations == null) {
            throw new IllegalArgumentException("blockMutations must not be null");
        }
        if (resultingRoot == null) {
            throw new IllegalArgumentException("resultingRoot must not be null");
        }
        List<BlockMutation> sorted = new ArrayList<>(blockMutations);
        sorted.sort(MUTATION_ORDER);
        blockMutations = List.copyOf(sorted);
    }

    /** True when the delta carries no block mutations. */
    public boolean isEmpty() {
        return blockMutations.isEmpty();
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.REGION_DELTA).writeU16(ENCODING_VERSION);
        region.encode(w);
        baseVersion.encode(w);
        resultingVersion.encode(w);
        w.writeList(blockMutations, CanonicalWriter::writeEncodable);
        resultingRoot.encode(w);
    }

    /**
     * Full-frame decode. The decoded mutations list is re-canonicalised by the compact constructor.
     *
     * @throws IllegalStateException if the next tag is not {@code REGION_DELTA}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static RegionDelta decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.REGION_DELTA) {
            throw new IllegalStateException("expected REGION_DELTA tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        RegionId region = RegionId.decode(r);
        SnapshotVersion baseVersion = SnapshotVersion.decode(r);
        SnapshotVersion resultingVersion = SnapshotVersion.decode(r);
        List<BlockMutation> mutations = r.readList(BlockMutation::decode);
        StateRoot resultingRoot = StateRoot.decode(r);
        return new RegionDelta(region, baseVersion, resultingVersion, mutations, resultingRoot);
    }
}
