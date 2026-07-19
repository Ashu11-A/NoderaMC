package dev.nodera.protocol.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.content.ManifestHolding;

import java.util.List;
import java.util.Objects;

/**
 * A peer's signed self-registration with a tracker (Task 28, wire tag 33).
 *
 * <p>The embedded Java tracker (Task 20) learned everything for free by riding membership gossip
 * and {@link InventoryAdvertisement}. A tracker in its own process is not on that bus, so peers
 * tell it explicitly: who they are, which world, how to reach them, what they hold, and how
 * reliable they claim to be — signed, so a third party cannot register or evict someone else.
 *
 * <h2>What the signature proves (and what it does not)</h2>
 *
 * <p>{@link #signedPortion()} is Ed25519-signed with the announcing peer's identity key and
 * verified against {@link #publicKey()} — which the tracker in turn checks derives the claimed
 * {@link #peer()} id. That stops impersonation and record tampering: only the holder of a key can
 * register, refresh, or {@code STOPPED}-remove that identity's record. It does <b>not</b> stop
 * identity farming (Sybil resistance is L-18 / Task 16), and it grants the tracker no authority:
 * peers verify world state by hash and certificate chain regardless of who introduced them.
 *
 * <p>{@link #announceEpochMillis()} bounds replay — a tracker rejects announces far outside its
 * accepted window, so a captured announce cannot resurrect a departed peer indefinitely.
 *
 * <h2>Routes are claims</h2>
 *
 * <p>{@link #routes()} are the peer's own claim about where it is reachable, exactly like
 * {@code SocketPeerTransport}'s hello. The tracker may append the observed source address as a
 * low-priority hint but never treats an address as proof of identity
 * ({@code docs/torrent/trackers.md} §5).
 *
 * @param genesisHash        the world being announced to (the swarm id — the {@code info_hash}
 *                           analog).
 * @param peer               the announcing peer's node id.
 * @param publicKey          the peer's X.509 Ed25519 public key, used to verify {@link #signature}.
 * @param event              the lifecycle event (see {@link AnnounceEvent}).
 * @param routes             advertised dial routes, in preference order; may be empty for a
 *                           {@code STOPPED} announce.
 * @param capabilities       the peer's declared capabilities, including its roles — the tracker's
 *                           seeder floor reads {@code FULL_ARCHIVE}/{@code WORLD_SEEDER} from here.
 * @param holdings           which pieces of which manifests the peer holds for this world.
 * @param worldName          the world's display name, honoured only from a {@code FULL_ARCHIVE}
 *                           host (rule 0: the host is the world's physical backup); {@code ""}
 *                           from anyone else. Names decorate a world; the genesis hash identifies
 *                           it, so a lying name misleads a UI and nothing more.
 * @param retentionDeadlineEpochMillis the Task 22 drop deadline the host wants surfaced, or
 *                           {@code 0} when no countdown is running. The tracker only displays it —
 *                           the peers' {@code RetentionPolicy} still owns the actual drop.
 * @param reliabilityBps     the peer's reliability in basis points (0..10000).
 * @param announceEpochMillis the peer's wall-clock at announce time — a freshness bound, never a
 *                           consensus input.
 * @param signature          Ed25519 over {@link #signedPortion()}.
 * @Thread-context immutable record, safe for any thread.
 */
public record TrackerAnnounce(
        Bytes genesisHash,
        NodeId peer,
        Bytes publicKey,
        AnnounceEvent event,
        List<String> routes,
        NodeCapabilities capabilities,
        List<ManifestHolding> holdings,
        String worldName,
        long retentionDeadlineEpochMillis,
        int reliabilityBps,
        long announceEpochMillis,
        Bytes signature
) implements NoderaMessage {

    /**
     * Compact constructor: validates and defensive-copies the lists.
     *
     * @throws IllegalArgumentException if a reference argument is null, {@code genesisHash} is
     *                                  empty, {@code reliabilityBps} is outside 0..10000, or
     *                                  {@code announceEpochMillis} is negative.
     */
    public TrackerAnnounce {
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(holdings, "holdings");
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(signature, "signature");
        if (retentionDeadlineEpochMillis < 0) {
            throw new IllegalArgumentException("retentionDeadlineEpochMillis must be non-negative");
        }
        if (genesisHash.isEmpty()) {
            throw new IllegalArgumentException("genesisHash must not be empty");
        }
        if (reliabilityBps < 0 || reliabilityBps > TrackerResponse.RELIABILITY_BPS_SCALE) {
            throw new IllegalArgumentException(
                    "reliabilityBps out of range: " + reliabilityBps);
        }
        if (announceEpochMillis < 0) {
            throw new IllegalArgumentException("announceEpochMillis must be non-negative");
        }
        routes = List.copyOf(routes);
        holdings = List.copyOf(holdings);
    }

    /**
     * The exact bytes the signature covers: the full canonical frame minus the trailing signature
     * field.
     *
     * <p>Signing over the frame (tag included) means an announce cannot be replayed as a different
     * message type, and the Rust service verifies the identical byte range it received rather than
     * a re-encoding of a decoded value.
     *
     * @return the signed portion.
     * @Thread-context any thread.
     */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter(512);
        writeSignedPortion(w);
        return w.toBytes();
    }

    /**
     * Write the signed portion into {@code w} — used by both {@link #signedPortion()} and the
     * codec, so the two can never disagree about where the signature starts.
     *
     * @param w the canonical sink.
     * @Thread-context any thread.
     */
    public void writeSignedPortion(CanonicalWriter w) {
        w.writeU16(MessageCodec.TAG_TRACKER_ANNOUNCE).writeU16(Encodable.ENCODING_VERSION);
        w.writeBytes(genesisHash);
        peer.encode(w);
        w.writeBytes(publicKey);
        w.writeU8(event.ordinal());
        w.writeList(routes, CanonicalWriter::writeString);
        capabilities.encode(w);
        w.writeList(holdings, (ww, h) -> {
            ww.writeBytes(h.manifestRoot());
            ww.writeBytes(h.pieceBitmap());
        });
        w.writeString(worldName);
        w.writeU64(retentionDeadlineEpochMillis);
        w.writeU32(Integer.toUnsignedLong(reliabilityBps));
        w.writeU64(announceEpochMillis);
    }
}
