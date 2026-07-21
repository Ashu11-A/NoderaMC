package dev.nodera.protocol.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A peer's canonical, Ed25519-signable rendezvous registration record (Task 29, type tag
 * {@value TypeTags#SIGNED_PEER_RECORD}).
 *
 * <p>The signature a peer produces covers exactly this value's {@link #encode(CanonicalWriter)}
 * output, so a discovering peer verifies the <b>same bytes</b> the registering peer signed — whether
 * the record arrives inside a {@code RendezvousRegister} (the relay verifies) or a
 * {@code RendezvousPeers} page (a discovering peer verifies). The relay carries records; peers
 * authenticate them end-to-end (rendezvous.md §8.1). The service is never authority: a lying relay
 * can hide or invent peers but cannot forge a record.
 *
 * <p>Namespace = {@code (networkId, genesisHash)} (rendezvous.md §3.1 / Task 29 Context): peers
 * register under a network + world so discovery returns swarm-relevant peers.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param networkId            the network this record belongs to.
 * @param genesisHash          the world (swarm id) this record belongs to.
 * @param peer                 the registering peer's node id.
 * @param publicKey            the peer's X.509 Ed25519 public key.
 * @param event               what the registration asks for (covered by the signature so
 *                             {@code UNREGISTER} cannot be forged).
 * @param candidates          advertised reachability candidates, in preference order; claims only.
 * @param capabilities        the peer's declared capabilities.
 * @param issuedAtEpochMillis the peer's wall-clock at issue time — a freshness bound only.
 * @param expiresAtEpochMillis when the record self-expires unless refreshed.
 */
public record SignedPeerRecord(
        UUID networkId,
        Bytes genesisHash,
        NodeId peer,
        Bytes publicKey,
        RegistrationEvent event,
        List<PeerCandidate> candidates,
        NodeCapabilities capabilities,
        long issuedAtEpochMillis,
        long expiresAtEpochMillis) implements Encodable {

    /**
     * Compact constructor: validates and defensive-copies the candidate list.
     *
     * @throws IllegalArgumentException if a reference argument is null, {@code genesisHash} is
     *                                  empty, or a timestamp is negative.
     */
    public SignedPeerRecord {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(capabilities, "capabilities");
        if (genesisHash.isEmpty()) {
            throw new IllegalArgumentException("genesisHash must not be empty");
        }
        if (issuedAtEpochMillis < 0 || expiresAtEpochMillis < 0) {
            throw new IllegalArgumentException("timestamps must be non-negative");
        }
        candidates = List.copyOf(candidates);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.SIGNED_PEER_RECORD).writeU16(Encodable.ENCODING_VERSION);
        w.writeU64(networkId.getMostSignificantBits());
        w.writeU64(networkId.getLeastSignificantBits());
        w.writeBytes(genesisHash);
        peer.encode(w);
        w.writeBytes(publicKey);
        w.writeU8(event.ordinal());
        w.writeList(candidates, (ww, c) -> c.encode(ww));
        capabilities.encode(w);
        w.writeU64(issuedAtEpochMillis);
        w.writeU64(expiresAtEpochMillis);
    }

    /**
     * The exact canonical bytes an Ed25519 signature covers.
     *
     * @return the signed bytes.
     * @Thread-context any thread.
     */
    public Bytes signedBytes() {
        CanonicalWriter w = new CanonicalWriter(256);
        encode(w);
        return w.toBytes();
    }

    /**
     * Decode the inverse of {@link #encode(CanonicalWriter)}.
     *
     * @param r the canonical source.
     * @return the record.
     * @throws IllegalStateException if the tag/version is wrong.
     * @Thread-context one reader per decode call.
     */
    public static SignedPeerRecord decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.SIGNED_PEER_RECORD) {
            throw new IllegalStateException("expected SignedPeerRecord tag, got " + tag);
        }
        int version = r.readU16();
        if (version != Encodable.ENCODING_VERSION) {
            throw new IllegalStateException("unsupported SignedPeerRecord version " + version);
        }
        UUID networkId = new UUID(r.readU64(), r.readU64());
        Bytes genesisHash = r.readBytesValue();
        NodeId peer = NodeId.decode(r);
        Bytes publicKey = r.readBytesValue();
        RegistrationEvent event = RegistrationEvent.decodeOrdinal(r);
        List<PeerCandidate> candidates = r.readList(PeerCandidate::decode);
        NodeCapabilities capabilities = NodeCapabilities.decode(r);
        long issuedAt = r.readU64();
        long expiresAt = r.readU64();
        return new SignedPeerRecord(networkId, genesisHash, peer, publicKey, event, candidates,
                capabilities, issuedAt, expiresAt);
    }
}
