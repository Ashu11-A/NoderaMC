package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class CertifiedWorldGenesisTest {

    private static final HashService HASHES = new HashService();
    private static final NodeIdentity HOST = NodeIdentity.generate();

    private static final RegionId R00 = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final RegionId R10 = new RegionId(DimensionKey.overworld(), 1, 0);

    private static Map<RegionId, Bytes> digests() {
        Map<RegionId, Bytes> d = new LinkedHashMap<>();
        d.put(R10, HASHES.sha256("region-1-0".getBytes()));
        d.put(R00, HASHES.sha256("region-0-0".getBytes()));
        return d;
    }

    @Test
    void certifySignsAndVerifies() {
        CertifiedWorldGenesis genesis = CertifiedWorldGenesis.certify(
                42L, 2, 99L, digests(), HOST, HASHES);

        assertThat(genesis.verifySignature()).isTrue();
        assertThat(genesis.authorNodeId()).isEqualTo(HOST.nodeId());
        assertThat(genesis.manifest().worldSeed()).isEqualTo(42L);
        assertThat(genesis.manifest().rulesVersion()).isEqualTo(2);
        assertThat(genesis.manifest().registryFingerprint()).isEqualTo(99L);
    }

    @Test
    void rootIsDeterministicAndInputOrderIndependent() {
        Map<RegionId, Bytes> reversed = new LinkedHashMap<>();
        digests().entrySet().stream()
                .sorted((a, b) -> -1)
                .forEach(e -> reversed.put(e.getKey(), e.getValue()));

        CertifiedWorldGenesis a = CertifiedWorldGenesis.certify(42L, 2, 99L, digests(), HOST, HASHES);
        CertifiedWorldGenesis b = CertifiedWorldGenesis.certify(42L, 2, 99L, reversed, HOST, HASHES);

        assertThat(a.manifest()).isEqualTo(b.manifest());
        assertThat(a.signedPortion()).isEqualTo(b.signedPortion());
    }

    @Test
    void rootCommitsToRegionContent() {
        Map<RegionId, Bytes> changed = new LinkedHashMap<>(digests());
        changed.put(R00, HASHES.sha256("tampered".getBytes()));

        assertThat(CertifiedWorldGenesis.certify(42L, 2, 99L, digests(), HOST, HASHES).manifest())
                .isNotEqualTo(CertifiedWorldGenesis.certify(42L, 2, 99L, changed, HOST, HASHES).manifest());
    }

    @Test
    void emptyRegionSetStillCommitsToWorldParameters() {
        CertifiedWorldGenesis a = CertifiedWorldGenesis.certify(1L, 2, 99L, Map.of(), HOST, HASHES);
        CertifiedWorldGenesis b = CertifiedWorldGenesis.certify(2L, 2, 99L, Map.of(), HOST, HASHES);

        assertThat(a.verifySignature()).isTrue();
        assertThat(a.manifest().genesisRoot()).isNotEqualTo(b.manifest().genesisRoot());
    }

    @Test
    void encodeDecodeRoundTripsAndStillVerifies() {
        CertifiedWorldGenesis genesis = CertifiedWorldGenesis.certify(
                42L, 2, 99L, digests(), HOST, HASHES);
        CanonicalWriter w = new CanonicalWriter();
        genesis.encode(w);

        CertifiedWorldGenesis decoded = CertifiedWorldGenesis.decode(new CanonicalReader(w.toBytes()));

        assertThat(decoded).isEqualTo(genesis);
        assertThat(decoded.verifySignature()).isTrue();
    }

    @Test
    void tamperedSignatureFailsVerification() {
        CertifiedWorldGenesis genesis = CertifiedWorldGenesis.certify(
                42L, 2, 99L, digests(), HOST, HASHES);
        byte[] raw = genesis.signature().toArray();
        raw[0] ^= 0x01;

        CertifiedWorldGenesis forged = new CertifiedWorldGenesis(
                genesis.manifest(), genesis.regionDigests(), genesis.authorNodeId(),
                genesis.authorPublicKey(), Bytes.unsafeWrap(raw));

        assertThat(forged.verifySignature()).isFalse();
    }

    @Test
    void foreignKeyCannotClaimAuthorship() {
        CertifiedWorldGenesis genesis = CertifiedWorldGenesis.certify(
                42L, 2, 99L, digests(), HOST, HASHES);
        NodeIdentity other = NodeIdentity.generate();

        CertifiedWorldGenesis reclaimed = new CertifiedWorldGenesis(
                genesis.manifest(), genesis.regionDigests(), other.nodeId(),
                other.publicKeyBytes(), genesis.signature());

        assertThat(reclaimed.verifySignature()).isFalse();
    }

    @Test
    void nullArgumentsRejected() {
        assertThatThrownBy(() -> CertifiedWorldGenesis.certify(1L, 2, 99L, null, HOST, HASHES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CertifiedWorldGenesis.certify(1L, 2, 99L, Map.of(), null, HASHES))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
