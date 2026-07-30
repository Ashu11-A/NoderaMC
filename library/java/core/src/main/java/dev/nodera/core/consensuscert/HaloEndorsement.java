package dev.nodera.core.consensuscert;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

import java.util.Objects;

/**
 * One committee member's signature over a halo slice (engine L-2, second half).
 *
 * <p><b>Why a slice needs a signature at all.</b> The halo is execution <em>input</em>: every
 * member of a committee must execute against the identical neighbour edge state or they compute
 * different roots and lose the round without either being wrong. A slice that arrives as bare
 * bytes from whoever happened to send it is therefore not merely untrusted, it is
 * <em>unpinnable</em> — there is nothing a second receiver can compare against. This record is the
 * pin: the source region's committee members each sign the exact bytes of the slice they cut from
 * their own committed snapshot, so a receiver accepts the slice only once a strict majority of
 * that committee has endorsed the <b>same</b> {@code sliceRoot}. Equivocation by a minority
 * therefore cannot reach quorum, and two receivers holding a quorum-backed slice necessarily hold
 * identical columns.
 *
 * <p>The signature covers {@link #signedPortion()} — type tag, body version, signer, source
 * region, epoch, snapshot version, slice root. The anchor fields are what stop an endorsement
 * being replayed onto a different region, a different epoch, or a different version.
 *
 * <p>Wire form: {@code signedPortion()} || {@code [u32 len][signature bytes]}.
 *
 * @param signer    the endorsing member of {@code source}'s committee; not null.
 * @param source    the region the slice was cut from; not null.
 * @param epoch     the source region's lease epoch at the time of the cut; not null.
 * @param version   the source snapshot version the slice was cut at; not null.
 * @param sliceRoot canonical hash of the slice's ordered edge columns; not null.
 * @param signature Ed25519 over {@link #signedPortion()}; not null.
 * @Thread-context immutable, any thread.
 */
public record HaloEndorsement(
        NodeId signer,
        RegionId source,
        RegionEpoch epoch,
        SnapshotVersion version,
        StateRoot sliceRoot,
        Bytes signature
) implements Encodable {

    /** Body version covered by the signature. */
    public static final int HALO_ENDORSEMENT_VERSION = 1;

    /**
     * Compact constructor.
     *
     * @throws NullPointerException if any argument is null.
     */
    public HaloEndorsement {
        Objects.requireNonNull(signer, "signer");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(sliceRoot, "sliceRoot");
        Objects.requireNonNull(signature, "signature");
    }

    /**
     * The canonical bytes the signature covers — a strict prefix of {@link #encode}, excluding the
     * signature by construction.
     *
     * @return the signed bytes.
     * @Thread-context deterministic; any thread.
     */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        writeSignedFields(w);
        return w.toBytes();
    }

    private void writeSignedFields(CanonicalWriter w) {
        w.writeU16(TypeTags.HALO_ENDORSEMENT).writeU16(HALO_ENDORSEMENT_VERSION);
        signer.encode(w);
        source.encode(w);
        epoch.encode(w);
        version.encode(w);
        sliceRoot.encode(w);
    }

    @Override
    public void encode(CanonicalWriter w) {
        writeSignedFields(w);
        w.writeBytes(signature);
    }

    /**
     * Full-frame decode.
     *
     * @param r the reader positioned at the type tag.
     * @return the endorsement.
     * @throws IllegalStateException if the next tag is not {@code HALO_ENDORSEMENT}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static HaloEndorsement decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.HALO_ENDORSEMENT) {
            throw new IllegalStateException("expected HALO_ENDORSEMENT tag, got " + tag);
        }
        r.readVersion(HALO_ENDORSEMENT_VERSION);
        NodeId signer = NodeId.decode(r);
        RegionId source = RegionId.decode(r);
        RegionEpoch epoch = RegionEpoch.decode(r);
        SnapshotVersion version = SnapshotVersion.decode(r);
        StateRoot sliceRoot = StateRoot.decode(r);
        Bytes signature = r.readBytesValue();
        return new HaloEndorsement(signer, source, epoch, version, sliceRoot, signature);
    }
}
