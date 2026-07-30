package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;

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
 * @param batchRoot     canonical hash of the exact {@code ActionBatch} (version 3).
 * @param proposerSig   Ed25519 signature over the signed portion of the proposal.
 * @param bodyVersion   proposal body/signature version.
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
        StateRoot batchRoot,
        Bytes proposerSig,
        int bodyVersion,
        java.util.List<HaloPin> haloPins
) implements NoderaMessage {

    /**
     * One neighbour edge slice this proposal was executed against (engine L-2).
     *
     * <p>The halo is execution input, so a validator that holds one slice more or less than the
     * primary computes a different root and the round dies as a "divergence" that nobody caused.
     * Naming the inputs turns that into a decision: a validator either holds exactly these slices
     * and re-executes on identical state, or it declines the round cleanly.
     *
     * @param source  the neighbour region the slice came from; not null.
     * @param version the source snapshot version the slice was cut at; not null.
     */
    public record HaloPin(RegionId source, SnapshotVersion version) {

        /** Canonical order, so two nodes listing the same pins list them identically. */
        public static final java.util.Comparator<HaloPin> CANONICAL_ORDER =
                java.util.Comparator.comparing((HaloPin p) -> p.source().dimension().toString())
                        .thenComparingInt(p -> p.source().regionX())
                        .thenComparingInt(p -> p.source().regionZ());

        public HaloPin {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(version, "version");
        }

        /** Sort a pin list into {@link #CANONICAL_ORDER} and make it immutable. */
        public static java.util.List<HaloPin> canonical(java.util.List<HaloPin> pins) {
            java.util.List<HaloPin> sorted = new java.util.ArrayList<>(pins);
            sorted.sort(CANONICAL_ORDER);
            return java.util.List.copyOf(sorted);
        }
    }

    /**
     * Version 1 signed root only; version 2 signed delta fields; version 3 adds batchRoot;
     * version 4 names the halo slices the proposal was executed against.
     */
    public static final int PROPOSAL_ENCODING_VERSION = 4;

    /** Version-2 constructor retained for old frames that did not bind the action batch. */
    public RegionProposal(
            RegionId region, RegionEpoch epoch, SnapshotVersion baseVersion,
            long tickFrom, long tickTo, StateRoot prevRoot, StateRoot resultingRoot,
            Bytes encodedDelta, Bytes proposerSig) {
        this(region, epoch, baseVersion, tickFrom, tickTo, prevRoot, resultingRoot,
                encodedDelta, null, proposerSig, 2, null);
    }

    /** Version-3 constructor retained for frames that did not name their halo inputs. */
    public RegionProposal(
            RegionId region, RegionEpoch epoch, SnapshotVersion baseVersion,
            long tickFrom, long tickTo, StateRoot prevRoot, StateRoot resultingRoot,
            Bytes encodedDelta, StateRoot batchRoot, Bytes proposerSig) {
        this(region, epoch, baseVersion, tickFrom, tickTo, prevRoot, resultingRoot,
                encodedDelta, batchRoot, proposerSig, 3, null);
    }

    /** Current full-proposal constructor. */
    public RegionProposal(
            RegionId region, RegionEpoch epoch, SnapshotVersion baseVersion,
            long tickFrom, long tickTo, StateRoot prevRoot, StateRoot resultingRoot,
            Bytes encodedDelta, StateRoot batchRoot, Bytes proposerSig,
            java.util.List<HaloPin> haloPins) {
        this(region, epoch, baseVersion, tickFrom, tickTo, prevRoot, resultingRoot,
                encodedDelta, batchRoot, proposerSig, PROPOSAL_ENCODING_VERSION,
                Objects.requireNonNull(haloPins, "haloPins"));
    }

    /** The four-arg legacy body-version constructor kept for decoders and fixtures. */
    public RegionProposal(
            RegionId region, RegionEpoch epoch, SnapshotVersion baseVersion,
            long tickFrom, long tickTo, StateRoot prevRoot, StateRoot resultingRoot,
            Bytes encodedDelta, StateRoot batchRoot, Bytes proposerSig, int bodyVersion) {
        this(region, epoch, baseVersion, tickFrom, tickTo, prevRoot, resultingRoot,
                encodedDelta, batchRoot, proposerSig, bodyVersion,
                bodyVersion >= 4 ? java.util.List.of() : null);
    }

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
        if (bodyVersion < 1 || bodyVersion > PROPOSAL_ENCODING_VERSION) {
            throw new IllegalArgumentException("unsupported proposal body version " + bodyVersion);
        }
        if (bodyVersion < 3 && batchRoot != null) {
            throw new IllegalArgumentException("legacy proposal cannot carry a batch root");
        }
        if (bodyVersion >= 3 && batchRoot == null) {
            throw new IllegalArgumentException("version 3 proposal requires a batch root");
        }
        if (bodyVersion < 4 && haloPins != null) {
            throw new IllegalArgumentException("legacy proposal cannot name halo pins");
        }
        if (bodyVersion >= 4) {
            if (haloPins == null) {
                throw new IllegalArgumentException("version 4 proposal requires halo pins");
            }
            haloPins = HaloPin.canonical(haloPins);
        }
    }

    /** Canonical message prefix covered by {@link #proposerSig()}. */
    public Bytes signedPortion() {
        if (bodyVersion == 1) {
            return resultingRoot.hash();
        }
        CanonicalWriter writer = new CanonicalWriter();
        writer.writeU16(MessageCodec.TAG_REGION_PROPOSAL).writeU16(bodyVersion);
        region.encode(writer);
        epoch.encode(writer);
        baseVersion.encode(writer);
        writer.writeU64(tickFrom);
        writer.writeU64(tickTo);
        prevRoot.encode(writer);
        resultingRoot.encode(writer);
        writer.writeBytes(encodedDelta);
        if (bodyVersion >= 3) {
            batchRoot.encode(writer);
        }
        if (bodyVersion >= 4) {
            // Signed, not merely carried: the halo pins ARE the execution inputs, so a relay that
            // could rewrite them could make an honest validator refuse an honest round.
            writer.writeList(haloPins, (w, pin) -> {
                pin.source().encode(w);
                pin.version().encode(w);
            });
        }
        return writer.toBytes();
    }
}
