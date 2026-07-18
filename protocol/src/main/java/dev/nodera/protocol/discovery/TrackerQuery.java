package dev.nodera.protocol.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * "Who is in the world identified by {@code genesisHash}, and who seeds it?" (Task 20).
 *
 * <p>A world is keyed by its <b>genesis hash</b> rather than a name, because a name is mutable
 * directory metadata that a host can change or spoof, while the genesis hash is the world's
 * cryptographic identity — two peers that agree on it are provably talking about the same world.
 * {@code GenesisManifest} stays name-free and frozen; the display name is looked up separately.
 *
 * <p>Answered with a {@link TrackerResponse} by any peer running the tracker role.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param genesisHash the queried world's genesis hash.
 */
public record TrackerQuery(Bytes genesisHash) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code genesisHash} is null or empty.
     */
    public TrackerQuery {
        Objects.requireNonNull(genesisHash, "genesisHash");
        if (genesisHash.isEmpty()) {
            throw new IllegalArgumentException("genesisHash must not be empty");
        }
    }

    @Override
    public String toString() {
        return "TrackerQuery[" + genesisHash.toShortHex(6) + "]";
    }
}
