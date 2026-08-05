package dev.nodera.endpoint.world;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.SessionDelegation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which identity an announcing session speaks for. The failure this covers is not exotic: for the
 * whole life of the feature every session spoke only for a key generated moments earlier, so the
 * permission set answered {@code MEMBER} for everybody who had ever been granted anything —
 * including a world's own author.
 */
final class SessionAuthorityTest {

    private static final Bytes WORLD = Bytes.unsafeWrap(new byte[]{7, 7, 7, 7});
    private static final String ROUTE = "10.0.0.5:25620";
    private static final long NOW = 1_700_000_000_000L;

    private static String encode(SessionDelegation delegation) {
        CanonicalWriter w = new CanonicalWriter();
        delegation.encode(w);
        return Base64.getEncoder().encodeToString(w.toBytes().toArray());
    }

    @Test
    void withNoDelegationASessionSpeaksOnlyForItself() {
        NodeIdentity session = NodeIdentity.generate();

        PlayerNodeRegistry.PlayerNode node = SessionAuthority.resolve(session.nodeId(),
                session.publicKeyBytes(), ROUTE, "", WORLD, NOW, null);

        assertThat(node.isDelegated()).isFalse();
        assertThat(node.authorityPublicKey()).isEqualTo(session.publicKeyBytes());
        assertThat(node.authorityNodeId()).isEqualTo(session.nodeId());
    }

    @Test
    void aValidDelegationMakesTheWorkerIdentityTheAuthority() {
        NodeIdentity worker = NodeIdentity.generate();
        NodeIdentity session = NodeIdentity.generate();
        String delegation = encode(SessionDelegation.create(
                worker, session.publicKeyBytes(), WORLD, NOW + 60_000));

        PlayerNodeRegistry.PlayerNode node = SessionAuthority.resolve(session.nodeId(),
                session.publicKeyBytes(), ROUTE, delegation, WORLD, NOW, null);

        assertThat(node.isDelegated()).isTrue();
        assertThat(node.authorityNodeId()).isEqualTo(worker.nodeId());
        assertThat(node.authorityPublicKey()).isEqualTo(worker.publicKeyBytes());
        // The session's own identity is untouched — it is still what the mesh dials.
        assertThat(node.nodeId()).isEqualTo(session.nodeId());
        assertThat(node.publicKey()).isEqualTo(session.publicKeyBytes());
    }

    @Test
    void aDelegationForAnotherSessionKeyIsIgnoredAndReported() {
        NodeIdentity worker = NodeIdentity.generate();
        NodeIdentity someoneElse = NodeIdentity.generate();
        NodeIdentity session = NodeIdentity.generate();
        String stolen = encode(SessionDelegation.create(
                worker, someoneElse.publicKeyBytes(), WORLD, NOW + 60_000));
        List<String> notes = new ArrayList<>();

        PlayerNodeRegistry.PlayerNode node = SessionAuthority.resolve(session.nodeId(),
                session.publicKeyBytes(), ROUTE, stolen, WORLD, NOW, notes::add);

        assertThat(node.isDelegated()).isFalse();
        assertThat(notes).hasSize(1);
    }

    @Test
    void aDelegationForAnotherWorldIsIgnored() {
        NodeIdentity worker = NodeIdentity.generate();
        NodeIdentity session = NodeIdentity.generate();
        String elsewhere = encode(SessionDelegation.create(worker, session.publicKeyBytes(),
                Bytes.unsafeWrap(new byte[]{1, 1, 1, 1}), NOW + 60_000));

        PlayerNodeRegistry.PlayerNode node = SessionAuthority.resolve(session.nodeId(),
                session.publicKeyBytes(), ROUTE, elsewhere, WORLD, NOW, null);

        assertThat(node.isDelegated()).isFalse();
    }

    @Test
    void anExpiredDelegationIsIgnored() {
        NodeIdentity worker = NodeIdentity.generate();
        NodeIdentity session = NodeIdentity.generate();
        String stale = encode(SessionDelegation.create(
                worker, session.publicKeyBytes(), WORLD, NOW - 1));

        PlayerNodeRegistry.PlayerNode node = SessionAuthority.resolve(session.nodeId(),
                session.publicKeyBytes(), ROUTE, stale, WORLD, NOW, null);

        assertThat(node.isDelegated()).isFalse();
    }

    @Test
    void garbageInTheDelegationSlotDoesNotBreakTheAnnounce() {
        NodeIdentity session = NodeIdentity.generate();
        List<String> notes = new ArrayList<>();

        PlayerNodeRegistry.PlayerNode node = SessionAuthority.resolve(session.nodeId(),
                session.publicKeyBytes(), ROUTE, "not base64 at all !!", WORLD, NOW, notes::add);

        assertThat(node.isDelegated()).isFalse();
        assertThat(notes).hasSize(1);
    }

    @Test
    void anUnsharedWorldAcceptsNoDelegationAtAll() {
        NodeIdentity worker = NodeIdentity.generate();
        NodeIdentity session = NodeIdentity.generate();
        String delegation = encode(SessionDelegation.create(
                worker, session.publicKeyBytes(), WORLD, NOW + 60_000));

        // No world id means nothing to scope a delegation to, so accepting one would be accepting a
        // claim about a world this host cannot name.
        PlayerNodeRegistry.PlayerNode node = SessionAuthority.resolve(session.nodeId(),
                session.publicKeyBytes(), ROUTE, delegation, Bytes.empty(), NOW, null);

        assertThat(node.isDelegated()).isFalse();
    }
}
