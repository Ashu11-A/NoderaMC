package dev.nodera.peer.discovery;

import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PersistedNodeIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A returning peer keeps its {@code NodeId} (retires L-28). The store must round-trip, create
 * owner-only files, and never truncate an existing identity mid-write.
 *
 * <p>Thread-context: single test thread.
 */
final class PersistentIdentityStoreTest {

    @Test
    void generatesOnFirstRunAndReloadsTheSameIdentityOnSubsequentRuns(@TempDir Path dir) {
        PersistentIdentityStore store = new PersistentIdentityStore(dir.resolve("identity.bin"));

        NodeIdentity first = store.loadOrGenerate();
        Optional<NodeIdentity> absentBefore = store.load();
        assertThat(absentBefore).isPresent();   // the first run just wrote it

        // A fresh store over the same file must reproduce the SAME identity — the whole point of
        // persistence (a returning peer is not a stranger).
        PersistentIdentityStore reopened = new PersistentIdentityStore(dir.resolve("identity.bin"));
        NodeIdentity again = reopened.loadOrGenerate();

        assertThat(again.nodeId()).isEqualTo(first.nodeId());
        assertThat(again.publicKeyBytes()).isEqualTo(first.publicKeyBytes());
    }

    @Test
    void roundTripsAnExplicitlyPersistedIdentity(@TempDir Path dir) {
        Path file = dir.resolve("nested/deep/identity.bin");
        PersistentIdentityStore store = new PersistentIdentityStore(file);

        PersistedNodeIdentity.Generated fresh = PersistedNodeIdentity.generate();
        store.save(fresh.persisted());

        NodeIdentity restored = store.load().orElseThrow();
        assertThat(restored.nodeId()).isEqualTo(fresh.identity().nodeId());
        assertThat(restored.publicKeyBytes()).isEqualTo(fresh.identity().publicKeyBytes());
    }

    @Test
    void loadReturnsEmptyBeforeFirstWrite(@TempDir Path dir) {
        PersistentIdentityStore store = new PersistentIdentityStore(dir.resolve("absent.bin"));
        assertThat(store.load()).isEmpty();
    }

    @Test
    void theSignerRoundTripsSoThePersistedIdentityCanStillSignAndBeVerified(@TempDir Path dir) {
        PersistentIdentityStore store = new PersistentIdentityStore(dir.resolve("identity.bin"));
        NodeIdentity identity = store.loadOrGenerate();

        dev.nodera.core.Bytes message = dev.nodera.core.Bytes.fromHex("deadbeef");
        dev.nodera.core.Bytes signature = identity.sign(message);

        dev.nodera.core.crypto.SignatureService verifier = new dev.nodera.core.crypto.SignatureService();
        assertThat(verifier.verify(identity.publicKeyBytes(), message, signature)).isTrue();
    }
}
