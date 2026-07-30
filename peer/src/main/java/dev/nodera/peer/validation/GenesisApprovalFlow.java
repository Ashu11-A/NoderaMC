package dev.nodera.peer.validation;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.simulationmsg.GenesisApprovalGrant;
import dev.nodera.protocol.simulationmsg.GenesisApprovalRequest;
import dev.nodera.storage.GenesisRecertification;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The L-20 share-flow: collects founding-peer genesis endorsements over the transport until the
 * {@link GenesisRecertification} quorum (strict majority of the DECLARED founding set) is met —
 * upgrading the world's trust root from the author's single signature to a multi-party one.
 *
 * <p><b>Host side</b>: {@link #begin} pins the root + founding set and self-approves when this
 * node is itself a founder; {@link #requestFrom} asks one founder (call it as founders join the
 * mesh); {@link #onMessage} ingests grants — each is verified against the DECLARED founder key
 * over the canonical signed portion before it counts, and duplicates/outsiders/bad signatures
 * never count. {@link #certified} yields the quorum-complete, self-verifying certificate.
 *
 * <p><b>Founder side</b>: {@link #onMessage} answers a request with a signature ONLY when the
 * request's root passes the {@code endorses} predicate (a founder signs the genesis it actually
 * joined on, never whatever a host claims) and this node is in the request's declared set.
 *
 * @Thread-context confine to the runtime's message thread (like the other validation services).
 */
public final class GenesisApprovalFlow {

    private final NodeIdentity identity;
    private final PeerTransport transport;
    private final SignatureService signatures;
    private final Predicate<StateRoot> endorses;

    private StateRoot root;
    private List<GenesisRecertification.Founder> founders = List.of();
    private final Map<NodeId, GenesisRecertification.Approval> approvals = new LinkedHashMap<>();

    /**
     * @param identity this node's identity (signer when it is a founder).
     * @param transport the peer transport grants/requests travel over.
     * @param signatures the signature verifier.
     * @param endorses whether THIS node vouches for a requested genesis root (typically: equals
     *                 the certified genesis root it joined on). A founder that cannot check the
     *                 root must refuse — fail closed.
     */
    public GenesisApprovalFlow(NodeIdentity identity, PeerTransport transport,
                               SignatureService signatures, Predicate<StateRoot> endorses) {
        this.identity = identity;
        this.transport = transport;
        this.signatures = signatures;
        this.endorses = endorses;
    }

    /** Host side: pin the root + declared founding set, and self-approve if we are a founder. */
    public void begin(StateRoot genesisRoot, List<GenesisRecertification.Founder> foundingSet) {
        this.root = genesisRoot;
        this.founders = List.copyOf(foundingSet);
        this.approvals.clear();
        if (founders.stream().anyMatch(f -> f.nodeId().equals(identity.nodeId()))) {
            GenesisRecertification.Approval self =
                    GenesisRecertification.approve(genesisRoot, founders, identity);
            approvals.put(self.nodeId(), self);
        }
    }

    /** Host side: ask one founding peer for its endorsement (call as founders join the mesh). */
    public void requestFrom(PeerAddress founder) {
        requireBegun();
        List<GenesisApprovalRequest.FounderEntry> entries = new ArrayList<>(founders.size());
        for (GenesisRecertification.Founder f : founders) {
            entries.add(new GenesisApprovalRequest.FounderEntry(f.nodeId(), f.publicKey()));
        }
        send(founder, new GenesisApprovalRequest(root, entries));
    }

    /** Both sides' message entry point — attach alongside the other application handlers. */
    public void onMessage(PeerAddress from, NoderaMessage message) {
        if (message instanceof GenesisApprovalRequest request) { onRequest(from, request);
        } else if (message instanceof GenesisApprovalGrant grant) { onGrant(grant);
        } else { /* not a genesis message */ }
    }

    /** @return the quorum-complete certificate once a strict founder majority has signed. */
    public Optional<GenesisRecertification> certified() {
        if (root == null || founders.isEmpty()) {
            return Optional.empty();
        }
        GenesisRecertification candidate =
                new GenesisRecertification(root, founders, List.copyOf(approvals.values()));
        return candidate.verify(signatures) ? Optional.of(candidate) : Optional.empty();
    }

    /** @return how many distinct declared founders have verifiably signed so far. */
    public int approvalCount() {
        return approvals.size();
    }

    private void onRequest(PeerAddress from, GenesisApprovalRequest request) {
        boolean declared = request.founders().stream()
                .anyMatch(f -> f.nodeId().equals(identity.nodeId()));
        if (!declared || !endorses.test(request.genesisRoot())) {
            // Not our set, or a root we cannot vouch for: stay silent — fail closed.
            return;
        }
        List<GenesisRecertification.Founder> requested = new ArrayList<>(request.founders().size());
        for (GenesisApprovalRequest.FounderEntry e : request.founders()) {
            requested.add(new GenesisRecertification.Founder(e.nodeId(), e.publicKey()));
        }
        GenesisRecertification.Approval approval =
                GenesisRecertification.approve(request.genesisRoot(), requested, identity);
        send(from, new GenesisApprovalGrant(
                request.genesisRoot(), approval.nodeId(), approval.signature()));
    }

    private void onGrant(GenesisApprovalGrant grant) {
        if (root == null || !root.equals(grant.genesisRoot())) {
            return;
        }
        GenesisRecertification.Founder declared = founders.stream()
                .filter(f -> f.nodeId().equals(grant.founder()))
                .findFirst().orElse(null);
        if (declared == null || approvals.containsKey(grant.founder())) {
            return; // outsider or duplicate — never counts
        }
        GenesisRecertification unsigned = new GenesisRecertification(root, founders, List.of());
        if (!signatures.verify(declared.publicKey(), unsigned.signedPortion(), grant.signature())) {
            return; // forged — never counts
        }
        approvals.put(grant.founder(),
                new GenesisRecertification.Approval(grant.founder(), grant.signature()));
    }

    private void requireBegun() {
        if (root == null || founders.isEmpty()) {
            throw new IllegalStateException("begin(root, founders) must run before requests");
        }
    }

    private void send(PeerAddress to, NoderaMessage msg) {
        try {
            transport.send(to, WireCodec.encode(msg));
        } catch (TransportException unreachable) {
            // An unreachable founder is an absent approval; the quorum tolerates a minority.
        }
    }
}
