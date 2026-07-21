package dev.nodera.core.region;

import dev.nodera.core.identity.NodeId;

import java.util.List;

/**
 * The decentralized ownership of one active region, derived purely from which players can see it.
 *
 * <p>{@code primary} is the peer whose player is closest to the region (they run the authoritative
 * simulation for it); {@code validators} are the other peers whose view discs also cover the region
 * (they re-execute and vote — this is the committee, formed naturally where fields of view overlap).
 * {@code coverCount} is how many players see the region in total (may exceed {@code 1 + validators}
 * when more players overlap than the committee cap admits).
 *
 * <p>The {@code (primary, validators)} pair maps 1:1 onto the existing coordinator {@code Committee},
 * so this player-anchored plan drives the unchanged lease/epoch/quorum machinery (Plan §3.3–3.4).
 *
 * <p>Thread-context: immutable, safe for any thread.
 */
public record RegionClaim(RegionId region, NodeId primary, List<NodeId> validators, int coverCount) {

    public RegionClaim {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (primary == null) {
            throw new IllegalArgumentException("primary must not be null");
        }
        validators = List.copyOf(validators);
        if (coverCount < 1) {
            throw new IllegalArgumentException("coverCount must be >= 1, got " + coverCount);
        }
    }

    /** @return the full committee membership (primary first, then validators) for this region. */
    public List<NodeId> committee() {
        List<NodeId> all = new java.util.ArrayList<>(validators.size() + 1);
        all.add(primary);
        all.addAll(validators);
        return List.copyOf(all);
    }

    /** @return {@code true} when only one player sees this region (solo-owned, no cross-validation). */
    public boolean isSoloOwned() {
        return coverCount == 1;
    }
}
