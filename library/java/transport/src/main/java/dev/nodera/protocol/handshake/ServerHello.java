package dev.nodera.protocol.handshake;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;
import java.util.UUID;

/**
 * Server's reply to a connecting client in the configuration-phase handshake (Task 4).
 *
 * <p>Carries the network's stable identity UUID, the current server tick (so the client can
 * align its tick window), the region geometry / quorum parameters the network is running, and
 * a fresh random challenge the client must sign in its {@link ChallengeResponse}.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param networkId          stable per-network identifier (persisted in SavedData later).
 * @param currentTick        server tick at send time.
 * @param regionSizeChunks   chunks per region edge (mirrors {@code NoderaConstants.REGION_SIZE_CHUNKS}).
 * @param requiredValidators committee quorum size the network requires for commit.
 * @param challenge          fresh random nonce the client signs to prove key possession.
 */
public record ServerHello(
        UUID networkId,
        long currentTick,
        int regionSizeChunks,
        int requiredValidators,
        Bytes challenge
) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code networkId} or {@code challenge} is null.
     */
    public ServerHello {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(challenge, "challenge");
    }
}
