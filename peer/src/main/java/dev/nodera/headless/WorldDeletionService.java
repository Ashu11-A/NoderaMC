package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.peer.control.WorkerEvent;
import dev.nodera.peer.control.WorkerEventBus;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.membership.WorldDeletionGossip;
import dev.nodera.storage.WorldTombstone;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import static dev.nodera.headless.WorldIds.key;
import static dev.nodera.headless.WorldIds.shortId;

/**
 * Forgetting a world, on the owner's signed instruction — and never on anyone else's.
 *
 * <h2>The rule</h2>
 *
 * <p>Nothing here trusts a sender. A {@link WorldTombstone} carries its own ownership claim and is
 * signed by both the world's key and its owner's node key, so this service's entire decision is
 * "does the record verify". A record that does not is dropped and <b>not relayed</b> — a peer that
 * forwarded requests it could not itself verify would turn the flood into an amplifier for
 * whatever an attacker managed to inject.
 *
 * <h2>Deleted is a state, not an event</h2>
 *
 * <p>The tombstone is kept after it is applied. Announces and gossip about a deleted world keep
 * arriving from peers that have not heard yet, and a node that only *processed* the deletion would
 * cheerfully re-adopt the world from the next one. So the record is the answer to "should I hold
 * this world" for as long as this node keeps it, and re-adding is refused by pointing at it.
 *
 * @Thread-context {@link #onMessage} runs on the runtime's state thread and must not block —
 *                 verification is local and sends are single frames. Everything else is safe from
 *                 any thread.
 */
public final class WorldDeletionService {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    private final NodeId self;
    /** The one flood loop this package shares; see {@link SignedGossipRelay}. */
    private final SignedGossipRelay mesh;
    private final WorldHostingService hosting;
    private final WorldRegistryStore registry; // nullable
    private final WorldArchiveService archive; // nullable
    private final dev.nodera.peer.tunnel.TunnelService tunnel; // nullable
    private volatile WorkerEventBus events; // nullable
    private volatile WorldTombstoneStore store; // nullable
    private volatile dev.nodera.peer.discovery.TrackerClient trackers; // nullable

    /** worldIdHex → the tombstone this node accepted for it. */
    private final Map<String, WorldTombstone> deleted = new ConcurrentHashMap<>();

    /**
     * worldIdHex → the owner's later "put it back", for worlds that were deleted and restored.
     *
     * <p>Kept for the same reason the tombstones are: a deletion that arrives after the restore —
     * from a peer that never heard about it, or replayed deliberately — must not win. Without this
     * record a restored world would be re-deleted by the next stale announce of the old tombstone,
     * repeatedly, and the owner would have no way to make the restore stick.
     */
    private final Map<String, dev.nodera.storage.WorldRevival> revived = new ConcurrentHashMap<>();

    /**
     * @param self      this node's id (never gossiped to).
     * @param transport the peer transport a verified deletion is relayed over.
     * @param members   the session members to relay to.
     * @param hosting   the announce lane, so a deleted world stops being advertised.
     * @param registry  the durable world list, so the deletion survives a restart; nullable.
     * @param archive   the content plane, so the bytes go too; nullable.
     * @param tunnel    the connection lane, so live sessions for it end; nullable.
     * @param events    the announcement bus, so local clients hear about it; nullable.
     */
    public WorldDeletionService(NodeId self, PeerTransport transport,
                                Supplier<List<PeerEntry>> members, WorldHostingService hosting,
                                WorldRegistryStore registry, WorldArchiveService archive,
                                dev.nodera.peer.tunnel.TunnelService tunnel, WorkerEventBus events) {
        this.self = Objects.requireNonNull(self, "self");
        this.mesh = new SignedGossipRelay(self, transport, members);
        this.hosting = Objects.requireNonNull(hosting, "hosting");
        this.registry = registry;
        this.archive = archive;
        this.tunnel = tunnel;
        this.events = events;
    }

    /**
     * Bind the tracker client a deletion is published through, and take its notices.
     *
     * <p>Both directions matter and they are different problems. Publishing reaches the peers that
     * are not in this node's session — a world is found through a tracker, so a deletion that never
     * reached one would leave the world advertised to everybody who had not joined yet. Receiving
     * covers the opposite case: this node was offline when somebody else's world was deleted, and
     * the first thing it learns is that its announce came back with the owner's record instead of
     * an ack.
     *
     * @param client the shared tracker client; null leaves this lane peer-to-peer only.
     */
    public void attachTrackers(dev.nodera.peer.discovery.TrackerClient client) {
        this.trackers = client;
        if (client != null) {
            client.onDeletionNotice(gossip -> onMessage(null, gossip));
        }
    }

    /**
     * Bind the bus this node announces deletions on.
     *
     * <p>After construction because the bus is built later in the worker's startup than the lanes
     * that feed it; a deletion arriving before it is bound is applied in full and simply not
     * announced to local clients, which is the correct order of precedence.
     */
    public void attachEvents(WorkerEventBus bus) {
        this.events = bus;
    }

    /**
     * Bind the on-disk record, and restore what it already holds.
     *
     * @param store where accepted deletions are kept; null keeps this node memory-only, in which
     *              case a restart forgets every deletion it applied.
     */
    public void attachStore(WorldTombstoneStore store) {
        this.store = store;
        if (store != null) {
            long now = System.currentTimeMillis();
            restore(store.load(now));
            restoreRevivals(store.loadRevivals(now));
        }
    }

    /** @return the tombstone this node holds for a world, if it has been deleted. */
    public Optional<WorldTombstone> tombstone(String worldIdHex) {
        return worldIdHex == null ? Optional.empty()
                : Optional.ofNullable(deleted.get(key(worldIdHex)));
    }

    /**
     * @param worldIdHex the world.
     * @return whether this node has accepted a deletion for it — the check every path that might
     *         re-adopt a world has to make.
     */
    public boolean isDeleted(String worldIdHex) {
        return tombstone(worldIdHex).isPresent();
    }

    /** @return every tombstone this node holds. */
    public List<WorldTombstone> tombstones() {
        return List.copyOf(deleted.values());
    }

    /**
     * Restore tombstones from disk at startup.
     *
     * <p>Without this a restart forgets every deletion, and the first announce for a deleted world
     * would put it back — which would make "delete" mean "until the owner's peers reboot".
     *
     * @param stored previously accepted tombstones.
     */
    public void restore(List<WorldTombstone> stored) {
        for (WorldTombstone tombstone : stored) {
            // Re-verified rather than trusted because it came off our own disk: a corrupted or
            // edited file is exactly as unacceptable as a forged frame.
            if (tombstone != null && tombstone.verify()) {
                deleted.put(key(tombstone.worldIdHex()), tombstone);
            }
        }
        if (!deleted.isEmpty()) {
            LOG.info("Restored {} world deletion(s)", deleted.size());
        }
    }

    /**
     * Restore accepted revivals from disk at startup.
     *
     * <p>The mirror of {@link #restore(List)}: without it a restart re-reads the tombstone that the
     * restore superseded — the deletion file is removed when a restore is saved, but a peer can send
     * the tombstone again at any time — and the world dies a second time.
     *
     * @param stored previously accepted revivals.
     */
    public void restoreRevivals(List<dev.nodera.storage.WorldRevival> stored) {
        for (dev.nodera.storage.WorldRevival revival : stored) {
            if (revival == null || !revival.verify()) {
                continue;
            }
            String id = key(revival.worldIdHex());
            revived.put(id, revival);
            WorldTombstone tombstone = deleted.get(id);
            if (tombstone != null && revival.issuedAtEpoch() > tombstone.issuedAtEpoch()) {
                deleted.remove(id);
            }
        }
        if (!revived.isEmpty()) {
            LOG.info("Restored {} world restore record(s)", revived.size());
        }
    }

    /**
     * What a publish did.
     *
     * @param error         why it was refused, or null on success.
     * @param peersNotified how many peers the request was handed to. Zero is a success, not a
     *                      failure: the world is deleted here and the tombstone is on disk, and a
     *                      peer that learns of it later gets it from whoever is still announcing
     *                      the world. Reported honestly so the UI can say "0 peers reached" rather
     *                      than implying the network has already forgotten it.
     */
    public record Outcome(String error, int peersNotified) {}

    /**
     * Publish this node's own deletion of a world it owns, and apply it here.
     *
     * @param tombstone the signed record.
     * @return the outcome; {@link Outcome#error()} is null on success.
     */
    public Outcome publish(WorldTombstone tombstone) {
        if (tombstone == null || !tombstone.verify()) {
            return new Outcome("the deletion request does not verify", 0);
        }
        if (!tombstone.issuedBy(self)) {
            // A node may relay somebody else's deletion, but it may not originate one. Minting for
            // a world you do not own is not possible anyway (no key); refusing here makes the
            // intent explicit at the call site rather than implicit in a signature failure.
            return new Outcome("this node is not the owner of that world", 0);
        }
        apply(tombstone);
        return new Outcome(null, relay(tombstone, null));
    }

    /**
     * Publish this node's own restoration of a world it owns, and apply it here.
     *
     * <p>The owner deleted it; the owner may put it back. Everything else on this lane treats a
     * tombstone as final, and it stays final to everyone <i>except</i> the key that wrote it — the
     * world id is derived from the save, so without this a deletion made that save permanently
     * unshareable, refused by the owner's own worker.
     *
     * @param revival the signed record.
     * @return the outcome; {@link Outcome#error()} is null on success.
     */
    public Outcome publish(dev.nodera.storage.WorldRevival revival) {
        if (revival == null || !revival.verify()) {
            return new Outcome("the restore request does not verify", 0);
        }
        if (!revival.issuedBy(self)) {
            return new Outcome("this node is not the owner of that world", 0);
        }
        if (!revival.supersedes(deleted.get(key(revival.worldIdHex())))) {
            // Older than the deletion it claims to undo. Not an error worth failing the share for:
            // the caller mints these with the current clock, so this is a clock going backwards,
            // and the honest answer is that the world stays deleted until a later record says so.
            return new Outcome("the restore is older than the deletion it would undo", 0);
        }
        apply(revival);
        return new Outcome(null, relay(revival, null));
    }

    /**
     * Handle one application-lane message; ignores anything that is not a deletion or a revival.
     *
     * @Thread-context runtime state thread.
     */
    public void onMessage(PeerAddress from, NoderaMessage message) {
        if (message instanceof dev.nodera.protocol.membership.WorldRevivalGossip revivalGossip) {
            onRevival(from, revivalGossip);
            return;
        }
        if (!(message instanceof WorldDeletionGossip gossip)) {
            return;
        }
        WorldTombstone tombstone;
        try {
            tombstone = WorldTombstone.decode(new CanonicalReader(gossip.encodedTombstone()));
        } catch (RuntimeException malformed) {
            LOG.debug("discarding malformed deletion from {}: {}", from, malformed.getMessage());
            return;
        }
        // The envelope must agree with the signed record. Honouring the envelope would let a relay
        // point a valid deletion at a different world.
        if (!tombstone.worldId().equals(gossip.worldId())) {
            LOG.warn("Refusing a deletion whose envelope names a different world than its proof");
            return;
        }
        if (!tombstone.verify()) {
            // The whole security boundary, in one branch. Nothing is deleted and nothing is passed
            // on, so a forgery costs the network one frame and stops.
            LOG.warn("Refusing an unverifiable deletion for world {} from {}",
                    shortId(tombstone.worldIdHex()), from);
            return;
        }
        if (deleted.containsKey(key(tombstone.worldIdHex()))) {
            return; // already applied; not re-flooded, so the gossip terminates
        }
        dev.nodera.storage.WorldRevival restore = revived.get(key(tombstone.worldIdHex()));
        if (restore != null && restore.issuedAtEpoch() >= tombstone.issuedAtEpoch()) {
            // The owner deleted this world and then put it back. An older deletion still circulating
            // is not news, and acting on it would undo the restore — on this node and, through the
            // relay below, on every node this one can reach.
            LOG.debug("Ignoring a deletion for world {} that the owner has since undone",
                    shortId(tombstone.worldIdHex()));
            return;
        }
        apply(tombstone);
        relay(tombstone, from == null ? null : from.nodeId());
    }

    /**
     * Do the deletion: stop serving the world, drop its bytes, and remember that we did.
     *
     * <p>Order matters. Withdrawal comes first so nothing new arrives for a world that is going
     * away, and the tombstone is filed last so a crash midway leaves the node having stopped
     * serving rather than having forgotten why.
     */
    private void apply(WorldTombstone tombstone) {
        String worldIdHex = tombstone.worldIdHex();
        if (tunnel != null) {
            tunnel.unpublish(worldIdHex);
        }
        hosting.stop(worldIdHex);
        if (registry != null) {
            registry.remove(worldIdHex);
        }
        if (archive != null) {
            archive.forget(worldIdHex);
        }
        deleted.put(key(worldIdHex), tombstone);
        WorldTombstoneStore disk = store;
        if (disk != null) {
            try {
                disk.save(tombstone);
            } catch (RuntimeException e) {
                // The deletion has already happened in memory and on the announce lanes; failing to
                // record it costs us the memory of WHY at the next restart, not the deletion.
                LOG.warn("Applied a world deletion but could not record it: {}", e.getMessage());
            }
        }
        LOG.info("World {} deleted at its owner's request{}", shortId(worldIdHex),
                tombstone.reason().isEmpty() ? "" : " (" + tombstone.reason() + ")");
        WorkerEventBus bus = events;
        if (bus != null) {
            bus.publish(WorkerEvent.named(WorkerEvent.WORLD_DELETED)
                    .with("world", worldIdHex)
                    .with("reason", tombstone.reason())
                    .build());
        }
    }

    private void onRevival(PeerAddress from,
                           dev.nodera.protocol.membership.WorldRevivalGossip gossip) {
        dev.nodera.storage.WorldRevival revival;
        try {
            revival = dev.nodera.storage.WorldRevival.decode(
                    new CanonicalReader(gossip.encodedRevival()));
        } catch (RuntimeException malformed) {
            LOG.debug("discarding malformed restore from {}: {}", from, malformed.getMessage());
            return;
        }
        if (!revival.worldId().equals(gossip.worldId())) {
            LOG.warn("Refusing a restore whose envelope names a different world than its proof");
            return;
        }
        if (!revival.verify()) {
            LOG.warn("Refusing an unverifiable restore for world {} from {}",
                    shortId(revival.worldIdHex()), from);
            return;
        }
        String id = key(revival.worldIdHex());
        dev.nodera.storage.WorldRevival known = revived.get(id);
        if (known != null && known.issuedAtEpoch() >= revival.issuedAtEpoch()) {
            return; // already have this one or a later one; not re-flooded
        }
        if (!revival.supersedes(deleted.get(id))) {
            // A restore older than the deletion it would undo. Refused rather than applied: this is
            // the replay an attacker would attempt with a record captured before the world was
            // deleted, and what a node must end up holding is the owner's LATEST word.
            LOG.warn("Refusing a restore for world {} that predates its deletion",
                    shortId(revival.worldIdHex()));
            return;
        }
        apply(revival);
        relay(revival, from == null ? null : from.nodeId());
    }

    /**
     * Undo a deletion: forget the tombstone, remember the restore, and let the world be hosted again.
     *
     * <p>Content is not restored here and cannot be — the bytes were dropped when the world was
     * deleted. What comes back is permission: the owner may host it again, and every peer that hears
     * the restore may adopt it again. The bytes return through the ordinary replication lane, from
     * the owner who still has the save on disk.
     */
    private void apply(dev.nodera.storage.WorldRevival revival) {
        String worldIdHex = revival.worldIdHex();
        String id = key(worldIdHex);
        revived.put(id, revival);
        deleted.remove(id);
        WorldTombstoneStore disk = store;
        if (disk != null) {
            try {
                disk.save(revival);
            } catch (RuntimeException e) {
                // In memory the world is restored; failing to write costs us the restore at the next
                // restart, when the tombstone still on disk would delete it all over again.
                LOG.warn("Applied a world restore but could not record it: {}", e.getMessage());
            }
        }
        LOG.info("World {} restored at its owner's request{}", shortId(worldIdHex),
                revival.reason().isEmpty() ? "" : " (" + revival.reason() + ")");
        WorkerEventBus bus = events;
        if (bus != null) {
            bus.publish(WorkerEvent.named(WorkerEvent.WORLD_RESTORED)
                    .with("world", worldIdHex)
                    .with("reason", revival.reason())
                    .build());
        }
    }

    private int relay(dev.nodera.storage.WorldRevival revival, NodeId excluding) {
        CanonicalWriter w = new CanonicalWriter();
        revival.encode(w);
        dev.nodera.protocol.membership.WorldRevivalGossip gossip =
                new dev.nodera.protocol.membership.WorldRevivalGossip(
                        revival.worldId(), w.toBytes());
        byte[] frame = WireCodec.encode(gossip);
        dev.nodera.peer.discovery.TrackerClient discovery = trackers;
        if (discovery != null) {
            try {
                discovery.publishRevival(gossip);
            } catch (RuntimeException e) {
                // A tracker that will not take the restore goes on refusing to list the world; the
                // peers still learn, and the next announce retries.
                LOG.debug("could not publish the restore to trackers: {}", e.getMessage());
            }
        }
        return mesh.flood(frame, excluding, "restore");
    }

    private int relay(WorldTombstone tombstone, NodeId excluding) {
        CanonicalWriter w = new CanonicalWriter();
        tombstone.encode(w);
        WorldDeletionGossip gossip = new WorldDeletionGossip(tombstone.worldId(), w.toBytes());
        byte[] frame = WireCodec.encode(gossip);
        dev.nodera.peer.discovery.TrackerClient discovery = trackers;
        if (discovery != null) {
            try {
                discovery.publishDeletion(gossip);
            } catch (RuntimeException e) {
                // A tracker that will not take the deletion does not stop the peers getting it.
                LOG.debug("could not publish the deletion to trackers: {}", e.getMessage());
            }
        }
        return mesh.flood(frame, excluding, "deletion");
    }


}
