package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-20's exit: multi-party genesis re-certification signed by the founding peer set — a strict
 * majority of distinct founders endorses the genesis root; forged, duplicate, outsider, or
 * under-quorum approval sets never verify; the endorsement round-trips canonically (tag 104).
 */
final class GenesisRecertificationTest {

    private final SignatureService signatures = new SignatureService();
    private final StateRoot root =
            StateRoot.of(new HashService().sha256("genesis".getBytes()));

    private static GenesisRecertification.Founder founder(NodeIdentity id) {
        return new GenesisRecertification.Founder(id.nodeId(), id.publicKeyBytes());
    }

    @Test
    void majorityOfFoundersRecertifiesAndRoundTrips() {
        NodeIdentity a = NodeIdentity.generate();
        NodeIdentity b = NodeIdentity.generate();
        NodeIdentity c = NodeIdentity.generate();
        List<GenesisRecertification.Founder> founders =
                List.of(founder(a), founder(b), founder(c));

        GenesisRecertification cert = new GenesisRecertification(root, founders, List.of(
                GenesisRecertification.approve(root, founders, a),
                GenesisRecertification.approve(root, founders, b)));

        assertThat(cert.quorumThreshold()).isEqualTo(2);
        assertThat(cert.verify(signatures)).isTrue();

        CanonicalWriter w = new CanonicalWriter();
        cert.encode(w);
        GenesisRecertification decoded =
                GenesisRecertification.decode(new CanonicalReader(w.toByteArray()));
        assertThat(decoded).isEqualTo(cert);
        assertThat(decoded.verify(signatures)).isTrue();
    }

    @Test
    void underQuorumOutsiderDuplicateAndForgedApprovalsNeverVerify() {
        NodeIdentity a = NodeIdentity.generate();
        NodeIdentity b = NodeIdentity.generate();
        NodeIdentity c = NodeIdentity.generate();
        NodeIdentity outsider = NodeIdentity.generate();
        List<GenesisRecertification.Founder> founders =
                List.of(founder(a), founder(b), founder(c));

        GenesisRecertification.Approval good =
                GenesisRecertification.approve(root, founders, a);

        // One approval (< 2-of-3).
        assertThat(new GenesisRecertification(root, founders, List.of(good))
                .verify(signatures)).isFalse();
        // Outsider approval never counts.
        assertThat(new GenesisRecertification(root, founders, List.of(
                good, GenesisRecertification.approve(root, founders, outsider)))
                .verify(signatures)).isFalse();
        // The same founder approving twice counts once.
        assertThat(new GenesisRecertification(root, founders, List.of(good, good))
                .verify(signatures)).isFalse();
        // A forged signature never counts.
        assertThat(new GenesisRecertification(root, founders, List.of(
                good, new GenesisRecertification.Approval(b.nodeId(), Bytes.fromHex("00ff"))))
                .verify(signatures)).isFalse();
    }

    @Test
    void approvalsBindTheCompleteFoundingSet() {
        NodeIdentity a = NodeIdentity.generate();
        NodeIdentity b = NodeIdentity.generate();
        NodeIdentity c = NodeIdentity.generate();
        List<GenesisRecertification.Founder> declared =
                List.of(founder(a), founder(b), founder(c));
        List<GenesisRecertification.Founder> redeclared = List.of(founder(a), founder(b));

        // Approvals signed under the 3-founder set do NOT verify under a re-declared 2-founder
        // set: no subset can shrink the founding set to lower the quorum bar.
        GenesisRecertification shrunk = new GenesisRecertification(root, redeclared, List.of(
                GenesisRecertification.approve(root, declared, a),
                GenesisRecertification.approve(root, declared, b)));
        assertThat(shrunk.verify(signatures)).isFalse();
    }
}
