package dev.nodera.core.region;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * The role a node plays for ONE region replica (Task 6 / Plan §3.3). Distinct from
 * {@link dev.nodera.core.identity.PeerRole} (a network-wide capability): a single
 * {@code REGION_EXECUTOR} peer is the {@code PRIMARY} of some regions and a {@code VALIDATOR}
 * of others.
 *
 * <p>Ordinal order is a FROZEN wire contract: the u8 written by {@link #encode(CanonicalWriter)}
 * is exactly {@link #ordinal()}. Never reorder — append only.
 *
 * <ul>
 *   <li>{@code PRIMARY} — executes the region (applies committed deltas, produces the
 *       pre-commit output that validators check).</li>
 *   <li>{@code VALIDATOR} — verifies the primary's output and co-signs the commit
 *       certificate.</li>
 * </ul>
 *
 * <p>Thread-context: immutable, any thread.
 */
public enum RegionReplicaRole implements Encodable {
    PRIMARY,
    VALIDATOR;

    /**
     * Encode as {@code tag(u16) + version(u16) + ordinal(u8)}.
     *
     * @param writer the canonical sink.
     * @Thread-context any thread; does not retain the writer.
     */
    @Override
    public void encode(CanonicalWriter writer) {
        writer.writeU16(TypeTags.REGION_REPLICA_ROLE).writeU16(ENCODING_VERSION);
        writer.writeU8(ordinal());
    }

    /**
     * Inverse of {@link #encode(CanonicalWriter)}.
     *
     * @param r the canonical source, positioned at the {@code REGION_REPLICA_ROLE} tag.
     * @return the decoded role.
     * @throws IllegalStateException if the tag/version/ordinal is invalid.
     * @Thread-context any thread; one reader per decode call (not thread-safe).
     */
    public static RegionReplicaRole decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.REGION_REPLICA_ROLE) {
            throw new IllegalStateException("expected REGION_REPLICA_ROLE tag, got " + tag);
        }
        int version = r.readU16();
        if (version != ENCODING_VERSION) {
            throw new IllegalStateException("unsupported REGION_REPLICA_ROLE encoding version " + version);
        }
        int ord = r.readU8();
        RegionReplicaRole[] values = values();
        if (ord < 0 || ord >= values.length) {
            throw new IllegalStateException("invalid RegionReplicaRole ordinal " + ord);
        }
        return values[ord];
    }
}
