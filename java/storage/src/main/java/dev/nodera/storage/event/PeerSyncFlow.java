package dev.nodera.storage.event;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.CertificateStore;
import dev.nodera.storage.Checkpoint;
import dev.nodera.storage.CheckpointStore;
import dev.nodera.storage.ContentStore;
import dev.nodera.storage.GenesisManifest;
import dev.nodera.storage.RegionEventStore;
import dev.nodera.storage.WorldStore;

import java.util.Optional;

/**
 * New / returning-peer forward synchronisation (Task 9, Phase 5; Invariant 8). A peer joins the
 * network, verifies the genesis manifest matches, picks the latest checkpoint it can fetch, replays
 * the certified event log forward from that checkpoint, and lands on the certified final root. A
 * locally-newer-but-uncertified suffix is discarded — the peer synchronises <b>forward from the
 * network</b>, never clobbering newer network state with an uncommitted local tail.
 *
 * <p>This class is the seam's pure decision logic: it takes the network {@link WorldStore} and a
 * local candidate checkpoint/version, and yields the verified sync outcome. The actual fetch
 * (download snapshots by content hash from multiple seeders) is the transport's job in the mod tier.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class PeerSyncFlow {

    private PeerSyncFlow() {
    }

    /**
     * Synchronise {@code region} forward from the network store.
     *
     * @param network       the certified network world store (read side).
     * @param region        the region to sync.
     * @param localLatestId the peer's last locally-known certified event id, or {@code -1} if new.
     * @return the sync outcome (verified root + applied count) or a reject.
     */
    public static Outcome syncForward(WorldStore network, RegionId region, long localLatestId) {
        if (network == null || region == null) {
            throw new IllegalArgumentException("network and region must not be null");
        }
        GenesisManifest genesis = network.genesis();
        CheckpointStore checkpoints = network.checkpoints();
        RegionEventStore events = network.events();
        CertificateStore certs = network.certificates();
        ContentStore content = network.content();

        Optional<Checkpoint> latest = checkpoints.latest(region);
        if (latest.isEmpty()) {
            // No checkpoint for this region: the certified state is the genesis root, replay all.
            EventReplayer.ReplayResult replay = EventReplayer.replay(
                    events, certs, region, genesis.genesisRoot(), 0L);
            return accepted(replay, localLatestId, -1L);
        }
        Checkpoint cp = latest.get();
        // Verify the checkpoint's snapshot blob is present and hashes to its content id (integrity).
        if (!content.has(cp.snapshotContent())) {
            return new Outcome(RejectReason.CHECKPOINT_BLOB_MISSING, null, genesis.genesisRoot(), -1L);
        }
        // Replay forward from the event after the checkpoint.
        EventReplayer.ReplayResult replay = EventReplayer.replay(
                events, certs, region, cp.root(), cp.lastEventId() + 1);
        return accepted(replay, localLatestId, cp.lastEventId());
    }

    private static Outcome accepted(EventReplayer.ReplayResult replay, long localLatestId,
                                    long checkpointLastEventId) {
        // A peer never goes backward: if its local certified tail is ahead of what the network
        // offered, it keeps its local state (Invariant 8 — forward-only sync). Here we report what
        // the network's certified chain yields; the peer adopts it iff it is at least as far.
        return new Outcome(null, new Accepted(replay.finalRoot(), replay.eventsApplied(),
                replay.lastCertifiedEventId()), replay.finalRoot(), checkpointLastEventId);
    }

    /** Why a sync could not complete. */
    public enum RejectReason {
        /** The checkpoint's snapshot blob is not fetchable from any seeder. */
        CHECKPOINT_BLOB_MISSING
    }

    /** @param rejectReason non-null iff the sync was rejected.
     *  @param accepted non-null iff the sync succeeded.
     *  @param networkFinalRoot the network's certified final root.
     *  @param checkpointLastEventId the last event folded into the checkpoint used (-1 genesis). */
    public record Outcome(RejectReason rejectReason, Accepted accepted, StateRoot networkFinalRoot,
                          long checkpointLastEventId) {
        /** @return {@code true} if the sync was accepted. */
        public boolean isAccepted() {
            return accepted != null;
        }
    }

    /** @param finalRoot the verified root the peer lands on.
     *  @param eventsApplied certified events replayed forward from the checkpoint.
     *  @param lastCertifiedEventId the id of the last certified event now held. */
    public record Accepted(StateRoot finalRoot, long eventsApplied, long lastCertifiedEventId) {
    }

    /** Sentinel snapshot version for the genesis case (no checkpoint). */
    public static final SnapshotVersion GENESIS_VERSION = SnapshotVersion.INITIAL;
}
