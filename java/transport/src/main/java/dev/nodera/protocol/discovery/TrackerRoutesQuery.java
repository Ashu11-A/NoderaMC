package dev.nodera.protocol.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * Ask a tracker for the <b>full claimed dial-route lists</b> of a world's live peers (wire tag 49).
 *
 * <p>{@link TrackerResponse}'s {@code PeerEntry} carries exactly one route per peer — enough to
 * bootstrap the P2P mesh, but not enough for the join flow: a host announces <em>several</em>
 * routes in preference order (its P2P listener, and — while its game is open — the Minecraft game
 * endpoint joiners actually connect to, {@code "mc/host:port"}). This query returns every route
 * each live peer claimed in its signed announce, so a joiner can pick the lane it needs.
 *
 * <p>Trust: routes are the peers' own signed claims relayed by the tracker verbatim
 * ({@code docs/torrent/trackers.md} §5) — the tracker adds no authority, and a joiner treats them
 * as dial candidates, never as proof of anything.
 *
 * @param genesisHash the world whose peers' routes are requested (the swarm id).
 * @Thread-context immutable record, safe for any thread.
 */
public record TrackerRoutesQuery(Bytes genesisHash) implements NoderaMessage {

    public TrackerRoutesQuery {
        Objects.requireNonNull(genesisHash, "genesisHash");
    }
}
