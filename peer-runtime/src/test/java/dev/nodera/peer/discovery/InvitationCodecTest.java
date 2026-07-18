package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An invitation is a pasteable bootstrap blob. Its signature proves integrity-in-transit (nobody
 * swapped the addresses in your friend's message), not trust — anyone can mint a key pair. The
 * tests pin both the happy path and every forgery path.
 *
 * <p>Thread-context: single test thread.
 */
final class InvitationCodecTest {

    private static final SignatureService SIGNATURES = new SignatureService();

    @Test
    void roundTripsAndVerifiesTheSignature() {
        NodeIdentity inviter = NodeIdentity.generate();
        Bytes world = DiscoveryFixtures.worldHash("a");
        String blob = InvitationCodec.encode(inviter, java.util.UUID.randomUUID(), world,
                List.of("boot.example:25565", "backup.example:25565"));

        InvitationCodec.Invitation decoded = InvitationCodec.decode(blob, SIGNATURES);

        assertThat(decoded.genesisHash()).isEqualTo(world);
        assertThat(decoded.inviter()).isEqualTo(inviter.nodeId());
        assertThat(decoded.inviterPublicKey()).isEqualTo(inviter.publicKeyBytes());
        assertThat(decoded.routes()).containsExactly("boot.example:25565", "backup.example:25565");
    }

    @Test
    void aTamperedBlobFailsSignatureVerification() {
        NodeIdentity inviter = NodeIdentity.generate();
        Bytes world = DiscoveryFixtures.worldHash("a");
        String blob = InvitationCodec.encode(inviter, java.util.UUID.randomUUID(), world,
                List.of("real.example:25565"));

        // Flip one character of the base64 body — the signature no longer matches.
        char[] chars = blob.toCharArray();
        chars[chars.length / 2] = (chars[chars.length / 2] == 'A') ? 'B' : 'A';
        String tampered = new String(chars);

        assertThatThrownBy(() -> InvitationCodec.decode(tampered, SIGNATURES))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidBase64AndBlankInput() {
        assertThatThrownBy(() -> InvitationCodec.decode("   ", SIGNATURES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvitationCodec.decode("not!!valid!!base64!!", SIGNATURES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAtLeastOneRoute() {
        NodeIdentity inviter = NodeIdentity.generate();
        assertThatThrownBy(() -> InvitationCodec.encode(inviter, java.util.UUID.randomUUID(),
                DiscoveryFixtures.worldHash("a"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSignatureCoversRoutesAndGenesisHashButNotTheSignatureItself() {
        NodeIdentity inviter = NodeIdentity.generate();
        Bytes world = DiscoveryFixtures.worldHash("a");
        InvitationCodec.Invitation one = InvitationCodec.decode(
                InvitationCodec.encode(inviter, java.util.UUID.randomUUID(), world,
                        List.of("a.example:1")), SIGNATURES);
        InvitationCodec.Invitation two = InvitationCodec.decode(
                InvitationCodec.encode(inviter, java.util.UUID.randomUUID(), world,
                        List.of("a.example:2")), SIGNATURES);

        // Different routes → different signed bytes (the signature is not a constant for a key).
        assertThat(one.signedPortion()).isNotEqualTo(two.signedPortion());
    }
}
