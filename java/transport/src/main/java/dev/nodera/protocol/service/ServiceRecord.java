package dev.nodera.protocol.service;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A service's canonical, Ed25519-signable self-description (type tag
 * {@value TypeTags#SERVICE_RECORD}).
 *
 * <p>The signature a service produces covers exactly this value's {@link #encode(CanonicalWriter)}
 * output, so a peer verifies the <b>same bytes</b> the service signed no matter which message
 * carried the record — a {@code ServiceAnnounce} the tracker verified, a
 * {@code ServiceDirectoryResponse} row, or a {@code ServiceDrainNotice} pushed down a relay control
 * channel. That is what stops a tracker from re-wording a record it is merely carrying: it can hide
 * a service or invent an unreachable one — the powers it already has over worlds — and nothing more.
 *
 * <p>The capacity numbers are <b>self-reported claims</b>. A service can flatter itself, which is
 * why they are only one term of a score whose dominant term is what peers actually measured.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param service                  the service's own node identity. Services have identities; they
 *                                 still hold no world keys and are never consensus authority.
 * @param publicKey                the service's X.509 or raw Ed25519 public key.
 * @param kind                     rendezvous or tracker.
 * @param lifecycle                where the service is in its lifecycle.
 * @param networkId                the network this service serves.
 * @param routes                   dial routes in the service's own preference order.
 * @param version                  the product version of the running binary (root {@code VERSION}).
 * @param activeSessions           registrations (rendezvous) or announced peers (tracker) held now.
 * @param maxSessions              the operator's ceiling for {@code activeSessions}; 0 = unstated.
 * @param activeCircuits           relay circuits bridged now; always 0 for a tracker.
 * @param maxCircuits              the ceiling for {@code activeCircuits}; 0 = unstated.
 * @param rejectedLastWindow       requests refused in the last reporting window, any reason.
 * @param issuedAtEpochMillis      the service's wall-clock at issue time — a freshness bound only.
 * @param expiresAtEpochMillis     when the record self-expires unless refreshed.
 * @param drainDeadlineEpochMillis when a draining service intends to stop; 0 unless
 *                                 {@link ServiceLifecycle#DRAINING}.
 */
public record ServiceRecord(
        NodeId service,
        Bytes publicKey,
        ServiceKind kind,
        ServiceLifecycle lifecycle,
        UUID networkId,
        List<String> routes,
        String version,
        int activeSessions,
        int maxSessions,
        int activeCircuits,
        int maxCircuits,
        int rejectedLastWindow,
        long issuedAtEpochMillis,
        long expiresAtEpochMillis,
        long drainDeadlineEpochMillis) implements Encodable {

    /** Permille denominator for every score component — integers only, never a float. */
    public static final int PERMILLE = 1_000;

    /**
     * The network a service directory is scoped to by default.
     *
     * <p>Deliberately <b>not</b> a per-world id. A rendezvous serves every world its operator points it
     * at, so scoping its record to one world would make the directory a per-world list of the same few
     * hosts repeated — and would hide a relay from the peers of every world it had not been used for
     * yet. The Rust services announce with the same value ({@code NetworkId::new(0, 0)}); a deployment
     * running a private network changes it on both sides together.
     */
    public static final UUID DEFAULT_NETWORK = new UUID(0L, 0L);

    /**
     * Compact constructor: validates and defensive-copies the route list.
     *
     * @throws IllegalArgumentException if a reference argument is null or a number is negative.
     */
    public ServiceRecord {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(version, "version");
        if (activeSessions < 0 || maxSessions < 0 || activeCircuits < 0 || maxCircuits < 0
                || rejectedLastWindow < 0) {
            throw new IllegalArgumentException("counters must be non-negative");
        }
        if (issuedAtEpochMillis < 0 || expiresAtEpochMillis < 0 || drainDeadlineEpochMillis < 0) {
            throw new IllegalArgumentException("timestamps must be non-negative");
        }
        routes = List.copyOf(routes);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.SERVICE_RECORD).writeU16(Encodable.ENCODING_VERSION);
        service.encode(w);
        w.writeBytes(publicKey);
        w.writeU8(kind.ordinal());
        w.writeU8(lifecycle.ordinal());
        w.writeU64(networkId.getMostSignificantBits());
        w.writeU64(networkId.getLeastSignificantBits());
        w.writeList(routes, (ww, route) -> ww.writeString(route));
        w.writeString(version);
        w.writeU32(activeSessions);
        w.writeU32(maxSessions);
        w.writeU32(activeCircuits);
        w.writeU32(maxCircuits);
        w.writeU32(rejectedLastWindow);
        w.writeU64(issuedAtEpochMillis);
        w.writeU64(expiresAtEpochMillis);
        w.writeU64(drainDeadlineEpochMillis);
    }

    /**
     * The exact canonical bytes an Ed25519 signature covers.
     *
     * @return the signed bytes.
     * @Thread-context any thread.
     */
    public Bytes signedBytes() {
        CanonicalWriter w = new CanonicalWriter(256);
        encode(w);
        return w.toBytes();
    }

    /**
     * Free capacity in permille, from the self-reported numbers, taking the tighter of the two
     * ceilings.
     *
     * <p>An unstated ceiling (0) reads as <b>fully free</b> rather than full. Penalising silence
     * would make "publish no limits" the winning move for every operator who dislikes the scoring,
     * which would empty the one field an operator can actually use to shed load.
     *
     * @return 0..1000.
     * @Thread-context any thread.
     */
    public int capacityPermille() {
        return Math.min(headroomPermille(activeSessions, maxSessions),
                headroomPermille(activeCircuits, maxCircuits));
    }

    private static int headroomPermille(int used, int ceiling) {
        if (ceiling == 0) {
            return PERMILLE;
        }
        if (used >= ceiling) {
            return 0;
        }
        return (int) (((long) (ceiling - used) * PERMILLE) / ceiling);
    }

    /**
     * Decode the inverse of {@link #encode(CanonicalWriter)}.
     *
     * @param r the canonical source.
     * @return the record.
     * @throws IllegalStateException if the tag/version is wrong.
     * @Thread-context one reader per decode call.
     */
    public static ServiceRecord decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.SERVICE_RECORD) {
            throw new IllegalStateException("expected ServiceRecord tag, got " + tag);
        }
        int version = r.readU16();
        if (version != Encodable.ENCODING_VERSION) {
            throw new IllegalStateException("unsupported ServiceRecord version " + version);
        }
        NodeId service = NodeId.decode(r);
        Bytes publicKey = r.readBytesValue();
        ServiceKind kind = ServiceKind.decodeOrdinal(r);
        ServiceLifecycle lifecycle = ServiceLifecycle.decodeOrdinal(r);
        UUID networkId = new UUID(r.readU64(), r.readU64());
        List<String> routes = r.readList(CanonicalReader::readString);
        String productVersion = r.readString();
        int activeSessions = r.readU32AsInt();
        int maxSessions = r.readU32AsInt();
        int activeCircuits = r.readU32AsInt();
        int maxCircuits = r.readU32AsInt();
        int rejected = r.readU32AsInt();
        long issuedAt = r.readU64();
        long expiresAt = r.readU64();
        long drainDeadline = r.readU64();
        return new ServiceRecord(service, publicKey, kind, lifecycle, networkId, routes,
                productVersion, activeSessions, maxSessions, activeCircuits, maxCircuits, rejected,
                issuedAt, expiresAt, drainDeadline);
    }
}
