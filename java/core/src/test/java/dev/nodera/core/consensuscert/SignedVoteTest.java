package dev.nodera.core.consensuscert;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SignedVote} signature-exclusion and round-trip checks (Task 2). {@code signedPortion}
 * MUST be a strict prefix of {@code encode} and MUST NOT contain the signature bytes.
 */
final class SignedVoteTest {

    private static final StateRoot ROOT = StateRoot.of(Bytes.fromHex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"));

    @Test
    void signedPortionExcludesSignature() {
        SignedVote vote = sample();
        Bytes signed = vote.signedPortion();
        assertThat(signed.toHex()).doesNotContain(vote.signature().toHex());
    }

    @Test
    void signedPortionIsStrictPrefixOfEncode() {
        SignedVote vote = sample();
        byte[] signed = vote.signedPortion().toArray();
        byte[] full = encode(vote);
        assertThat(full.length).isGreaterThan(signed.length);
        for (int i = 0; i < signed.length; i++) {
            assertThat(full[i]).isEqualTo(signed[i]);
        }
    }

    @Test
    void encodeDecodeRoundTrip() {
        SignedVote vote = sample();
        SignedVote decoded = SignedVote.decode(new CanonicalReader(encode(vote)));
        assertThat(decoded).isEqualTo(vote);
    }

    @Test
    void encodeDecodeAllDecisions() {
        for (VoteDecision d : VoteDecision.values()) {
            SignedVote vote = new SignedVote(
                    new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                    ROOT, d, Bytes.fromHex("abcd"));
            SignedVote decoded = SignedVote.decode(new CanonicalReader(encode(vote)));
            assertThat(decoded).isEqualTo(vote);
        }
    }

    private static SignedVote sample() {
        byte[] sig = new byte[64];
        for (int i = 0; i < sig.length; i++) {
            sig[i] = (byte) (0x10 + i);
        }
        return new SignedVote(
                new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                ROOT, VoteDecision.ACCEPT, new Bytes(sig));
    }

    private static byte[] encode(SignedVote vote) {
        CanonicalWriter w = new CanonicalWriter();
        vote.encode(w);
        return w.toByteArray();
    }
}
