package dev.nodera.core.identity;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Static, self-declared resource/capability profile of a node (Task 9 peer-runtime). Used by the
 * coordinator / {@code CommitteeManager} to nominate primaries and validators: more cores, more
 * memory, lower latency and higher reliability raise a node's candidacy.
 *
 * <p>{@code reliability} is a capability SCALAR (an EMA maintained by the peer), not a
 * hashed-region state float. It is encoded as raw IEEE-754 bits via
 * {@code Double.doubleToLongBits(...)}; this is acceptable and deterministic because the same
 * {@code double} always produces the same bit pattern on every JVM (no floating-point math is
 * performed on it during encoding).
 *
 * <p><b>{@code roles} (Task 20)</b> is an APPENDED field, written after every pre-existing one, in
 * the same append-only spirit the state types document ("later tasks extend this type by appending
 * fields — never reordering/renaming existing ones"). Putting the roles on the wire is what lets a
 * tracker privilege {@code FULL_ARCHIVE}/{@code WORLD_SEEDER} peers as seeders instead of guessing
 * from traffic. The set is encoded as an ASCENDING list of frozen {@link PeerRole} ordinals, so two
 * peers declaring the same roles always encode identical bytes regardless of set iteration order.
 *
 * <p>Thread-context: immutable, any thread.
 */
public record NodeCapabilities(
        int logicalCores,
        long memoryBytes,
        int latencyMs,
        double reliability,
        int maxPrimaryRegions,
        int maxValidatorRegions,
        boolean acceptsWorker,
        Set<PeerRole> roles) implements Encodable {

    /**
     * Compact constructor: defensive-copies {@code roles} into an immutable {@link EnumSet}-backed
     * set so iteration order is the frozen ordinal order.
     *
     * @throws IllegalArgumentException if {@code roles} is null.
     */
    public NodeCapabilities {
        if (roles == null) {
            throw new IllegalArgumentException("roles must not be null");
        }
        roles = roles.isEmpty()
                ? Set.of()
                : java.util.Collections.unmodifiableSet(EnumSet.copyOf(roles));
    }

    /**
     * Backwards-compatible factory for the pre-Task-20 six-field shape (no declared roles).
     *
     * @param logicalCores        logical cores.
     * @param memoryBytes         usable heap/system memory.
     * @param latencyMs           observed latency.
     * @param reliability         reliability EMA.
     * @param maxPrimaryRegions   primary-region cap.
     * @param maxValidatorRegions validator-region cap.
     * @param acceptsWorker       whether the peer accepts worker duty.
     * @return capabilities with an empty role set.
     * @Thread-context any thread.
     */
    public static NodeCapabilities of(
            int logicalCores, long memoryBytes, int latencyMs, double reliability,
            int maxPrimaryRegions, int maxValidatorRegions, boolean acceptsWorker) {
        return new NodeCapabilities(logicalCores, memoryBytes, latencyMs, reliability,
                maxPrimaryRegions, maxValidatorRegions, acceptsWorker, Set.of());
    }

    /**
     * @param role the role to test.
     * @return {@code true} if this peer declares that role.
     * @Thread-context any thread.
     */
    public boolean hasRole(PeerRole role) {
        return roles.contains(role);
    }

    /**
     * @param newRoles the roles to declare.
     * @return a copy of these capabilities with {@code roles} replaced.
     * @Thread-context any thread.
     */
    public NodeCapabilities withRoles(Set<PeerRole> newRoles) {
        return new NodeCapabilities(logicalCores, memoryBytes, latencyMs, reliability,
                maxPrimaryRegions, maxValidatorRegions, acceptsWorker, newRoles);
    }

    /**
     * @param newReliability the reliability EMA to set.
     * @return a copy of these capabilities with {@code reliability} replaced.
     * @Thread-context any thread.
     */
    public NodeCapabilities withReliability(double newReliability) {
        return new NodeCapabilities(logicalCores, memoryBytes, latencyMs, newReliability,
                maxPrimaryRegions, maxValidatorRegions, acceptsWorker, roles);
    }

    /**
     * Sensible defaults for a mid-range peer (used when nothing is configured).
     *
     * <p>Carries an <b>empty</b> role set: a peer declares the roles it actually performs
     * ({@link #withRoles(Set)}) when it knows them, rather than the default guessing on its behalf.
     * The dedicated server adds {@code FULL_ARCHIVE}/{@code BOOTSTRAP}; a client adds
     * {@code PARTIAL_ARCHIVE}; the empty default keeps the golden wire vector minimal.
     *
     * @return a 4-core, 4 GiB, 50 ms, 0.95-reliability node willing to be primary of up to 4
     *         regions, validator of up to 8, and to accept worker duty.
     * @Thread-context any thread.
     */
    public static NodeCapabilities initial() {
        return new NodeCapabilities(4, 4L * 1024 * 1024 * 1024, 50, 0.95, 4, 8, true, Set.of());
    }

    /**
     * Canonical encoding: {@code tag(u16) + version(u16) + cores(u32) + memoryBytes(u64) +
     * latencyMs(u32) + reliability(u64 IEEE-754 bits) + maxPrimaryRegions(u32) +
     * maxValidatorRegions(u32) + acceptsWorker(u8) + roles(u32 count + ascending u8 ordinals)}.
     *
     * @param writer the canonical sink.
     * @Thread-context any thread; does not retain the writer.
     */
    @Override
    public void encode(CanonicalWriter writer) {
        writer.writeU16(TypeTags.NODE_CAPABILITIES).writeU16(ENCODING_VERSION);
        writer.writeU32(Integer.toUnsignedLong(logicalCores));
        writer.writeU64(memoryBytes);
        writer.writeU32(Integer.toUnsignedLong(latencyMs));
        writer.writeU64(Double.doubleToLongBits(reliability));
        writer.writeU32(Integer.toUnsignedLong(maxPrimaryRegions));
        writer.writeU32(Integer.toUnsignedLong(maxValidatorRegions));
        writer.writeU8(acceptsWorker ? 1 : 0);
        // Appended by Task 20. Sorted by frozen ordinal so a set encodes canonically.
        List<PeerRole> ordered = roles.isEmpty()
                ? List.of()
                : List.copyOf(EnumSet.copyOf(roles));
        writer.writeList(ordered, (w, role) -> w.writeU8(role.ordinal()));
    }

    /**
     * Inverse of {@link #encode(CanonicalWriter)}.
     *
     * @param r the canonical source, positioned at the {@code NODE_CAPABILITIES} tag.
     * @return the decoded capabilities.
     * @throws IllegalStateException if the tag or version is invalid.
     * @Thread-context any thread; one reader per decode call (not thread-safe).
     */
    public static NodeCapabilities decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.NODE_CAPABILITIES) {
            throw new IllegalStateException("expected NODE_CAPABILITIES tag, got " + tag);
        }
        int version = r.readU16();
        if (version != ENCODING_VERSION) {
            throw new IllegalStateException("unsupported NODE_CAPABILITIES encoding version " + version);
        }
        int cores = (int) r.readU32();
        long mem = r.readU64();
        int latency = (int) r.readU32();
        double rel = Double.longBitsToDouble(r.readU64());
        int maxPrimary = (int) r.readU32();
        int maxVal = (int) r.readU32();
        boolean accepts = r.readU8() != 0;
        List<PeerRole> decodedRoles = r.readList(rr -> {
            int ord = rr.readU8();
            PeerRole[] all = PeerRole.values();
            if (ord < 0 || ord >= all.length) {
                throw new IllegalStateException("invalid PeerRole ordinal " + ord);
            }
            return all[ord];
        });
        Set<PeerRole> roleSet = decodedRoles.isEmpty() ? Set.of() : EnumSet.copyOf(decodedRoles);
        return new NodeCapabilities(cores, mem, latency, rel, maxPrimary, maxVal, accepts, roleSet);
    }
}
