package dev.nodera.endpoint.share;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #36 (F1): the challenge-response announce proof — the anti-spoofing spoof matrix. */
final class NodeAnnounceProofTest {

    private static final Bytes CHALLENGE =
            Bytes.unsafeWrap("a-32-byte-ish-random-challenge!!".getBytes());

    /** One announce as it is presented for verification: five fields, any of which may be a lie. */
    private record Announce(Bytes publicKey, Bytes signature, Bytes challenge,
            String nodeId, String mcUuid) {

        /** @return an announce in which nothing is a lie. */
        static Announce honest() {
            NodeIdentity id = NodeIdentity.generate();
            String mcUuid = UUID.randomUUID().toString();
            return new Announce(id.publicKeyBytes(),
                    NodeAnnounceProof.sign(id, CHALLENGE, mcUuid),
                    CHALLENGE, id.nodeId().value().toString(), mcUuid);
        }

        boolean verifies() {
            return NodeAnnounceProof.verify(publicKey, signature, challenge, nodeId, mcUuid);
        }
    }

    @Test
    void honestProofVerifies() {
        assertTrue(Announce.honest().verifies());
    }

    /**
     * The spoof matrix: one claim — verification fails — over which field was tampered with.
     *
     * <p>Each of these was its own method generating its own identity and its own signature, and
     * the difference between them was a single field. Written out as one row each, the matrix is
     * readable as a matrix, and a seventh attack is a seventh row rather than a seventh method.
     *
     * @return one row per attack: the name JUnit reports, and the announce presented.
     */
    static Stream<Arguments> spoofs() {
        Announce honest = Announce.honest();
        NodeIdentity attacker = NodeIdentity.generate();
        return Stream.of(
                Arguments.of("the challenge is not the one that was signed",
                        new Announce(honest.publicKey(), honest.signature(),
                                Bytes.unsafeWrap("a-DIFFERENT-32-byte-ish-challenge".getBytes()),
                                honest.nodeId(), honest.mcUuid())),
                Arguments.of("the Minecraft UUID is not the one that was signed",
                        new Announce(honest.publicKey(), honest.signature(), honest.challenge(),
                                honest.nodeId(), UUID.randomUUID().toString())),
                Arguments.of("the node id is not the one that was signed",
                        new Announce(honest.publicKey(), honest.signature(), honest.challenge(),
                                UUID.randomUUID().toString(), honest.mcUuid())),
                // The attacker signs with their own identity and presents the victim's public key:
                // the key is inside the signed bytes AND is the verification key, so both mismatch.
                Arguments.of("the signature belongs to a different key than the one announced",
                        new Announce(honest.publicKey(),
                                NodeAnnounceProof.sign(attacker, CHALLENGE, honest.mcUuid()),
                                honest.challenge(), attacker.nodeId().value().toString(),
                                honest.mcUuid())));
    }

    @ParameterizedTest(name = "refused when {0}")
    @MethodSource("spoofs")
    void everySpoofIsRefused(String attack, Announce announce) {
        assertFalse(announce.verifies(), attack);
    }

    @Test
    void emptyInputsFail() {
        // Kept as one test rather than three more rows above, so the flat test count is unchanged
        // by this refactor: an absent field is one claim, not three attacks.
        Announce honest = Announce.honest();
        assertFalse(new Announce(honest.publicKey(), Bytes.empty(), honest.challenge(),
                honest.nodeId(), honest.mcUuid()).verifies(), "empty signature");
        assertFalse(new Announce(honest.publicKey(), honest.signature(), Bytes.empty(),
                honest.nodeId(), honest.mcUuid()).verifies(), "empty challenge");
        assertFalse(new Announce(Bytes.empty(), honest.signature(), honest.challenge(),
                honest.nodeId(), honest.mcUuid()).verifies(), "empty public key");
    }
}
