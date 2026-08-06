package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.protocol.discovery.AnnounceEvent;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.discovery.TrackerAnnounce;
import dev.nodera.protocol.discovery.TrackerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Being findable while holding no world.
 *
 * <h2>The hole this fills</h2>
 *
 * <p>Peers find each other by querying a world they have both announced into. That works for a node
 * that hosts something and is silently useless for one that does not: it announces nothing, appears
 * in no answer, and — because the tracker deliberately has no full-scrape endpoint — cannot ask what
 * worlds exist either. It is reachable, healthy, and invisible to everyone.
 *
 * <p>A phone is exactly that node. Observed on a real handset against the project's own tracker: the
 * device reported the tracker <em>up at 67 ms</em>, and the world its three desktop peers shared
 * reported {@code peers 3}, none of them the phone. Its only conversation with the tracker was the
 * reachability probe.
 *
 * <p>The fix is a namespace every world-less peer announces into, so "I am here and I hold nothing"
 * is a thing that can be said at all. The Rust companion has had this since mobile support landed;
 * the Java worker never learned it, and on Android it is the Java worker that runs. That mismatch —
 * two peer implementations, one of which knows how to be present — is the whole bug.
 *
 * <h2>The bytes are a cross-language constant</h2>
 *
 * <p>{@link #WORLD_ID} must equal {@code nodera_app::peer::tracker::COMMONS_WORLD} byte for byte, or
 * the two implementations announce into two different namespaces and neither can see the other while
 * both look correct. {@code CommonsPresenceTest} pins the bytes; the Rust side pins its own.
 *
 * <p>Thread-context: {@link #round} is called from a scheduler thread and does network I/O.
 */
public final class CommonsPresence {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaCommons");

    /**
     * The namespace every world-less peer announces into.
     *
     * <p>32 bytes: the ASCII label, zero-padded. It is not a genesis hash and nothing verifies it as
     * one — it is a well-known name in the same slot, which is what makes it work without a wire
     * change.
     */
    public static final Bytes WORLD_ID = commonsWorldId();

    /** The name the tracker shows for it, so an operator reading a listing knows what it is. */
    public static final String WORLD_NAME = "Nodera commons";

    private static Bytes commonsWorldId() {
        byte[] id = new byte[32];
        byte[] label = "nodera:mobile-commons:v1".getBytes(StandardCharsets.US_ASCII);
        if (label.length > id.length) {
            throw new IllegalStateException("the commons label no longer fits in 32 bytes");
        }
        System.arraycopy(label, 0, id, 0, label.length);
        // unsafeWrap is safe here: `id` is freshly allocated in this method and never escapes it.
        return Bytes.unsafeWrap(id);
    }

    private final TrackerClient trackers;
    private final dev.nodera.core.identity.NodeId self;
    private final NodeCapabilities capabilities;

    /**
     * @param trackers     the client this node announces and queries through.
     * @param self         this node's id, so it can drop itself from what it reads back.
     * @param capabilities what this node advertises it can do.
     */
    public CommonsPresence(TrackerClient trackers, dev.nodera.core.identity.NodeId self,
            NodeCapabilities capabilities) {
        this.trackers = trackers;
        this.self = self;
        this.capabilities = capabilities;
    }

    /**
     * Announce into the commons and read back who else is there.
     *
     * <p>Announce first, then query, so this node is in the answer it receives — a peer that queried
     * first would see a swarm it is not yet part of and could report itself as alone on a network
     * where it is not.
     *
     * <p>Never throws. Presence is a convenience for discovery; a tracker having a bad minute must
     * not take down the worker that called this.
     *
     * @param selfRoute      this node's reachable {@code host:port}, or {@code null} while unbound.
     * @param nowEpochMillis the current wall clock.
     * @return the other peers present, never null and never containing this node.
     */
    public List<PeerEntry> round(String selfRoute, long nowEpochMillis) {
        if (selfRoute == null || selfRoute.isBlank()) {
            // Announcing without a route would put an unreachable row in front of every peer that
            // reads the commons, which is worse for them than this node staying quiet.
            return List.of();
        }
        try {
            TrackerAnnounce announce = trackers.buildAnnounce(
                    WORLD_ID, AnnounceEvent.STARTED, List.of(selfRoute), capabilities,
                    List.of(), WORLD_NAME, 0L, 0, nowEpochMillis);
            trackers.announce(announce);
        } catch (RuntimeException e) {
            LOG.debug("Nodera commons: announce failed ({})", e.toString());
        }

        try {
            return trackers.query(WORLD_ID)
                    .map(TrackerResponse::peers)
                    .map(this::withoutSelf)
                    .orElseGet(List::of);
        } catch (RuntimeException e) {
            LOG.debug("Nodera commons: query failed ({})", e.toString());
            return List.of();
        }
    }

    private List<PeerEntry> withoutSelf(List<PeerEntry> peers) {
        List<PeerEntry> others = new ArrayList<>(peers.size());
        for (PeerEntry peer : peers) {
            if (!self.equals(peer.nodeId())) {
                others.add(peer);
            }
        }
        return List.copyOf(others);
    }

    /** The raw 32 bytes, for a test that compares them with the Rust constant. */
    public static byte[] worldIdBytes() {
        return Arrays.copyOf(WORLD_ID.toArray(), 32);
    }
}
