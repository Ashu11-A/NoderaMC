package dev.nodera.coordinator;

import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionPlacementPolicy;

/**
 * Deterministic weighted rendezvous placement (Plan §3.5, Task 6). The score packs a capability
 * tier into the high bits and a {@link StableHash} of {@code (node, region)} into the low bits, so:
 *
 * <ul>
 *   <li>a higher-capability / more-reliable node always outranks a lower one (tier dominates);</li>
 *   <li>within a tier, placement is spread pseudo-randomly but <b>reproducibly</b> — every peer that
 *       scores the same candidate set derives the identical ordering on any JVM.</li>
 * </ul>
 *
 * <p>No floating-point maths enters the score: the reliability EMA is quantised to a 4-bit band
 * before mixing, so there is no place for a rounding difference to change placement between peers.
 *
 * @Thread-context pure and deterministic; safe from any thread.
 */
public final class RendezvousPlacementPolicy implements RegionPlacementPolicy {

    @Override
    public boolean eligible(NodeCapabilities caps) {
        return caps.acceptsWorker() && caps.maxPrimaryRegions() > 0;
    }

    @Override
    public long score(NodeId node, RegionId region, NodeCapabilities caps, double reliability) {
        long nodeHash = StableHash.of(node.value());
        long dimHash = StableHash.of(region.dimension().toString());
        long regionHash = StableHash.of(dimHash,
                StableHash.of((long) region.regionX(), (long) region.regionZ()));
        long spread = StableHash.of(nodeHash, regionHash);

        int tier = tier(caps, reliability); // 0..255
        // tier in the top 8 bits, hash in the low 48 → tier dominates, hash spreads within a tier.
        return ((long) tier << 48) | (spread >>> 16 & 0x0000_FFFF_FFFF_FFFFL);
    }

    /** Capability tier 0..255: cores (clamped to 15) in the high nibble, reliability band in the low. */
    private static int tier(NodeCapabilities caps, double reliability) {
        int coresComponent = Math.min(15, Math.max(0, caps.logicalCores()));
        double clamped = reliability < 0.0 ? 0.0 : Math.min(1.0, reliability);
        int reliabilityBand = (int) Math.floor(clamped * 15.0);
        reliabilityBand = Math.max(0, Math.min(15, reliabilityBand));
        return (coresComponent << 4) | reliabilityBand;
    }
}
