package dev.nodera.headless;

import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.storage.WorldTombstone;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The deletions this peer has accepted, kept on disk so a restart cannot undo one.
 *
 * <h2>Why a deletion has to be remembered, not just performed</h2>
 *
 * <p>Deleting a world removes it from this node. It does not remove it from the peers that were
 * offline, and those peers keep announcing and gossiping the world until they hear. A node that
 * forgot the deletion would take one of those announces at face value and re-adopt the world — so
 * "delete" would last exactly until the next restart, and the network would heal the damage the
 * owner asked for.
 *
 * <p>The record kept is the tombstone itself, not a flag, because it is the evidence: this node can
 * hand it to whoever is still announcing the world and let them verify it for themselves, which is
 * how a deletion reaches a peer that was never in the session where it was issued.
 *
 * <h2>The 120-day window</h2>
 *
 * <p>Tombstones older than {@link #RETENTION} are dropped on load, matching the window the trackers
 * keep. The honest bound: after that, a peer that has been offline for four months and comes back
 * still holding the world's bytes would find nobody to contradict it. The window is a judgement that
 * four months is longer than any real absence, not a proof that resurrection is impossible.
 *
 * @Thread-context safe from any thread; blocking IO on the caller's thread.
 */
public final class WorldTombstoneStore extends HexKeyedStore {

    private static final String SUFFIX = ".tombstone";

    /** Kept beside the tombstones, in the same window: the owner's later "put it back". */
    private static final String REVIVAL_SUFFIX = ".revival";

    /** How long a deletion is remembered — the same window the trackers cache. */
    public static final Duration RETENTION = Duration.ofDays(120);

    /** @param directory where accepted tombstones live; created on the first save. */
    public WorldTombstoneStore(Path directory) {
        super(directory);
    }

    /**
     * Read every tombstone still inside the retention window.
     *
     * <p>Each is verified again on the way in. A file on our own disk is not more trustworthy than a
     * frame off the wire — if it has been edited, the honest answer is that we no longer hold a
     * deletion for that world, not that we hold an unverifiable one.
     *
     * @param nowEpochMillis the current time, for the retention cut.
     * @return the surviving tombstones.
     */
    public List<WorldTombstone> load(long nowEpochMillis) {
        return loadAll(SUFFIX, WorldTombstone::decode, WorldTombstone::verify, "deletion",
                WorldTombstone::issuedAtEpoch, nowEpochMillis - RETENTION.toMillis());
    }

    /**
     * Persist an accepted tombstone.
     *
     * @param tombstone a tombstone the caller has already verified.
     * @throws IllegalArgumentException if it does not verify — an unverifiable deletion is never
     *                                  written, so nothing this store hands back can be a forgery
     *                                  that survived a restart.
     */
    public void save(WorldTombstone tombstone) {
        Objects.requireNonNull(tombstone, "tombstone");
        if (!tombstone.verify()) {
            throw new IllegalArgumentException("refusing to persist a deletion that does not verify");
        }
        CanonicalWriter w = new CanonicalWriter(256);
        tombstone.encode(w);
        LocalFiles.writeAtomically(fileFor(tombstone.worldIdHex(), SUFFIX), w.toByteArray());
    }

    /**
     * Persist an accepted revival, and drop the deletion it undoes.
     *
     * <p>Both halves matter across a restart. Removing the tombstone file is what lets the owner
     * host the world again after a reboot; keeping the revival is what stops an old deletion,
     * arriving later from a peer that never heard about the restore, from killing it a second time.
     *
     * @param revival a revival the caller has already verified.
     * @throws IllegalArgumentException if it does not verify.
     */
    public void save(dev.nodera.storage.WorldRevival revival) {
        Objects.requireNonNull(revival, "revival");
        if (!revival.verify()) {
            throw new IllegalArgumentException("refusing to persist a restore that does not verify");
        }
        CanonicalWriter w = new CanonicalWriter(256);
        revival.encode(w);
        LocalFiles.writeAtomically(fileFor(revival.worldIdHex(), REVIVAL_SUFFIX), w.toByteArray());
        delete(fileFor(revival.worldIdHex(), SUFFIX));
    }

    /**
     * Read every revival still inside the retention window.
     *
     * @param nowEpochMillis the current time, for the retention cut.
     * @return the surviving revivals, each re-verified on the way in.
     */
    public List<dev.nodera.storage.WorldRevival> loadRevivals(long nowEpochMillis) {
        return loadAll(REVIVAL_SUFFIX, dev.nodera.storage.WorldRevival::decode,
                dev.nodera.storage.WorldRevival::verify, "restore",
                dev.nodera.storage.WorldRevival::issuedAtEpoch,
                nowEpochMillis - RETENTION.toMillis());
    }
}
