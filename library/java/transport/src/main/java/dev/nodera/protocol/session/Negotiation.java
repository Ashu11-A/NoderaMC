package dev.nodera.protocol.session;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.wire.WireRegistry;

import java.util.Set;
import java.util.UUID;

/**
 * The handshake: ask, answer, and be bound by the answer (Task 14 phase 4, retiring
 * {@code Plan.7} R2 and R3).
 *
 * <p>Nodera shipped four handshake message types and no handshake. Tags 1–4 existed in the codec and
 * in its tests, and no runtime handler ever constructed or consumed one. Production authenticated
 * the carrier, sent {@code PeerJoin}, and admitted the peer — with no comparison of protocol
 * version, rules version, registry fingerprint, or feature set, and with the joiner's own claimed
 * public key trusted as its identity. Every incompatibility therefore had to be <em>experienced</em>
 * rather than reported: a frame that would not parse, a liveness timer that expired, or an exception
 * thrown from inside the region engine minutes later.
 *
 * <p>Three things happen here that could not happen before.
 *
 * <ol>
 *   <li><b>Identity is checked against the transport.</b> The {@code nodeId} in the body must equal
 *       the peer the carrier authenticated. A peer no longer names itself.</li>
 *   <li><b>Features are intersected.</b> The result is the session's emit profile, so a feature the
 *       peer did not accept cannot be sent to it — the writer finally knows who it is writing to.</li>
 *   <li><b>Skew is answered, not suffered.</b> A rules or registry difference yields
 *       {@link SessionRole#OBSERVER} with a coded reason, at the handshake, in one frame.</li>
 * </ol>
 *
 * <p>Thread-context: stateless; any thread.
 */
public final class Negotiation {

    private Negotiation() {}

    /**
     * What this build is, for the purpose of deciding who it can agree with.
     *
     * @param productVersion      human-readable build identity — diagnostics only, never a
     *                            compatibility input.
     * @param rulesVersion        the simulation rule set.
     * @param registryFingerprint the block/entity registry digest.
     * @param networkId           the network this peer belongs to.
     * @param features            the wire features this build supports.
     * @param capabilities        what this peer offers the mesh.
     * @Thread-context immutable; any thread.
     */
    public record LocalProfile(String productVersion,
                               int rulesVersion,
                               long registryFingerprint,
                               UUID networkId,
                               Set<Integer> features,
                               NodeCapabilities capabilities) {

        /** This build's profile with every feature it knows about. */
        public static LocalProfile of(String productVersion, int rulesVersion,
                                      long registryFingerprint, UUID networkId,
                                      NodeCapabilities capabilities) {
            return new LocalProfile(productVersion, rulesVersion, registryFingerprint, networkId,
                    WireFeature.all(), capabilities);
        }
    }

    /**
     * Build and sign this peer's opening message.
     *
     * @param identity this peer's key material.
     * @param local    what this build is.
     * @return the signed hello.
     * @Thread-context any thread.
     */
    public static Hello hello(NodeIdentity identity, LocalProfile local) {
        Hello unsigned = new Hello(WireRegistry.WIRE_EPOCH, local.productVersion(),
                local.features(), local.rulesVersion(), local.registryFingerprint(),
                local.networkId(), identity.nodeId(), identity.publicKeyBytes(),
                local.capabilities(), Bytes.empty());
        return new Hello(unsigned.wireEpoch(), unsigned.productVersion(), unsigned.features(),
                unsigned.rulesVersion(), unsigned.registryFingerprint(), unsigned.networkId(),
                unsigned.nodeId(), unsigned.publicKey(), unsigned.capabilities(),
                identity.sign(unsigned.signedPortion()));
    }

    /**
     * Decide what to do with an incoming {@link Hello}.
     *
     * @param incoming              the peer's opening message.
     * @param local                 what this build is.
     * @param transportAuthenticated the identity the carrier proved, or {@code null} on a carrier
     *                              that does not authenticate. A null here means the identity check
     *                              is skipped, which is a property of the carrier and is stated
     *                              rather than assumed.
     * @param signatures            the verifier.
     * @return the answer to send back.
     * @Thread-context any thread.
     */
    public static HelloAck respond(Hello incoming, LocalProfile local,
                                   NodeId transportAuthenticated, SignatureService signatures) {
        if (incoming.wireEpoch() != WireRegistry.WIRE_EPOCH) {
            return refuse(local, RejectCode.UNSUPPORTED_EPOCH,
                    "epoch " + incoming.wireEpoch() + " != " + WireRegistry.WIRE_EPOCH);
        }
        if (transportAuthenticated != null && !transportAuthenticated.equals(incoming.nodeId())) {
            // A peer used to be able to name itself, and PeerRuntime trusted the key it carried.
            return refuse(local, RejectCode.IDENTITY_MISMATCH,
                    "the carrier authenticated " + transportAuthenticated + ", the body claims "
                            + incoming.nodeId());
        }
        if (!signatures.verify(incoming.publicKey(), incoming.signedPortion(),
                incoming.signature())) {
            return refuse(local, RejectCode.BAD_SIGNATURE, "hello signature does not verify");
        }
        if (!local.networkId().equals(incoming.networkId())) {
            return refuse(local, RejectCode.WRONG_NETWORK,
                    "this is network " + local.networkId());
        }

        Set<Integer> selected = WireFeature.intersect(local.features(), incoming.features());

        // Skew bars co-validation, not communication. An observer still meshes, seeds, relays,
        // tunnels and receives commits; the one thing it cannot do is agree on a state root, which
        // is exactly what a differing rule set or registry makes impossible.
        if (incoming.rulesVersion() != local.rulesVersion()) {
            return new HelloAck(WireRegistry.WIRE_EPOCH, selected, SessionRole.OBSERVER,
                    local.networkId(), RejectCode.RULES_VERSION_MISMATCH,
                    "rules version " + incoming.rulesVersion() + " != " + local.rulesVersion());
        }
        if (incoming.registryFingerprint() != local.registryFingerprint()) {
            return new HelloAck(WireRegistry.WIRE_EPOCH, selected, SessionRole.OBSERVER,
                    local.networkId(), RejectCode.REGISTRY_FINGERPRINT_MISMATCH,
                    "registry fingerprint differs");
        }
        for (WireFeature consensusFeature : WireFeature.consensusFeatures()) {
            if (!selected.contains(consensusFeature.code())) {
                return new HelloAck(WireRegistry.WIRE_EPOCH, selected, SessionRole.OBSERVER,
                        local.networkId(), RejectCode.RULES_VERSION_MISMATCH,
                        "consensus feature " + consensusFeature + " is not shared");
            }
        }

        return new HelloAck(WireRegistry.WIRE_EPOCH, selected, SessionRole.ADMITTED,
                local.networkId(), RejectCode.NONE, "");
    }

    private static HelloAck refuse(LocalProfile local, RejectCode code, String detail) {
        return new HelloAck(WireRegistry.WIRE_EPOCH, Set.of(), SessionRole.REFUSED,
                local.networkId(), code, detail);
    }

    /**
     * Turn a received answer into the session profile that governs everything sent afterwards.
     *
     * @param peer the authenticated peer.
     * @param ack  the answer.
     * @return the session.
     * @throws IllegalStateException if the peer refused. A refusal is a coded statement, so a caller
     *         that ignores it is choosing to; there is no silent path.
     * @Thread-context any thread.
     */
    public static PeerSession accept(NodeId peer, HelloAck ack) {
        if (ack.refused()) {
            throw new IllegalStateException("peer " + peer + " refused the handshake: "
                    + ack.reject() + (ack.detail().isEmpty() ? "" : " — " + ack.detail()));
        }
        return PeerSession.of(peer, ack);
    }

}
