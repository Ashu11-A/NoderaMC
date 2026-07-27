package dev.nodera.headless;

import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.Bytes;
import dev.nodera.storage.PersistedWorldKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The keys that make this peer a world's administrator — and the rule that they are only ever
 * created here, never accepted from anywhere.
 */
final class WorldKeyStoreTest {

    @TempDir
    Path dir;

    private static String worldId(String seed) {
        return new dev.nodera.core.crypto.HashService().sha256(seed.getBytes()).toHex();
    }

    @Test
    @DisplayName("a world is keyed once and keeps that key across restarts")
    void theKeyIsStable() {
        String world = worldId("stable");
        PersistedWorldKey minted = new WorldKeyStore(dir).loadOrGenerate(world);

        // A second store is a second process: the peer must come back administering the same world
        // with the same key, or every peer that learned its public key sees it change hands.
        PersistedWorldKey reloaded = new WorldKeyStore(dir).loadOrGenerate(world);

        assertThat(reloaded.x509Public()).isEqualTo(minted.x509Public());
        assertThat(reloaded.pkcs8Private()).isEqualTo(minted.pkcs8Private());
    }

    @Test
    @DisplayName("a reloaded key still signs verifiably")
    void aReloadedKeyStillSigns() {
        String world = worldId("signing");
        PersistedWorldKey minted = new WorldKeyStore(dir).loadOrGenerate(world);

        PersistedWorldKey reloaded = new WorldKeyStore(dir).load(world).orElseThrow();
        Bytes data = Bytes.unsafeWrap("prove it".getBytes());

        assertThat(new SignatureService().verify(minted.x509Public(), data, reloaded.sign(data)))
                .isTrue();
    }

    @Test
    @DisplayName("a world this node never created is not administered by it")
    void anUnknownWorldIsNotAdministered() {
        WorldKeyStore store = new WorldKeyStore(dir);
        store.loadOrGenerate(worldId("mine"));

        assertThat(store.administers(worldId("someone-elses"))).isFalse();
        assertThat(store.load(worldId("someone-elses"))).isEmpty();
        assertThat(store.administers(worldId("mine"))).isTrue();
    }

    @Test
    @DisplayName("each world gets its own key")
    void keysAreNotSharedBetweenWorlds() {
        WorldKeyStore store = new WorldKeyStore(dir);

        assertThat(store.loadOrGenerate(worldId("a")).x509Public())
                .isNotEqualTo(store.loadOrGenerate(worldId("b")).x509Public());
        assertThat(store.administeredWorlds()).hasSize(2);
    }

    @Test
    @DisplayName("key files are owner-only")
    void keyFilesAreNotWorldReadable() throws Exception {
        String world = worldId("perms");
        new WorldKeyStore(dir).loadOrGenerate(world);

        Path keyFile = dir.resolve(world + ".worldkey");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(keyFile);

        assertThat(permissions).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    @DisplayName("a world id that is not hex never becomes a path")
    void aWorldIdIsNotAFilename() {
        WorldKeyStore store = new WorldKeyStore(dir);

        // The id becomes a filename, so anything that is not hex is refused rather than escaped.
        assertThat(store.load("../../etc/passwd")).isEmpty();
        assertThat(store.load("../../etc/passwd")).isEmpty();
        assertThatThrownBy(() -> store.loadOrGenerate("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.loadOrGenerate(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.administeredWorlds()).isEmpty();
    }

    @Test
    @DisplayName("world ids are case-insensitive, so one world never gets two keys")
    void caseDoesNotMintASecondKey() {
        String world = worldId("case");
        WorldKeyStore store = new WorldKeyStore(dir);

        PersistedWorldKey lower = store.loadOrGenerate(world);
        PersistedWorldKey upper = store.loadOrGenerate(world.toUpperCase(java.util.Locale.ROOT));

        assertThat(upper.x509Public()).isEqualTo(lower.x509Public());
        assertThat(store.administeredWorlds()).hasSize(1);
    }

    @Test
    @DisplayName("an unreadable key file is reported, never silently replaced")
    void aCorruptKeyFileIsNotOverwritten() throws Exception {
        String world = worldId("corrupt");
        Path keyFile = dir.resolve(world + ".worldkey");
        Files.createDirectories(dir);
        Files.write(keyFile, "not a key".getBytes());
        byte[] before = Files.readAllBytes(keyFile);

        WorldKeyStore store = new WorldKeyStore(dir);

        // Minting over it would destroy the only copy of an administrator's authority. The world
        // reads as unadministered until somebody looks at the file.
        assertThat(store.load(world)).isEmpty();
        assertThat(Files.readAllBytes(keyFile)).isEqualTo(before);
    }

    @Test
    @DisplayName("a node with no key directory administers nothing")
    void anEmptyStoreIsNotAnError() {
        WorldKeyStore store = new WorldKeyStore(dir.resolve("never-created"));

        assertThat(store.administeredWorlds()).isEmpty();
        assertThat(store.administers(worldId("x"))).isFalse();
    }
}
