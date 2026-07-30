package dev.nodera.endpoint.share;

import dev.nodera.endpoint.share.NodeAnnounceProof;
import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #36 (F1): the challenge-response announce proof — the anti-spoofing spoof matrix. */
final class NodeAnnounceProofTest {

    private static Bytes challenge() {
        return Bytes.unsafeWrap("a-32-byte-ish-random-challenge!!".getBytes());
    }

    @Test
    void honestProofVerifies() {
        NodeIdentity id = NodeIdentity.generate();
        String mcUuid = UUID.randomUUID().toString();
        Bytes ch = challenge();
        Bytes sig = NodeAnnounceProof.sign(id, ch, mcUuid);
        assertTrue(NodeAnnounceProof.verify(id.publicKeyBytes(), sig, ch,
                id.nodeId().value().toString(), mcUuid));
    }

    @Test
    void wrongChallengeFails() {
        NodeIdentity id = NodeIdentity.generate();
        String mcUuid = UUID.randomUUID().toString();
        Bytes sig = NodeAnnounceProof.sign(id, challenge(), mcUuid);
        Bytes otherChallenge = Bytes.unsafeWrap("a-DIFFERENT-32-byte-ish-challenge".getBytes());
        assertFalse(NodeAnnounceProof.verify(id.publicKeyBytes(), sig, otherChallenge,
                id.nodeId().value().toString(), mcUuid));
    }

    @Test
    void wrongMcUuidFails() {
        NodeIdentity id = NodeIdentity.generate();
        Bytes ch = challenge();
        Bytes sig = NodeAnnounceProof.sign(id, ch, UUID.randomUUID().toString());
        assertFalse(NodeAnnounceProof.verify(id.publicKeyBytes(), sig, ch,
                id.nodeId().value().toString(), UUID.randomUUID().toString()));
    }

    @Test
    void swappedKeyFails() {
        // Attacker signs with their own identity but presents the victim's public key: the key is
        // inside the signed bytes AND is the verification key, so both mismatch.
        NodeIdentity victim = NodeIdentity.generate();
        NodeIdentity attacker = NodeIdentity.generate();
        String mcUuid = UUID.randomUUID().toString();
        Bytes ch = challenge();
        Bytes attackerSig = NodeAnnounceProof.sign(attacker, ch, mcUuid);
        // Announcing the victim's key with the attacker's signature must fail.
        assertFalse(NodeAnnounceProof.verify(victim.publicKeyBytes(), attackerSig, ch,
                attacker.nodeId().value().toString(), mcUuid));
    }

    @Test
    void spoofedNodeIdFails() {
        // Client signs its real nodeId; announcing a different nodeId breaks the signed bytes.
        NodeIdentity id = NodeIdentity.generate();
        String mcUuid = UUID.randomUUID().toString();
        Bytes ch = challenge();
        Bytes sig = NodeAnnounceProof.sign(id, ch, mcUuid);
        assertFalse(NodeAnnounceProof.verify(id.publicKeyBytes(), sig, ch,
                UUID.randomUUID().toString(), mcUuid));
    }

    @Test
    void emptyInputsFail() {
        NodeIdentity id = NodeIdentity.generate();
        String mcUuid = UUID.randomUUID().toString();
        Bytes ch = challenge();
        Bytes sig = NodeAnnounceProof.sign(id, ch, mcUuid);
        assertFalse(NodeAnnounceProof.verify(id.publicKeyBytes(), Bytes.empty(), ch,
                id.nodeId().value().toString(), mcUuid));
        assertFalse(NodeAnnounceProof.verify(id.publicKeyBytes(), sig, Bytes.empty(),
                id.nodeId().value().toString(), mcUuid));
        assertFalse(NodeAnnounceProof.verify(Bytes.empty(), sig, ch,
                id.nodeId().value().toString(), mcUuid));
    }
}
