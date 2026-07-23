package dev.nodera.core.consensuscert;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
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

    @Test
    void transitionRootIsSignedAndRoundTrips() {
        StateRoot transition = StateRoot.of(Bytes.fromHex(
                "f00102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"));
        SignedVote vote = new SignedVote(
                new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                ROOT, transition, VoteDecision.ACCEPT, Bytes.fromHex("abcd"));
        SignedVote decoded = SignedVote.decode(new CanonicalReader(encode(vote)));
        assertThat(decoded.transitionRoot()).isEqualTo(transition);
        assertThat(decoded.signedPortion()).isNotEqualTo(sample().signedPortion());
    }

    @Test
    void legacyVoteRetainsVersionOneSignedBytes() {
        SignedVote legacy = sample();
        byte[] encoded = encode(legacy);
        SignedVote decoded = SignedVote.decode(new CanonicalReader(encoded));
        assertThat(decoded.bodyVersion()).isEqualTo(1);
        assertThat(encode(decoded)).isEqualTo(encoded);
        assertThat(decoded.signedPortion()).isEqualTo(legacy.signedPortion());
    }

    @Test
    void currentVoteBindsProposalAnchor() {
        NodeId voter = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        RegionId region = new RegionId(DimensionKey.overworld(), 2, 3);
        SignedVote vote = new SignedVote(
                voter, region, new RegionEpoch(4), new SnapshotVersion(5),
                ROOT, ROOT, ROOT, VoteDecision.ACCEPT, Bytes.fromHex("abcd"));
        SignedVote decoded = SignedVote.decode(new CanonicalReader(encode(vote)));
        SignedVote otherEpoch = new SignedVote(
                voter, region, new RegionEpoch(6), new SnapshotVersion(5),
                ROOT, ROOT, ROOT, VoteDecision.ACCEPT, Bytes.fromHex("abcd"));
        assertThat(decoded).isEqualTo(vote);
        assertThat(decoded.bodyVersion()).isEqualTo(SignedVote.VOTE_ENCODING_VERSION);
        assertThat(otherEpoch.signedPortion()).isNotEqualTo(vote.signedPortion());
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
