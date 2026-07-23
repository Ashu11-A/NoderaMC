package dev.nodera.core.consensuscert;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

/**
 * The server's certificate for a version advance that did NOT go through a committee quorum
 * (Task 11; Task 8 consumes the non-{@code EXTERNAL_MUTATION} reasons). Replicas apply the
 * accompanying delta WITHOUT voting after verifying this certificate — it is the trust anchor for
 * the server-authority lane, so its {@link Bytes serverSignature} covers exactly
 * {@link #signedPortion()} (typeTag + body version + region + baseVersion + resultingVersion +
 * resultingRoot + transitionRoot + reason); the signature itself is never signed. Legacy body
 * version 1 omits {@code transitionRoot}; version 2 binds every delta effect.
 *
 * <p>Wire form: {@code signedPortion()} || {@code [u32 len][signature bytes]}.
 *
 * @Thread-context immutable, any thread.
 */
public record ServerAuthorityCertificate(
        RegionId region,
        SnapshotVersion baseVersion,
        SnapshotVersion resultingVersion,
        StateRoot resultingRoot,
        StateRoot transitionRoot,
        Reason reason,
        Bytes serverSignature,
        int bodyVersion
) implements Encodable {

    /** Version 1 signed state only; version 2 also binds complete delta effects. */
    public static final int CERTIFICATE_ENCODING_VERSION = 2;

    /** Legacy root-only constructor retained for decoding and stored certificates. */
    public ServerAuthorityCertificate(
            RegionId region, SnapshotVersion baseVersion, SnapshotVersion resultingVersion,
            StateRoot resultingRoot, Reason reason, Bytes serverSignature) {
        this(region, baseVersion, resultingVersion, resultingRoot, resultingRoot,
                reason, serverSignature, 1);
    }

    /** Current transition-bound constructor. */
    public ServerAuthorityCertificate(
            RegionId region, SnapshotVersion baseVersion, SnapshotVersion resultingVersion,
            StateRoot resultingRoot, StateRoot transitionRoot,
            Reason reason, Bytes serverSignature) {
        this(region, baseVersion, resultingVersion, resultingRoot, transitionRoot,
                reason, serverSignature, CERTIFICATE_ENCODING_VERSION);
    }

    /**
     * Why the server (not a committee) certified this advance. Encoded as a {@code u8} ordinal —
     * position-stable by Java language guarantee; <b>append only, never reorder</b>.
     */
    public enum Reason {
        /** A foreign write inside a delegated region was converted into a certified delta (Task 11). */
        EXTERNAL_MUTATION,
        /** The region is not assigned to any committee (Task 8 server lane). */
        UNASSIGNED,
        /** The region is non-delegable (Task 8 server lane). */
        NON_DELEGABLE,
        /** A cross-region action executed on the server lane (Task 8). */
        CROSS_REGION,
        /** The committee disputed the root; the server re-executed (Task 8). */
        DISPUTED,
        /** The committee collapsed below quorum (Task 8). */
        COMMITTEE_COLLAPSED,
        /** A spot-check re-execution produced this authoritative root (Task 7/8). */
        SPOT_CHECK
    }

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null or the versions do not advance.
     */
    public ServerAuthorityCertificate {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (baseVersion == null) {
            throw new IllegalArgumentException("baseVersion must not be null");
        }
        if (resultingVersion == null) {
            throw new IllegalArgumentException("resultingVersion must not be null");
        }
        if (resultingRoot == null) {
            throw new IllegalArgumentException("resultingRoot must not be null");
        }
        if (transitionRoot == null) {
            throw new IllegalArgumentException("transitionRoot must not be null");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        if (serverSignature == null) {
            throw new IllegalArgumentException("serverSignature must not be null");
        }
        if (resultingVersion.value() <= baseVersion.value()) {
            throw new IllegalArgumentException(
                    "resultingVersion " + resultingVersion.value()
                            + " must advance past baseVersion " + baseVersion.value());
        }
        if (bodyVersion < 1 || bodyVersion > CERTIFICATE_ENCODING_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported server-authority body version " + bodyVersion);
        }
        if (bodyVersion == 1 && !transitionRoot.equals(resultingRoot)) {
            throw new IllegalArgumentException("version 1 certificate cannot carry a transition root");
        }
    }

    /**
     * The canonical bytes the server signature covers: everything except the signature. A strict
     * prefix of {@link #encode(CanonicalWriter)} by construction.
     *
     * @Thread-context deterministic; safe from any thread.
     */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        writeSignedFields(w);
        return w.toBytes();
    }

    private void writeSignedFields(CanonicalWriter w) {
        w.writeU16(TypeTags.SERVER_AUTH_CERT).writeU16(bodyVersion);
        region.encode(w);
        baseVersion.encode(w);
        resultingVersion.encode(w);
        resultingRoot.encode(w);
        if (bodyVersion >= 2) {
            transitionRoot.encode(w);
        }
        w.writeU8(reason.ordinal());
    }

    @Override
    public void encode(CanonicalWriter w) {
        writeSignedFields(w);
        w.writeBytes(serverSignature);
    }

    /**
     * Full-frame decode (signed fields + signature).
     *
     * @throws IllegalStateException if the next tag is not {@code SERVER_AUTH_CERT} or the reason
     *         ordinal is out of range.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static ServerAuthorityCertificate decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.SERVER_AUTH_CERT) {
            throw new IllegalStateException("expected SERVER_AUTH_CERT tag, got " + tag);
        }
        int bodyVersion = r.readU16();
        if (bodyVersion < 1 || bodyVersion > CERTIFICATE_ENCODING_VERSION) {
            throw new IllegalStateException(
                    "unsupported SERVER_AUTH_CERT encoding version " + bodyVersion);
        }
        RegionId region = RegionId.decode(r);
        SnapshotVersion baseVersion = SnapshotVersion.decode(r);
        SnapshotVersion resultingVersion = SnapshotVersion.decode(r);
        StateRoot resultingRoot = StateRoot.decode(r);
        StateRoot transitionRoot = bodyVersion >= 2 ? StateRoot.decode(r) : resultingRoot;
        int ord = r.readU8();
        Reason[] reasons = Reason.values();
        if (ord >= reasons.length) {
            throw new IllegalStateException("ServerAuthorityCertificate reason ordinal out of range: " + ord);
        }
        Bytes signature = r.readBytesValue();
        return new ServerAuthorityCertificate(
                region, baseVersion, resultingVersion, resultingRoot, transitionRoot,
                reasons[ord], signature, bodyVersion);
    }
}
