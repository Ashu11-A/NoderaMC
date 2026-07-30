package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.StateRoot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Task 30c: the {@link GenesisManifest} of an <b>existing</b> world, extracted from its current
 * content and self-certified by the hosting identity. This relocates the Task 9 "genesis from
 * current world" primitive to the decentralized model — the signer is the host player's node, not a
 * dedicated server; genesis remains the one self-signed trust root (ledger L-20, unchanged), and
 * multi-party genesis stays Task 16.
 *
 * <p><b>Root derivation.</b> The genesis root is a SHA-256 over the canonical encoding of
 * {@code (worldSeed, rulesVersion, registryFingerprint)} plus the per-region content digests in
 * deterministic region order — so the root commits to the actual extracted world content, byte for
 * byte, while remaining reproducible from the same extraction. Region digests are opaque here
 * (the extractor owns their derivation); this type owns ordering, hashing, and certification.
 *
 * <p>Wire form: {@code [u16 CERTIFIED_WORLD_GENESIS][u16 version][GenesisManifest]
 * [u32 regionCount][(RegionId, Bytes digest)...][NodeId author][Bytes authorPublicKey]
 * [Bytes signature]} — the signature covers everything before it ({@link #signedPortion()}).
 *
 * @Thread-context immutable, any thread.
 */
public record CertifiedWorldGenesis(
        GenesisManifest manifest,
        Map<RegionId, Bytes> regionDigests,
        NodeId authorNodeId,
        Bytes authorPublicKey,
        Bytes signature) implements Encodable {

    /** Deterministic region ordering (dimension, then Z, then X) for hashing + encoding. */
    private static final Comparator<RegionId> REGION_ORDER = Comparator
            .comparing((RegionId r) -> r.dimension().toString())
            .thenComparingInt(RegionId::regionZ)
            .thenComparingInt(RegionId::regionX);

    public CertifiedWorldGenesis {
        if (manifest == null || regionDigests == null || authorNodeId == null
                || authorPublicKey == null || signature == null) {
            throw new IllegalArgumentException("CertifiedWorldGenesis fields must not be null");
        }
        TreeMap<RegionId, Bytes> ordered = new TreeMap<>(REGION_ORDER);
        for (Map.Entry<RegionId, Bytes> e : regionDigests.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw new IllegalArgumentException("region digests must not contain null");
            }
            ordered.put(e.getKey(), e.getValue());
        }
        regionDigests = java.util.Collections.unmodifiableMap(ordered);
    }

    /**
     * Extract-and-certify: derive the genesis root from the world parameters and the per-region
     * content digests, then sign the whole record with the hosting identity.
     *
     * @param worldSeed           the world seed.
     * @param rulesVersion        the rule-set version the host validates with.
     * @param registryFingerprint the block-registry fingerprint of that rule set.
     * @param regionDigests       per-region content digests from the extractor (may be empty for a
     *                            world with no delegable regions yet — the root still commits to
     *                            the world parameters).
     * @param host                the hosting identity (the self-signing trust root).
     * @param hashes              the SHA-256 service.
     * @return the signed record.
     * @Thread-context any thread.
     */
    public static CertifiedWorldGenesis certify(
            long worldSeed, int rulesVersion, long registryFingerprint,
            Map<RegionId, Bytes> regionDigests, NodeIdentity host, HashService hashes) {
        if (regionDigests == null || host == null || hashes == null) {
            throw new IllegalArgumentException("regionDigests, host, and hashes must not be null");
        }
        TreeMap<RegionId, Bytes> ordered = new TreeMap<>(REGION_ORDER);
        ordered.putAll(regionDigests);
        CanonicalWriter w = new CanonicalWriter();
        w.writeU64(worldSeed);
        w.writeU32(Integer.toUnsignedLong(rulesVersion));
        w.writeU64(registryFingerprint);
        for (Map.Entry<RegionId, Bytes> e : ordered.entrySet()) {
            e.getKey().encode(w);
            w.writeBytes(e.getValue());
        }
        GenesisManifest manifest = new GenesisManifest(
                worldSeed, rulesVersion, registryFingerprint, StateRoot.of(hashes.sha256(w.toBytes())));
        CertifiedWorldGenesis unsigned = new CertifiedWorldGenesis(
                manifest, ordered, host.nodeId(), host.publicKeyBytes(), Bytes.empty());
        Bytes sig = host.sign(unsigned.signedPortion());
        return new CertifiedWorldGenesis(manifest, ordered, host.nodeId(), host.publicKeyBytes(), sig);
    }

    /** The canonical bytes the signature covers: everything except the signature itself. */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        encodeSigned(w);
        return w.toBytes();
    }

    /** @return whether the author signature verifies against {@link #authorPublicKey}. */
    public boolean verifySignature() {
        return new SignatureService().verify(authorPublicKey, signedPortion(), signature);
    }

    private void encodeSigned(CanonicalWriter w) {
        w.writeU16(TypeTags.CERTIFIED_WORLD_GENESIS).writeU16(ENCODING_VERSION);
        manifest.encode(w);
        List<Map.Entry<RegionId, Bytes>> entries = new ArrayList<>(regionDigests.entrySet());
        w.writeList(entries, (ww, e) -> {
            e.getKey().encode(ww);
            ww.writeBytes(e.getValue());
        });
        authorNodeId.encode(w);
        w.writeBytes(authorPublicKey);
    }

    @Override
    public void encode(CanonicalWriter w) {
        encodeSigned(w);
        w.writeBytes(signature);
    }

    /**
     * Full-frame decode.
     *
     * @throws IllegalStateException if the next tag is not {@code CERTIFIED_WORLD_GENESIS}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static CertifiedWorldGenesis decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.CERTIFIED_WORLD_GENESIS) {
            throw new IllegalStateException("expected CERTIFIED_WORLD_GENESIS tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        GenesisManifest manifest = GenesisManifest.decode(r);
        List<Map.Entry<RegionId, Bytes>> entries = r.readList(rr -> {
            RegionId region = RegionId.decode(rr);
            Bytes digest = rr.readBytesValue();
            return Map.entry(region, digest);
        });
        TreeMap<RegionId, Bytes> digests = new TreeMap<>(REGION_ORDER);
        for (Map.Entry<RegionId, Bytes> e : entries) {
            digests.put(e.getKey(), e.getValue());
        }
        NodeId author = NodeId.decode(r);
        Bytes publicKey = r.readBytesValue();
        Bytes signature = r.readBytesValue();
        return new CertifiedWorldGenesis(manifest, digests, author, publicKey, signature);
    }
}
