package dev.nodera.peer.validation;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.PlayerView;
import dev.nodera.core.region.RegionClaim;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.region.ViewOwnershipPlanner;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.GenesisManifest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minecraft-free bootstrap planner for the live entity lane (Task 5b seam consumed by Task 12):
 * turns a world seed and the current player views into everything
 * {@code LiveEntityLaneSession.open} needs — the {@link GenesisManifest} every committee member
 * must agree on, the initial {@link RegionSnapshot} each activated region starts from, and the
 * {@link RegionLease}s derived from the decentralized {@link ViewOwnershipPlanner} plan.
 *
 * <p><b>Genesis derivation.</b> Until the live genesis lane (Task 9/30c) extracts and
 * self-certifies a manifest from the real world, the genesis root is a stable domain-separated
 * SHA-256 over {@code (worldSeed, rulesVersion, registryFingerprint)} — every peer that shares the
 * seed and rule set derives the identical manifest with no coordination, mirroring the interim
 * world-id scheme in the mod's host lane. The rules version and registry fingerprint are pinned to
 * {@link FlatWorldRules}, the only rule set the MVP engine validates.
 *
 * <p><b>Initial snapshots.</b> The MVP validated lane runs on the flat-world state model: every
 * region starts as the all-{@link FlatWorldRules#AIR AIR} 8×8-chunk column grid at
 * {@link SnapshotVersion#INITIAL} (items rest on the implicit ground plane at
 * {@code ItemEntityRules.GROUND_Y}; terrain enters the root only when actions mutate it). Replaced
 * by the 5b {@code SnapshotExtractor} when real-terrain extraction lands.
 *
 * <p>Thread-context: stateless; every method is a pure function, safe from any thread.
 */
public final class EntityLaneBootstrap {

    /** Domain separator for the interim genesis root derivation (never reused elsewhere). */
    private static final byte[] GENESIS_DOMAIN =
            "nodera.entity-lane.genesis.v1".getBytes(StandardCharsets.UTF_8);

    private static final int SECTION_COUNT =
            (FlatWorldRules.MAX_Y - FlatWorldRules.MIN_Y + 1) / 16;

    private EntityLaneBootstrap() {
    }

    /**
     * Derive the interim {@link GenesisManifest} for a world. Pure: two peers calling this with the
     * same seed produce byte-identical manifests, so the store-open genesis equality check holds
     * across the committee without any exchange.
     *
     * @param worldSeed the world seed both peers share.
     * @param hashes    the SHA-256 service.
     * @return the manifest pinned to {@link FlatWorldRules}.
     * @Thread-context any thread.
     */
    public static GenesisManifest genesis(long worldSeed, HashService hashes) {
        if (hashes == null) {
            throw new IllegalArgumentException("hashes must not be null");
        }
        CanonicalWriter w = new CanonicalWriter();
        w.writeBytes(GENESIS_DOMAIN);
        w.writeU64(worldSeed);
        w.writeU32(Integer.toUnsignedLong(FlatWorldRules.RULES_VERSION));
        w.writeU64(FlatWorldRules.registryFingerprint());
        return new GenesisManifest(
                worldSeed,
                FlatWorldRules.RULES_VERSION,
                FlatWorldRules.registryFingerprint(),
                StateRoot.of(hashes.sha256(w.toBytes())));
    }

    /**
     * The initial validated-lane state of a region: the all-AIR flat-world snapshot at
     * {@link SnapshotVersion#INITIAL}, tick 0. Byte-stable — every committee member derives the
     * identical snapshot (and therefore the identical base root) for a region with no exchange.
     *
     * @param region the region to seed.
     * @return the region's genesis snapshot.
     * @Thread-context any thread.
     */
    public static RegionSnapshot initialSnapshot(RegionId region) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        int originX = region.originChunkX();
        int originZ = region.originChunkZ();
        List<ChunkColumnState> chunks = new ArrayList<>(
                NoderaConstants.REGION_SIZE_CHUNKS * NoderaConstants.REGION_SIZE_CHUNKS);
        for (int dx = 0; dx < NoderaConstants.REGION_SIZE_CHUNKS; dx++) {
            for (int dz = 0; dz < NoderaConstants.REGION_SIZE_CHUNKS; dz++) {
                chunks.add(new ChunkColumnState(
                        originX + dx, originZ + dz,
                        new int[SECTION_COUNT], FlatWorldRules.MIN_Y, SECTION_COUNT));
            }
        }
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L, chunks);
    }

    /**
     * One region of the decentralized ownership plan, with its first-epoch lease.
     *
     * @param region         the region.
     * @param lease          the epoch-1 lease derived from the {@link RegionClaim} (primary +
     *                       validators in committee order, valid {@code currentTick} →
     *                       {@code currentTick + LEASE_LENGTH_TICKS}).
     * @param locallyPrimary whether the planning node is the lease's primary — the regions the
     *                       local peer activates in its own {@code LiveEntityLaneRuntime}.
     */
    public record PlannedRegion(RegionId region, RegionLease lease, boolean locallyPrimary) {
        public PlannedRegion {
            if (region == null || lease == null || !region.equals(lease.region())) {
                throw new IllegalArgumentException("planned region and lease must agree");
            }
        }
    }

    /**
     * Compute the first-epoch entity-lane plan from the current player views. Deterministic: every
     * peer with the same views and tick derives identical leases (validator order inside
     * {@link RegionLease} is canonically sorted; primaries come from
     * {@link ViewOwnershipPlanner}'s distance ranking).
     *
     * @param views            each peer's current field-of-view disc.
     * @param localNode        the planning peer (marks {@link PlannedRegion#locallyPrimary()}).
     * @param currentTick      the tick the leases become valid at.
     * @param maxCommitteeSize committee cap, e.g. {@link NoderaConstants#QUORUM_MVP_SIZE}.
     * @return the planned regions in deterministic region order.
     * @Thread-context any thread.
     */
    public static List<PlannedRegion> plan(
            Map<NodeId, PlayerView> views, NodeId localNode, long currentTick, int maxCommitteeSize) {
        if (views == null || localNode == null) {
            throw new IllegalArgumentException("views and localNode must not be null");
        }
        Map<RegionId, RegionClaim> claims = ViewOwnershipPlanner.plan(views, maxCommitteeSize);
        List<PlannedRegion> planned = new ArrayList<>(claims.size());
        for (RegionClaim claim : claims.values()) {
            RegionLease lease = new RegionLease(
                    claim.region(), new RegionEpoch(1), claim.primary(), claim.validators(),
                    currentTick, currentTick + NoderaConstants.LEASE_LENGTH_TICKS);
            planned.add(new PlannedRegion(
                    claim.region(), lease, claim.primary().equals(localNode)));
        }
        return planned;
    }
}
