package dev.nodera.mod.common;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeIdentity;

/**
 * Issue #36 (flaw F1): proof that a node announce comes from the holder of the announced key.
 *
 * <p>The old announce path blindly trusted the {@code nodeId}+{@code publicKey} a client sent, so
 * anyone could register another player's node with their own key (or vice-versa). This binds the
 * announce to a fresh, server-issued, single-use challenge: the client signs
 * {@code challenge ‖ nodeId ‖ publicKey ‖ mcUuid} with its peer identity, and the server verifies
 * the signature against the announced {@code publicKey} before recording the node. Including the
 * announced public key <i>inside</i> the signed bytes stops an attacker from swapping the key; the
 * MC UUID binds the proof to the exact vanilla connection the challenge was issued for; the
 * per-login challenge stops replay.
 *
 * <p>Pure — no Minecraft types — so the whole matrix is unit-testable.
 *
 * @Thread-context stateless, any thread.
 */
public final class NodeAnnounceProof {

    private NodeAnnounceProof() {
    }

    /** The canonical bytes the announce signature covers. */
    public static Bytes canonicalBytes(Bytes challenge, String nodeIdUuid, Bytes publicKey,
                                       String mcUuid) {
        CanonicalWriter w = new CanonicalWriter();
        w.writeBytes(challenge);
        w.writeString(nodeIdUuid);
        w.writeBytes(publicKey);
        w.writeString(mcUuid);
        return w.toBytes();
    }

    /** Sign the proof with the announcing peer's identity (client side). */
    public static Bytes sign(NodeIdentity identity, Bytes challenge, String mcUuid) {
        return identity.sign(canonicalBytes(challenge, identity.nodeId().value().toString(),
                identity.publicKeyBytes(), mcUuid));
    }

    /**
     * Verify an announce proof (server side).
     *
     * @param publicKey  the announced public key — both the verification key and part of the signed
     *                   content, so it cannot be swapped.
     * @param signature  the client's signature over {@link #canonicalBytes}.
     * @param challenge  the challenge the server issued for this login (non-empty).
     * @param nodeIdUuid the announced NodeId UUID string.
     * @param mcUuid     the server-authoritative Minecraft UUID of the connection.
     * @return whether the proof is valid.
     */
    public static boolean verify(Bytes publicKey, Bytes signature, Bytes challenge,
                                 String nodeIdUuid, String mcUuid) {
        if (publicKey == null || publicKey.isEmpty() || signature == null || signature.isEmpty()
                || challenge == null || challenge.isEmpty()) {
            return false;
        }
        return new SignatureService().verify(publicKey,
                canonicalBytes(challenge, nodeIdUuid, publicKey, mcUuid), signature);
    }
}
