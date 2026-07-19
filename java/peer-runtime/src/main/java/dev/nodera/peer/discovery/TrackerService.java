package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.protocol.discovery.ManifestSeeders;
import dev.nodera.protocol.discovery.TrackerQuery;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.membership.PeerEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers "who is in this world, who seeds it, and how is it doing?" (Task 20) by joining the
 * {@link PeerDirectory} (liveness) with the {@link ArchiveInventory} (holdings).
 *
 * <p>This is the control plane the multiplayer UI (Task 26) reads to list worlds with their player
 * counts, chunk counts, reliability and health colour.
 *
 * <h2>Health, and why zero seeders is not instantly DEAD</h2>
 *
 * <p>A world with no seeders is {@link WorldHealth#DEGRADED} with a countdown, not
 * {@link WorldHealth#DEAD}. Flipping straight to DEAD would mean a host rebooting their machine
 * watches their world go gray, and — under Task 22's retention rules — eventually get dropped. The
 * DEAD state is therefore gated on the coordinated 24 h countdown having <i>expired</i>, and the
 * tracker exposes the deadline so the UI can show the clock rather than a verdict.
 *
 * <h2>Names are directory metadata, not identity</h2>
 *
 * <p>A world's display name is registered by its host and looked up here by genesis hash.
 * {@code GenesisManifest} stays name-free and frozen: names are mutable, spoofable, and duplicated
 * across networks, so they can decorate a world but never identify one.
 *
 * <p>Thread-context: thread-safe. World metadata lives in a {@link ConcurrentHashMap}; the
 * directory and inventory are independently thread-safe.
 */
public final class TrackerService {

    /**
     * Host-registered metadata about a world.
     *
     * @param worldName                    the display name.
     * @param retentionDeadlineEpochMillis the Task 22 drop deadline, or {@code 0} if no countdown.
     * @Thread-context immutable record, safe for any thread.
     */
    public record WorldInfo(String worldName, long retentionDeadlineEpochMillis) {

        /**
         * Compact constructor.
         *
         * @throws IllegalArgumentException if {@code worldName} is null or the deadline is negative.
         */
        public WorldInfo {
            Objects.requireNonNull(worldName, "worldName");
            if (retentionDeadlineEpochMillis < 0) {
                throw new IllegalArgumentException("retention deadline must be non-negative");
            }
        }

        /**
         * @param name the display name.
         * @return metadata with no retention countdown running.
         * @Thread-context any thread.
         */
        public static WorldInfo named(String name) {
            return new WorldInfo(name, 0L);
        }
    }

    /**
     * How many seeders a world needs before it counts as {@link WorldHealth#HEALTHY}. Defaults to
     * Task 21's current-snapshot replication factor: below it, the world is under-replicated and
     * the UI should say so in red.
     */
    public static final int DEFAULT_HEALTHY_SEEDER_FLOOR = 5;

    private final PeerDirectory directory;
    private final ArchiveInventory inventory;
    private final int healthySeederFloor;
    private final Map<Bytes, WorldInfo> worldInfo = new ConcurrentHashMap<>();

    /**
     * @param directory the peer index.
     * @param inventory the holdings index.
     * @throws IllegalArgumentException if an argument is null.
     * @Thread-context any thread (construction only).
     */
    public TrackerService(PeerDirectory directory, ArchiveInventory inventory) {
        this(directory, inventory, DEFAULT_HEALTHY_SEEDER_FLOOR);
    }

    /**
     * @param directory          the peer index.
     * @param inventory          the holdings index.
     * @param healthySeederFloor seeders required for {@code HEALTHY}; must be positive.
     * @throws IllegalArgumentException if an argument is null or the floor is not positive.
     * @Thread-context any thread (construction only).
     */
    public TrackerService(PeerDirectory directory, ArchiveInventory inventory, int healthySeederFloor) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        if (healthySeederFloor <= 0) {
            throw new IllegalArgumentException("healthySeederFloor must be positive");
        }
        this.healthySeederFloor = healthySeederFloor;
    }

    /**
     * Register (or update) a world's host-supplied metadata.
     *
     * @param genesisHash the world.
     * @param info        its display name and retention state.
     * @throws IllegalArgumentException if an argument is null.
     * @Thread-context any thread.
     */
    public void registerWorld(Bytes genesisHash, WorldInfo info) {
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(info, "info");
        worldInfo.put(genesisHash, info);
    }

    /**
     * @param genesisHash the world.
     * @return its registered metadata, or {@code null} if the host never registered any.
     * @Thread-context any thread.
     */
    public WorldInfo worldInfo(Bytes genesisHash) {
        return worldInfo.get(genesisHash);
    }

    /**
     * Answer a tracker query.
     *
     * @param query     the query.
     * @param nowMillis the evaluation time (liveness and the retention countdown are both relative
     *                  to it; the service never reads a clock itself).
     * @return the response — a world nobody is in still gets an answer, with zero counts and
     *         {@code DEGRADED}/{@code DEAD} health, because "no such world" and "an empty world"
     *         are indistinguishable to a tracker and silence would just make the UI hang.
     * @throws IllegalArgumentException if {@code query} is null.
     * @Thread-context any thread.
     */
    public TrackerResponse answer(TrackerQuery query, long nowMillis) {
        Objects.requireNonNull(query, "query");
        Bytes genesisHash = query.genesisHash();

        List<PeerDirectory.Known> online = directory.online(genesisHash, nowMillis);
        List<PeerEntry> peers = new ArrayList<>(online.size());
        double reliabilitySum = 0;
        for (PeerDirectory.Known k : online) {
            peers.add(k.entry());
            reliabilitySum += k.capabilities().reliability();
        }

        Map<Bytes, List<NodeId>> byManifest = inventory.manifestsOfWorld(genesisHash);
        List<ManifestSeeders> seeders = new ArrayList<>(byManifest.size());
        for (Map.Entry<Bytes, List<NodeId>> e : byManifest.entrySet()) {
            seeders.add(new ManifestSeeders(e.getKey(), e.getValue()));
        }

        int seederCount = inventory.seedersOfWorld(genesisHash).size();
        WorldInfo info = worldInfo.get(genesisHash);
        long deadline = info == null ? 0L : info.retentionDeadlineEpochMillis();

        int reliabilityBps = peers.isEmpty()
                ? 0
                : TrackerResponse.toBasisPoints(reliabilitySum / peers.size());

        return new TrackerResponse(
                genesisHash,
                info == null ? "" : info.worldName(),
                peers,
                seeders,
                peers.size(),
                inventory.storedPieces(genesisHash),
                reliabilityBps,
                healthOf(seederCount, deadline, nowMillis),
                deadline);
    }

    /**
     * Classify a world's health.
     *
     * @param seederCount how many peers hold any of its content.
     * @param retentionDeadlineEpochMillis the drop deadline, or {@code 0} if none.
     * @param nowMillis   the evaluation time.
     * @return the health class.
     * @Thread-context any thread.
     */
    WorldHealth healthOf(int seederCount, long retentionDeadlineEpochMillis, long nowMillis) {
        if (seederCount >= healthySeederFloor) {
            return WorldHealth.HEALTHY;
        }
        // Only an EXPIRED retention countdown makes a world DEAD. Zero seeders inside the window is
        // a host rebooting, not a world dying.
        if (seederCount == 0
                && retentionDeadlineEpochMillis > 0
                && nowMillis >= retentionDeadlineEpochMillis) {
            return WorldHealth.DEAD;
        }
        return WorldHealth.DEGRADED;
    }

    /** @return the backing peer directory. */
    public PeerDirectory directory() {
        return directory;
    }

    /** @return the backing archive inventory. */
    public ArchiveInventory inventory() {
        return inventory;
    }
}
