package dev.nodera.protocol.content;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * "Send me the piece manifests you hold for this world" (the world-continuity lane).
 *
 * <p>The tracker answers <i>who</i> seeds a world ({@code ManifestSeeders}), but the manifest
 * bytes themselves — the hash list a fetch verifies against — live only on peers. A joiner that
 * learned a seeder from the tracker sends this query over the {@code PeerTransport} and receives a
 * {@link WorldManifestAnswer} carrying the seeder's newest manifests for the world. Everything in
 * the answer is verified by the receiver (manifest roots recompute on decode; piece hashes verify
 * on fetch), so the seeder is never trusted, only consulted.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param worldId the world's id (the genesis hash the tracker lists it under).
 */
public record WorldManifestQuery(Bytes worldId) implements NoderaMessage {

    public WorldManifestQuery {
        Objects.requireNonNull(worldId, "worldId");
    }

    @Override
    public String toString() {
        return "WorldManifestQuery[" + worldId.toShortHex(6) + "]";
    }
}
