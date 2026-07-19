package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A pasteable invitation blob — bootstrap mechanism #3 (Task 20). A friend already in a world sends
 * you a base64 string; you paste it and you are in.
 *
 * <h2>What the signature does and does not prove</h2>
 *
 * <p>The blob is signed by the inviting peer, and {@link #decode} verifies that signature against
 * the public key carried <i>in the blob itself</i>. That is deliberately a weak claim, and worth
 * stating plainly: it proves the invitation was authored as a unit and not tampered with in
 * transit (nobody swapped the addresses in your friend's message), but it does <b>not</b> prove the
 * signer is trustworthy — anyone can generate a key pair and sign their own invitation.
 *
 * <p>The real check happens after connecting: the joining peer verifies the <b>genesis hash</b> and
 * the certificate chain of the state it receives. State self-verifies; peers do not. So the worst a
 * forged invitation achieves is directing you at a network you then refuse to accept state from.
 *
 * <h2>Format</h2>
 *
 * <p>URL-safe base64 (no padding) over the canonical encoding
 * {@code [u16 INVITATION][u16 ENCODING_VERSION][u64 networkIdMsb][u64 networkIdLsb]
 * [bytes genesisHash][NodeId inviter][bytes inviterPublicKey][list String routes][bytes signature]},
 * where the signature covers everything before it. URL-safe so an invitation survives being pasted
 * into a chat client, a URL, or a config file without escaping.
 *
 * <p>Thread-context: stateless static helpers; safe for any thread.
 */
public final class InvitationCodec {

    private InvitationCodec() {}

    /**
     * The decoded contents of an invitation.
     *
     * @param networkId        the network's id.
     * @param genesisHash      the world's genesis hash — the thing the joiner actually verifies.
     * @param inviter          the signing peer.
     * @param inviterPublicKey the signer's X.509 public key, used to verify {@code signature}.
     * @param routes           transport routes to try, in order.
     * @param signature        Ed25519 over the canonical bytes preceding it.
     * @Thread-context immutable record, safe for any thread.
     */
    public record Invitation(
            UUID networkId,
            Bytes genesisHash,
            NodeId inviter,
            Bytes inviterPublicKey,
            List<String> routes,
            Bytes signature
    ) implements Encodable {

        /**
         * Compact constructor.
         *
         * @throws IllegalArgumentException if a reference argument is null, {@code genesisHash} is
         *                                  empty, or {@code routes} is empty (an invitation with
         *                                  nowhere to dial is not an invitation).
         */
        public Invitation {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(genesisHash, "genesisHash");
            Objects.requireNonNull(inviter, "inviter");
            Objects.requireNonNull(inviterPublicKey, "inviterPublicKey");
            Objects.requireNonNull(routes, "routes");
            Objects.requireNonNull(signature, "signature");
            if (genesisHash.isEmpty()) {
                throw new IllegalArgumentException("genesisHash must not be empty");
            }
            if (routes.isEmpty()) {
                throw new IllegalArgumentException("an invitation must carry at least one route");
            }
            routes = List.copyOf(routes);
        }

        @Override
        public void encode(CanonicalWriter w) {
            writeSignedPortion(w, networkId, genesisHash, inviter, inviterPublicKey, routes);
            w.writeBytes(signature);
        }

        /**
         * The exact bytes the signature covers — everything but the signature itself.
         *
         * @return the signed portion.
         * @Thread-context any thread.
         */
        public Bytes signedPortion() {
            CanonicalWriter w = new CanonicalWriter(256);
            writeSignedPortion(w, networkId, genesisHash, inviter, inviterPublicKey, routes);
            return w.toBytes();
        }
    }

    private static void writeSignedPortion(
            CanonicalWriter w, UUID networkId, Bytes genesisHash, NodeId inviter,
            Bytes inviterPublicKey, List<String> routes) {
        w.writeU16(TypeTags.INVITATION).writeU16(Encodable.ENCODING_VERSION);
        w.writeU64(networkId.getMostSignificantBits());
        w.writeU64(networkId.getLeastSignificantBits());
        w.writeBytes(genesisHash);
        inviter.encode(w);
        w.writeBytes(inviterPublicKey);
        w.writeList(routes, CanonicalWriter::writeString);
    }

    /**
     * Create and sign an invitation.
     *
     * @param identity    the inviting peer's identity (signs the blob).
     * @param networkId   the network id.
     * @param genesisHash the world's genesis hash.
     * @param routes      routes a joiner should try, in order.
     * @return the base64 blob to paste.
     * @throws IllegalArgumentException if an argument is null or {@code routes} is empty.
     * @Thread-context any thread.
     */
    public static String encode(
            NodeIdentity identity, UUID networkId, Bytes genesisHash, List<String> routes) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(routes, "routes");
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("an invitation must carry at least one route");
        }
        CanonicalWriter signedWriter = new CanonicalWriter(256);
        writeSignedPortion(signedWriter, networkId, genesisHash, identity.nodeId(),
                identity.publicKeyBytes(), routes);
        Bytes signature = identity.sign(signedWriter.toBytes());

        Invitation invitation = new Invitation(networkId, genesisHash, identity.nodeId(),
                identity.publicKeyBytes(), routes, signature);
        CanonicalWriter full = new CanonicalWriter(320);
        invitation.encode(full);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(full.toByteArray());
    }

    /**
     * Decode and verify a pasted invitation.
     *
     * @param blob       the base64 string.
     * @param signatures the verifier.
     * @return the invitation.
     * @throws IllegalArgumentException if the blob is null/blank or not valid base64.
     * @throws IllegalStateException    if the frame is malformed or the signature does not verify.
     * @Thread-context any thread.
     */
    public static Invitation decode(String blob, SignatureService signatures) {
        Objects.requireNonNull(blob, "blob");
        Objects.requireNonNull(signatures, "signatures");
        if (blob.isBlank()) {
            throw new IllegalArgumentException("invitation blob must not be blank");
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(blob.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invitation blob is not valid URL-safe base64", e);
        }

        CanonicalReader r = new CanonicalReader(raw);
        int tag = r.readU16();
        if (tag != TypeTags.INVITATION) {
            throw new IllegalStateException("expected INVITATION tag, got " + tag);
        }
        r.readVersion(Encodable.ENCODING_VERSION);
        long msb = r.readU64();
        long lsb = r.readU64();
        Bytes genesisHash = r.readBytesValue();
        NodeId inviter = NodeId.decode(r);
        Bytes publicKey = r.readBytesValue();
        List<String> routes = r.readList(CanonicalReader::readString);
        Bytes signature = r.readBytesValue();

        Invitation invitation = new Invitation(
                new UUID(msb, lsb), genesisHash, inviter, publicKey, routes, signature);

        if (!signatures.verify(publicKey, invitation.signedPortion(), signature)) {
            throw new IllegalStateException("invitation signature does not verify");
        }
        return invitation;
    }
}
