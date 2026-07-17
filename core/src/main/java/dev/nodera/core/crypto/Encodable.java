package dev.nodera.core.crypto;

/**
 * Implemented by every type that is hashed or signed (Task 2 class relationships:
 * {@code ActionEnvelope}, {@code ActionBatch}, {@code RegionSnapshot}, {@code RegionDelta},
 * {@code BlockMutation}, {@code ChunkColumnState}, {@code RegionLease}, {@code RegionCommittee},
 * {@code SignedVote}, {@code QuorumCertificate}, {@code CommittedEventEnvelope}, plus the
 * identity/region primitives).
 *
 * <p><b>Encoding contract</b> (Task 2 implementation details):
 * <ol>
 *   <li>{@code encode} writes ALL semantic fields. A type carrying its own signature
 *       (e.g. {@code ActionEnvelope}, {@code SignedVote}) MUST exclude that signature from its
 *       encoded form; use {@code signedPortion()} to obtain the bytes that are actually signed.
 *   <li>Every implementation begins its body with
 *       {@code w.writeU16(TypeTags.XXX); w.writeU16(ENCODING_VERSION);}
 *       (the typeTag + version frame) so a single stream of {@code Encodable}s is self-describing.
 *   <li>Big-endian fixed-width ints; no varints. Strings = UTF-8 with u32 length. Lists = u32
 *       count + elements. Optionals = u8 presence + payload. No floats in hashed state for MVP.
 * </ol>
 *
 * <p>Implementations must be deterministic and side-effect free. Never read clocks, RNGs, the
 * filesystem, static mutable state, or iterate unordered collections during encoding.
 *
 * <p>Thread-context: any thread; implementations must not retain the {@link CanonicalWriter}.
 */
public interface Encodable {

    /** Encode this value into {@code writer}. */
    void encode(CanonicalWriter writer);

    /** Current canonical encoding version (starts at 1; bump only on a network-breaking change). */
    int ENCODING_VERSION = 1;
}
