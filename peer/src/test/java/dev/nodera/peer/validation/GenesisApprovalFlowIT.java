package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.simulationmsg.GenesisApprovalGrant;
import dev.nodera.storage.GenesisRecertification;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.MeshNode;
import dev.nodera.testkit.peer.PeerTestHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-20 share-flow (Task 16): founder approvals collected over the transport upgrade the genesis
 * trust root from the author's single signature to a strict-majority multi-party certificate.
 * The host self-approves, asks each founder as it joins, and assembles the quorum; a founder
 * only ever signs the root it can vouch for; outsiders, duplicates, and forgeries never count.
 */
final class GenesisApprovalFlowIT {

    private final PeerTestHarness harness = PeerTestHarness.create();
    private final HashService hashes = harness.hashes();
    private final SignatureService signatures = new SignatureService();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private StateRoot rootOf(String seed) {
        return StateRoot.of(hashes.sha256(seed.getBytes()));
    }

    /** A founder node: the approval flow, wired to answer whatever arrives on its transport. */
    private MeshNode<GenesisApprovalFlow> founder(NodeIdentity identity,
                                                  Predicate<StateRoot> vouchesFor) {
        return harness.messageNode(identity,
                (id, transport, peers) ->
                        new GenesisApprovalFlow(id, transport, signatures, vouchesFor),
                flow -> flow::onMessage);
    }

    @Test
    void founderApprovalsFlowOverTheTransportUntilTheQuorumCertifies() {
        NodeIdentity hostId = NodeIdentity.generate();
        NodeIdentity f1Id = NodeIdentity.generate();
        NodeIdentity f2Id = NodeIdentity.generate();
        StateRoot genesis = rootOf("the-shared-world-genesis");
        List<GenesisRecertification.Founder> founders = List.of(
                new GenesisRecertification.Founder(hostId.nodeId(), hostId.publicKeyBytes()),
                new GenesisRecertification.Founder(f1Id.nodeId(), f1Id.publicKeyBytes()),
                new GenesisRecertification.Founder(f2Id.nodeId(), f2Id.publicKeyBytes()));

        MeshNode<GenesisApprovalFlow> host = founder(hostId, genesis::equals);
        MeshNode<GenesisApprovalFlow> f1 = founder(f1Id, genesis::equals);
        // f2 joined a DIFFERENT world: it must refuse to endorse this genesis.
        MeshNode<GenesisApprovalFlow> f2 =
                founder(f2Id, rootOf("some-other-world")::equals);

        GenesisApprovalFlow hostFlow = host.service();
        hostFlow.begin(genesis, founders);
        assertThat(hostFlow.approvalCount())
                .as("the host founder self-approves at begin").isEqualTo(1);
        assertThat(hostFlow.certified())
                .as("one signature of three is below the strict majority").isEmpty();

        hostFlow.requestFrom(f1.address());
        hostFlow.requestFrom(f2.address());

        Await.quietly(5_000, () -> hostFlow.certified().isPresent());

        GenesisRecertification cert = hostFlow.certified().orElseThrow();
        assertThat(cert.verify(signatures))
                .as("the assembled certificate is self-verifying").isTrue();
        assertThat(hostFlow.approvalCount())
                .as("host + f1 signed; f2 (different world) correctly refused")
                .isEqualTo(2);
    }

    @Test
    void outsiderForgedAndDuplicateGrantsNeverCount() {
        NodeIdentity hostId = NodeIdentity.generate();
        NodeIdentity f1Id = NodeIdentity.generate();
        NodeIdentity outsider = NodeIdentity.generate();
        StateRoot genesis = rootOf("world");
        List<GenesisRecertification.Founder> founders = List.of(
                new GenesisRecertification.Founder(hostId.nodeId(), hostId.publicKeyBytes()),
                new GenesisRecertification.Founder(f1Id.nodeId(), f1Id.publicKeyBytes()));

        MeshNode<GenesisApprovalFlow> host = founder(hostId, genesis::equals);
        GenesisApprovalFlow hostFlow = host.service();
        hostFlow.begin(genesis, founders);

        // An outsider's grant (not in the declared set) never counts.
        hostFlow.onMessage(host.address(), new GenesisApprovalGrant(
                genesis, outsider.nodeId(), outsider.sign(Bytes.unsafeWrap(new byte[]{1}))));
        // A forged signature under a declared founder's id never counts.
        hostFlow.onMessage(host.address(), new GenesisApprovalGrant(
                genesis, f1Id.nodeId(), outsider.sign(Bytes.unsafeWrap(new byte[]{2}))));
        assertThat(hostFlow.approvalCount()).isEqualTo(1); // still just the self-approval

        // The genuine grant counts exactly once, even if replayed.
        GenesisRecertification.Approval genuine =
                GenesisRecertification.approve(genesis, founders, f1Id);
        var grant = new GenesisApprovalGrant(genesis, f1Id.nodeId(), genuine.signature());
        hostFlow.onMessage(host.address(), grant);
        hostFlow.onMessage(host.address(), grant);
        assertThat(hostFlow.approvalCount()).isEqualTo(2);
        assertThat(hostFlow.certified()).isPresent(); // 2-of-2 ≥ strict majority of 2
    }
}
