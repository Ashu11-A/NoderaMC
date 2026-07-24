package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.GenesisRecertification;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-20 share-flow (Task 16): founder approvals collected over the transport upgrade the genesis
 * trust root from the author's single signature to a strict-majority multi-party certificate.
 * The host self-approves, asks each founder as it joins, and assembles the quorum; a founder
 * only ever signs the root it can vouch for; outsiders, duplicates, and forgeries never count.
 */
final class GenesisApprovalFlowIT {

    private final HashService hashes = new HashService();
    private final SignatureService signatures = new SignatureService();

    private static StateRoot rootOf(HashService hashes, String seed) {
        return StateRoot.of(hashes.sha256(seed.getBytes()));
    }

    @Test
    void founderApprovalsFlowOverTheTransportUntilTheQuorumCertifies() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity host = NodeIdentity.generate();
        NodeIdentity f1 = NodeIdentity.generate();
        NodeIdentity f2 = NodeIdentity.generate();
        StateRoot genesis = rootOf(hashes, "the-shared-world-genesis");
        List<GenesisRecertification.Founder> founders = List.of(
                new GenesisRecertification.Founder(host.nodeId(), host.publicKeyBytes()),
                new GenesisRecertification.Founder(f1.nodeId(), f1.publicKeyBytes()),
                new GenesisRecertification.Founder(f2.nodeId(), f2.publicKeyBytes()));

        LoopbackTransport hostTx = net.register(host.nodeId());
        LoopbackTransport f1Tx = net.register(f1.nodeId());
        LoopbackTransport f2Tx = net.register(f2.nodeId());

        GenesisApprovalFlow hostFlow = new GenesisApprovalFlow(
                host, hostTx, signatures, genesis::equals);
        GenesisApprovalFlow f1Flow = new GenesisApprovalFlow(
                f1, f1Tx, signatures, genesis::equals);
        // f2 joined a DIFFERENT world: it must refuse to endorse this genesis.
        GenesisApprovalFlow f2Flow = new GenesisApprovalFlow(
                f2, f2Tx, signatures, rootOf(hashes, "some-other-world")::equals);

        hostTx.setHandler(handlerFor(hostFlow));
        f1Tx.setHandler(handlerFor(f1Flow));
        f2Tx.setHandler(handlerFor(f2Flow));
        hostTx.start();
        f1Tx.start();
        f2Tx.start();

        hostFlow.begin(genesis, founders);
        assertThat(hostFlow.approvalCount())
                .as("the host founder self-approves at begin").isEqualTo(1);
        assertThat(hostFlow.certified())
                .as("one signature of three is below the strict majority").isEmpty();

        hostFlow.requestFrom(PeerAddress.of(f1.nodeId(), "loopback"));
        hostFlow.requestFrom(PeerAddress.of(f2.nodeId(), "loopback"));

        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && hostFlow.certified().isEmpty()) {
            Thread.sleep(20);
        }

        GenesisRecertification cert = hostFlow.certified().orElseThrow();
        assertThat(cert.verify(signatures))
                .as("the assembled certificate is self-verifying").isTrue();
        assertThat(hostFlow.approvalCount())
                .as("host + f1 signed; f2 (different world) correctly refused")
                .isEqualTo(2);
    }

    @Test
    void outsiderForgedAndDuplicateGrantsNeverCount() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity host = NodeIdentity.generate();
        NodeIdentity f1 = NodeIdentity.generate();
        NodeIdentity outsider = NodeIdentity.generate();
        StateRoot genesis = rootOf(hashes, "world");
        List<GenesisRecertification.Founder> founders = List.of(
                new GenesisRecertification.Founder(host.nodeId(), host.publicKeyBytes()),
                new GenesisRecertification.Founder(f1.nodeId(), f1.publicKeyBytes()));

        LoopbackTransport hostTx = net.register(host.nodeId());
        hostTx.start();
        GenesisApprovalFlow hostFlow = new GenesisApprovalFlow(
                host, hostTx, signatures, genesis::equals);
        hostFlow.begin(genesis, founders);
        PeerAddress self = PeerAddress.of(host.nodeId(), "loopback");

        // An outsider's grant (not in the declared set) never counts.
        hostFlow.onMessage(self, new dev.nodera.protocol.simulationmsg.GenesisApprovalGrant(
                genesis, outsider.nodeId(), outsider.sign(Bytes.unsafeWrap(new byte[]{1}))));
        // A forged signature under a declared founder's id never counts.
        hostFlow.onMessage(self, new dev.nodera.protocol.simulationmsg.GenesisApprovalGrant(
                genesis, f1.nodeId(), outsider.sign(Bytes.unsafeWrap(new byte[]{2}))));
        assertThat(hostFlow.approvalCount()).isEqualTo(1); // still just the self-approval

        // The genuine grant counts exactly once, even if replayed.
        GenesisRecertification.Approval genuine =
                GenesisRecertification.approve(genesis, founders, f1);
        var grant = new dev.nodera.protocol.simulationmsg.GenesisApprovalGrant(
                genesis, f1.nodeId(), genuine.signature());
        hostFlow.onMessage(self, grant);
        hostFlow.onMessage(self, grant);
        assertThat(hostFlow.approvalCount()).isEqualTo(2);
        assertThat(hostFlow.certified()).isPresent(); // 2-of-2 ≥ strict majority of 2
    }

    private static dev.nodera.transport.MessageHandler handlerFor(GenesisApprovalFlow flow) {
        return new dev.nodera.transport.MessageHandler() {
            @Override
            public void onMessage(PeerAddress from, byte[] frame) {
                flow.onMessage(from, MessageCodec.decode(frame));
            }

            @Override
            public void onPeerDown(PeerAddress peer) {
            }
        };
    }
}
