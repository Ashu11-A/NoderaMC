package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * Primary→validator proposal of a region commit (Task 4).
 *
 * <p>Carries the target {@code region}/{@code epoch}, the {@code baseVersion} the proposal
 * builds on, the tick window it covers, the previous and resulting {@link StateRoot}s, the
 * {@code encodedDelta} (a canonical {@code RegionDelta} byte blob), and the primary's
 * {@code proposerSig} (Ed25519 over the canonical signed portion). Validators verify the
 * delta produces {@code resultingRoot} before voting in a {@link ValidationVote}.
 *
 * <p>The {@code encodedDelta} and {@code proposerSig} are opaque {@link Bytes}; their internal
 * framing is owned by core's {@code RegionDelta} type and the signing path respectively.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param region        target region.
 * @param epoch         target region epoch.
 * @param baseVersion   snapshot version the proposal builds on.
 * @param tickFrom      inclusive start tick of the proposal window.
 * @param tickTo        inclusive end tick of the proposal window.
 * @param prevRoot      state root at {@code baseVersion}.
 * @param resultingRoot state root claimed for the post-commit version.
 * @param encodedDelta  canonical bytes of the {@code RegionDelta} to apply.
 * @param proposerSig   Ed25519 signature over the signed portion of the proposal.
 */
public record RegionProposal(
        RegionId region,
        RegionEpoch epoch,
        SnapshotVersion baseVersion,
        long tickFrom,
        long tickTo,
        StateRoot prevRoot,
        StateRoot resultingRoot,
        Bytes encodedDelta,
        Bytes proposerSig
) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public RegionProposal {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(baseVersion, "baseVersion");
        Objects.requireNonNull(prevRoot, "prevRoot");
        Objects.requireNonNull(resultingRoot, "resultingRoot");
        Objects.requireNonNull(encodedDelta, "encodedDelta");
        Objects.requireNonNull(proposerSig, "proposerSig");
    }
}
