package dev.nodera.storage;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.StateRoot;

/**
 * The world's genesis manifest (Plan §6 Phase 5 / Task 9): the fixed parameters every peer agrees on
 * before any event, plus the genesis root. A new peer's sync begins here — bootstrap → genesis
 * manifest → checkpoint certificates → snapshots → certified events.
 *
 * <p>Wire form: {@code [u16 GENESIS_MANIFEST][u16 ENCODING_VERSION][u64 worldSeed]
 * [u32 rulesVersion][u64 registryFingerprint][StateRoot genesisRoot]} — {@code worldSeed} and
 * {@code registryFingerprint} are two's-complement i64 in the u64 slot.
 *
 * @param worldSeed           the world seed.
 * @param rulesVersion        the rule-set version.
 * @param registryFingerprint the block-registry fingerprint.
 * @param genesisRoot         the root of the empty/initial world.
 * @Thread-context immutable, any thread.
 */
public record GenesisManifest(long worldSeed, int rulesVersion, long registryFingerprint, StateRoot genesisRoot)
        implements Encodable {

    public GenesisManifest {
        if (genesisRoot == null) {
            throw new IllegalArgumentException("genesisRoot must not be null");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.GENESIS_MANIFEST).writeU16(ENCODING_VERSION);
        w.writeU64(worldSeed);
        w.writeU32(Integer.toUnsignedLong(rulesVersion));
        w.writeU64(registryFingerprint);
        genesisRoot.encode(w);
    }

    /**
     * Full-frame decode.
     *
     * @throws IllegalStateException if the next tag is not {@code GENESIS_MANIFEST}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static GenesisManifest decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.GENESIS_MANIFEST) {
            throw new IllegalStateException("expected GENESIS_MANIFEST tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        long worldSeed = r.readU64();
        int rulesVersion = (int) r.readU32();
        long registryFingerprint = r.readU64();
        StateRoot genesisRoot = StateRoot.decode(r);
        return new GenesisManifest(worldSeed, rulesVersion, registryFingerprint, genesisRoot);
    }
}
