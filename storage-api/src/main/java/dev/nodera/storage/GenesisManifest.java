package dev.nodera.storage;

import dev.nodera.core.state.StateRoot;

/**
 * The world's genesis manifest (Plan §6 Phase 5 / Task 9): the fixed parameters every peer agrees on
 * before any event, plus the genesis root. A new peer's sync begins here — bootstrap → genesis
 * manifest → checkpoint certificates → snapshots → certified events.
 *
 * @param worldSeed           the world seed.
 * @param rulesVersion        the rule-set version.
 * @param registryFingerprint the block-registry fingerprint.
 * @param genesisRoot         the root of the empty/initial world.
 * @Thread-context immutable, any thread.
 */
public record GenesisManifest(long worldSeed, int rulesVersion, long registryFingerprint, StateRoot genesisRoot) {
    public GenesisManifest {
        if (genesisRoot == null) {
            throw new IllegalArgumentException("genesisRoot must not be null");
        }
    }
}
