package dev.nodera.core.identity;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A delegation says one thing — "this session key speaks for me, in this world, until then" — and
 * these tests are the four ways that sentence can be false.
 */
final class SessionDelegationTest {

    private static final Bytes WORLD = Bytes.unsafeWrap(new byte[]{1, 2, 3, 4});
    private static final Bytes OTHER_WORLD = Bytes.unsafeWrap(new byte[]{9, 9, 9, 9});
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void aFreshDelegationVouchesForTheSessionKeyItNames() {
        NodeIdentity worker = NodeIdentity.generate();
        NodeIdentity session = NodeIdentity.generate();

        SessionDelegation delegation = SessionDelegation.create(
                worker, session.publicKeyBytes(), WORLD, NOW + 1000);

        assertThat(delegation.isValidFor(session.publicKeyBytes(), WORLD, NOW)).isTrue();
        assertThat(delegation.workerNodeId()).isEqualTo(worker.nodeId());
        assertThat(delegation.workerPublicKey()).isEqualTo(worker.publicKeyBytes());
    }

    @Test
    void aDelegationDoesNotVouchForADifferentSessionKey() {
        NodeIdentity worker = NodeIdentity.generate();
        NodeIdentity session = NodeIdentity.generate();
        NodeIdentity attacker = NodeIdentity.generate();

        SessionDelegation delegation = SessionDelegation.create(
                worker, session.publicKeyBytes(), WORLD, NOW + 1000);

        // Lifting a delegation off somebody else's announce and presenting it with your own key is
        // the obvious attack, and the session key being inside the signed bytes is what stops it.
        assertThat(delegation.isValidFor(attacker.publicKeyBytes(), WORLD, NOW)).isFalse();
    }

    @Test
    void aDelegationIsInertInAWorldItWasNotMintedFor() {
        NodeIdentity worker = NodeIdentity.generate();
        NodeIdentity session = NodeIdentity.generate();

        SessionDelegation delegation = SessionDelegation.create(
                worker, session.publicKeyBytes(), WORLD, NOW + 1000);

        assertThat(delegation.isValidFor(session.publicKeyBytes(), OTHER_WORLD, NOW)).isFalse();
    }

    @Test
    void anExpiredDelegationVouchesForNothing() {
        NodeIdentity worker = NodeIdentity.generate();
        NodeIdentity session = NodeIdentity.generate();

        SessionDelegation delegation = SessionDelegation.create(
                worker, session.publicKeyBytes(), WORLD, NOW - 1);

        assertThat(delegation.isValidFor(session.publicKeyBytes(), WORLD, NOW)).isFalse();
    }

    @Test
    void aDelegationSignedWithTheWrongKeyIsRefused() {
        NodeIdentity worker = NodeIdentity.generate();
        NodeIdentity attacker = NodeIdentity.generate();
        NodeIdentity session = NodeIdentity.generate();

        // The attacker signs, then claims the worker's identity in the fields. Verification uses the
        // key IN the record, so this only proves the attacker signed their own claim — and the
        // world's permission set has never heard of the attacker's key.
        SessionDelegation forged = SessionDelegation.create(
                attacker, session.publicKeyBytes(), WORLD, NOW + 1000);
        SessionDelegation relabelled = new SessionDelegation(worker.nodeId(),
                worker.publicKeyBytes(), session.publicKeyBytes(), WORLD, NOW + 1000,
                forged.signature());

        assertThat(relabelled.verifySignature()).isFalse();
        assertThat(relabelled.isValidFor(session.publicKeyBytes(), WORLD, NOW)).isFalse();
    }

    @Test
    void itRoundTripsThroughItsCanonicalEncoding() {
        NodeIdentity worker = NodeIdentity.generate();
        NodeIdentity session = NodeIdentity.generate();
        SessionDelegation delegation = SessionDelegation.create(
                worker, session.publicKeyBytes(), WORLD, NOW + 1000);

        CanonicalWriter w = new CanonicalWriter();
        delegation.encode(w);
        SessionDelegation decoded = SessionDelegation.decode(new CanonicalReader(w.toBytes()));

        assertThat(decoded).isEqualTo(delegation);
        assertThat(decoded.isValidFor(session.publicKeyBytes(), WORLD, NOW)).isTrue();
    }

    @Test
    void itRefusesToExistWithoutASessionKeyOrAWorld() {
        NodeIdentity worker = NodeIdentity.generate();
        NodeIdentity session = NodeIdentity.generate();

        assertThatThrownBy(() -> new SessionDelegation(worker.nodeId(), worker.publicKeyBytes(),
                Bytes.empty(), WORLD, NOW, Bytes.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionDelegation(worker.nodeId(), worker.publicKeyBytes(),
                session.publicKeyBytes(), Bytes.empty(), NOW, Bytes.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
