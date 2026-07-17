package dev.nodera.core.identity;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * Capability-declared role a peer performs in the network (Task 9 peer-runtime). A single
 * {@code PeerRuntime} may declare several roles; the dedicated full archival peer carries
 * virtually all of them, a client peer carries a subset.
 *
 * <p>Ordinal order is a FROZEN wire contract: the u8 written by {@link #encode(CanonicalWriter)}
 * is exactly {@link #ordinal()}. Never reorder or insert — append only.
 *
 * <ul>
 *   <li>{@code BOOTSTRAP} — serves the {@code BootstrapResponse}; entry point for new peers
 *       (the full archival peer's primary civic duty).</li>
 *   <li>{@code RELAY} — forwards peer-to-peer traffic; keeps the overlay connected.</li>
 *   <li>{@code SESSION_GATEWAY} — terminates a player's session and owns the gateway lane
 *       for their region crossings.</li>
 *   <li>{@code REGION_EXECUTOR} — runs the {@code primary} replica of one or more regions
 *       (applies committed deltas to the local view).</li>
 *   <li>{@code REGION_VALIDATOR} — runs a {@code validator} replica: verifies the primary's
 *       output and co-signs commit certificates.</li>
 *   <li>{@code PARTIAL_ARCHIVE} — stores a bounded subset of world history per an archival
 *       placement policy (typical client-peer role).</li>
 *   <li>{@code FULL_ARCHIVE} — stores every certified event and checkpoint; seeds history
 *       to any returning or new peer (the dedicated server's archival role).</li>
 *   <li>{@code WORLD_SEEDER} — serves initial-world content blobs during bootstrap.</li>
 * </ul>
 *
 * <p>Thread-context: immutable, any thread.
 */
public enum PeerRole implements Encodable {
    BOOTSTRAP,
    RELAY,
    SESSION_GATEWAY,
    REGION_EXECUTOR,
    REGION_VALIDATOR,
    PARTIAL_ARCHIVE,
    FULL_ARCHIVE,
    WORLD_SEEDER;

    /**
     * Encode as {@code tag(u16) + version(u16) + ordinal(u8)}.
     *
     * @param writer the canonical sink.
     * @Thread-context any thread; does not retain the writer.
     */
    @Override
    public void encode(CanonicalWriter writer) {
        writer.writeU16(TypeTags.PEER_ROLE).writeU16(ENCODING_VERSION);
        writer.writeU8(ordinal());
    }

    /**
     * Inverse of {@link #encode(CanonicalWriter)}.
     *
     * @param r the canonical source, positioned at the {@code PEER_ROLE} tag.
     * @return the decoded role.
     * @throws IllegalStateException if the tag/version/ordinal is invalid.
     * @Thread-context any thread; one reader per decode call (not thread-safe).
     */
    public static PeerRole decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.PEER_ROLE) {
            throw new IllegalStateException("expected PEER_ROLE tag, got " + tag);
        }
        int version = r.readU16();
        if (version != ENCODING_VERSION) {
            throw new IllegalStateException("unsupported PEER_ROLE encoding version " + version);
        }
        int ord = r.readU8();
        PeerRole[] values = values();
        if (ord < 0 || ord >= values.length) {
            throw new IllegalStateException("invalid PeerRole ordinal " + ord);
        }
        return values[ord];
    }
}
