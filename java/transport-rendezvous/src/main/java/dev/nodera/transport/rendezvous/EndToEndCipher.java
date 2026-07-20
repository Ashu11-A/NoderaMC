package dev.nodera.transport.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.crypto.symmetric.ContentCipher;
import dev.nodera.core.crypto.symmetric.ContentKey;
import dev.nodera.core.identity.NodeIdentity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;
import java.util.Optional;

import javax.crypto.KeyAgreement;

/**
 * End-to-end authenticated encryption for a relayed leg (Task 29; rendezvous.md §8.2).
 *
 * <p>Encrypting only the individual relay legs would let the relay read the traffic. This runs an
 * additional end-to-end session <i>through</i> the relay: an ephemeral X25519 ECDH handshake, each
 * ephemeral key Ed25519-signed by the peer's {@link NodeIdentity}, deriving an AES-GCM session key
 * the relay never sees. Direct legs may skip encryption (messages are signed) but reuse this same
 * session-establishment code path.
 *
 * <h2>Identity binding (rendezvous.md §4.4)</h2>
 *
 * <p>The address obtained from rendezvous is never proof of identity. The handshake verifies that
 * the remote presents the exact X.509 identity key from the discovered record (the {@code
 * expectedPeerPublicKey}) and signs its ephemeral key with it — so a relay swapping the far end for
 * an impostor is caught before any application byte crosses.
 *
 * <p>Thread-context: {@link Session#seal(byte[])} / {@link Session#open(byte[])} are safe from any
 * one thread; a session is not shared concurrently. Handshake is blocking IO.
 */
public final class EndToEndCipher {

    private static final HashService HASHES = new HashService();
    private static final SignatureService SIGNATURES = new SignatureService();
    private static final SecureRandom RANDOM = new SecureRandom();

    private EndToEndCipher() {}

    /**
     * Run the handshake over an already-connected duplex stream pair and return the session.
     *
     * <p>Both sides write their hello then read the peer's, so the exchange completes over a single
     * TCP connection without a designated first mover; the messages are small and flushed.
     *
     * @param in                    the leg's input stream.
     * @param out                   the leg's output stream.
     * @param self                  this peer's identity (signs the ephemeral key; private key never
     *                              leaves it).
     * @param expectedPeerPublicKey the remote's X.509 identity key from the discovered record.
     * @param initiator             whether this side initiated the circuit (fixes the nonce domain).
     * @return the established session.
     * @throws IOException              on IO failure.
     * @throws SecurityException        if the peer's identity or signature does not verify.
     * @Thread-context blocking; one call per leg.
     */
    public static Session handshake(
            InputStream in,
            OutputStream out,
            NodeIdentity self,
            Bytes expectedPeerPublicKey,
            boolean initiator)
            throws IOException {
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(expectedPeerPublicKey, "expectedPeerPublicKey");
        try {
            KeyPair ephemeral = newX25519KeyPair();
            byte[] ephPublic = ephemeral.getPublic().getEncoded(); // X.509-encoded X25519 key
            Bytes signature = self.sign(Bytes.unsafeWrap(ephPublic));

            RendezvousFrames.write(out, hello(self.publicKeyBytes().toArray(), ephPublic,
                    signature.toArray()));

            byte[] peerHello = RendezvousFrames.read(in)
                    .orElseThrow(() -> new SecurityException("peer closed before its E2E hello"));
            Hello peer = parseHello(peerHello);

            if (!Bytes.unsafeWrap(peer.identityX509).equals(expectedPeerPublicKey)) {
                throw new SecurityException(
                        "relayed peer presented a different identity than the discovered record");
            }
            if (!SIGNATURES.verify(Bytes.unsafeWrap(peer.identityX509),
                    Bytes.unsafeWrap(peer.ephemeralX509), Bytes.unsafeWrap(peer.signature))) {
                throw new SecurityException("peer's ephemeral key signature did not verify");
            }

            byte[] shared = ecdh(ephemeral, peer.ephemeralX509);
            // The session key mixes the shared secret with both ephemeral keys so the two sides
            // derive an identical key regardless of who initiated.
            ContentKey key = deriveKey(shared, ephPublic, peer.ephemeralX509);
            return new Session(key, initiator);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("E2E handshake failed", e);
        }
    }

    private static byte[] hello(byte[] identityX509, byte[] ephemeralX509, byte[] signature)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bytes);
        writeField(dos, identityX509);
        writeField(dos, ephemeralX509);
        writeField(dos, signature);
        return bytes.toByteArray();
    }

    private static Hello parseHello(byte[] frame) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(frame));
        byte[] identity = readField(dis);
        byte[] ephemeral = readField(dis);
        byte[] signature = readField(dis);
        return new Hello(identity, ephemeral, signature);
    }

    private static void writeField(DataOutputStream dos, byte[] field) throws IOException {
        dos.writeInt(field.length);
        dos.write(field);
    }

    private static byte[] readField(DataInputStream dis) throws IOException {
        int len = dis.readInt();
        if (len < 0 || len > 4096) {
            throw new IllegalStateException("E2E hello field out of range: " + len);
        }
        byte[] field = new byte[len];
        dis.readFully(field);
        return field;
    }

    private static KeyPair newX25519KeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
        return kpg.generateKeyPair();
    }

    private static byte[] ecdh(KeyPair self, byte[] peerX509) throws GeneralSecurityException {
        PublicKey peerPublic = KeyFactory.getInstance("X25519")
                .generatePublic(new X509EncodedKeySpec(peerX509));
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(self.getPrivate());
        agreement.doPhase(peerPublic, true);
        return agreement.generateSecret();
    }

    /** Derive the AES-256 session key: {@code SHA-256(shared || minEph || maxEph)}. */
    private static ContentKey deriveKey(byte[] shared, byte[] ephA, byte[] ephB) {
        // Ordering the two ephemeral keys makes the derivation independent of who is A vs B.
        byte[] lo = compare(ephA, ephB) <= 0 ? ephA : ephB;
        byte[] hi = compare(ephA, ephB) <= 0 ? ephB : ephA;
        ByteArrayOutputStream material = new ByteArrayOutputStream();
        material.writeBytes(shared);
        material.writeBytes(lo);
        material.writeBytes(hi);
        Bytes digest = HASHES.sha256(Bytes.unsafeWrap(material.toByteArray()));
        return ContentKey.of(digest.toArray());
    }

    private static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }
        return a.length - b.length;
    }

    private record Hello(byte[] identityX509, byte[] ephemeralX509, byte[] signature) {}

    /**
     * An established end-to-end session: seals outbound frames and opens inbound ones under the
     * derived AES-GCM key. A fresh random 96-bit nonce is prepended to each ciphertext, so the two
     * directions never collide even though they share the key.
     *
     * <p>Thread-context: use from one thread per direction.
     */
    public static final class Session {

        private final ContentKey key;
        private final boolean initiator;

        private Session(ContentKey key, boolean initiator) {
            this.key = key;
            this.initiator = initiator;
        }

        /** @return whether this side initiated the circuit. */
        public boolean isInitiator() {
            return initiator;
        }

        /**
         * Encrypt a frame: {@code nonce(12) || AES-GCM(plaintext)}.
         *
         * @param plaintext the frame to seal (non-empty).
         * @return the sealed frame.
         * @Thread-context one thread per direction.
         */
        public byte[] seal(byte[] plaintext) {
            byte[] nonce = new byte[ContentCipher.NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Bytes ciphertext = ContentCipher.encrypt(
                    key, Bytes.unsafeWrap(nonce), Bytes.unsafeWrap(plaintext));
            byte[] out = new byte[nonce.length + ciphertext.length()];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ciphertext.toArray(), 0, out, nonce.length, ciphertext.length());
            return out;
        }

        /**
         * Decrypt a sealed frame; empty if the tag does not verify (tamper / wrong key).
         *
         * @param sealed the sealed frame.
         * @return the plaintext, or empty on any failure.
         * @Thread-context one thread per direction.
         */
        public Optional<byte[]> open(byte[] sealed) {
            if (sealed.length <= ContentCipher.NONCE_BYTES) {
                return Optional.empty();
            }
            byte[] nonce = new byte[ContentCipher.NONCE_BYTES];
            System.arraycopy(sealed, 0, nonce, 0, nonce.length);
            byte[] ciphertext = new byte[sealed.length - nonce.length];
            System.arraycopy(sealed, nonce.length, ciphertext, 0, ciphertext.length);
            return ContentCipher.decrypt(key, Bytes.unsafeWrap(nonce), Bytes.unsafeWrap(ciphertext))
                    .map(Bytes::toArray);
        }
    }
}
