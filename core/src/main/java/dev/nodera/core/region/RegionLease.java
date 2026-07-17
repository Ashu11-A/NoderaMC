package dev.nodera.core.region;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Time-bounded authorization for a {@link NodeId} to act as {@code primary} of a region, backed
 * by a validator set (Plan §3.4 / Task 6). Issued for
 * {@code dev.nodera.core.NoderaConstants#LEASE_LENGTH_TICKS}; renewed
 * {@code dev.nodera.core.NoderaConstants#LEASE_RENEW_TICKS} before expiry. A stale-epoch lease is
 * rejected by the consensus machinery (Invariant 9).
 *
 * <p>The {@code validators} list is defensively copied into an unmodifiable list and rejects
 * {@code null} elements. For canonical determinism the list is re-sorted by {@code NodeId.value}
 * (UUID bit-order: MSB then LSB) at encode time — two leases whose validators differ only in
 * input order encode to IDENTICAL bytes.
 *
 * <p>Thread-context: immutable, any thread.
 */
public record RegionLease(
        RegionId region,
        RegionEpoch epoch,
        NodeId primary,
        List<NodeId> validators,
        long validFromTick,
        long expiresAtTick) implements Encodable {

    public RegionLease {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (epoch == null) {
            throw new IllegalArgumentException("epoch must not be null");
        }
        if (primary == null) {
            throw new IllegalArgumentException("primary must not be null");
        }
        if (validators == null) {
            throw new IllegalArgumentException("validators must not be null");
        }
        List<NodeId> defensive = new ArrayList<>(validators.size());
        for (NodeId v : validators) {
            if (v == null) {
                throw new IllegalArgumentException("validators must not contain null");
            }
            defensive.add(v);
        }
        validators = Collections.unmodifiableList(defensive);
    }

    /**
     * @return {@code true} if {@code node} is the primary or one of the validators.
     * @Thread-context any thread.
     */
    public boolean contains(NodeId node) {
        if (node == null) {
            return false;
        }
        return primary.equals(node) || validators.contains(node);
    }

    /**
     * @return {@code true} if {@code tick} is at or after {@link #expiresAtTick()} (the lease is
     *         no longer valid at that tick).
     * @Thread-context any thread.
     */
    public boolean isExpiredAt(long tick) {
        return tick >= expiresAtTick;
    }

    /**
     * Canonical encoding: {@code tag(u16) + version(u16) + region(encodable) +
     * epoch(encodable) + primary(encodable) + validators(u32 count + elements, each encodable,
     * SORTED by NodeId.value UUID) + validFromTick(u64) + expiresAtTick(u64)}.
     *
     * @param writer the canonical sink.
     * @Thread-context any thread; does not retain the writer.
     */
    @Override
    public void encode(CanonicalWriter writer) {
        writer.writeU16(TypeTags.REGION_LEASE).writeU16(ENCODING_VERSION);
        writer.writeEncodable(region);
        writer.writeEncodable(epoch);
        writer.writeEncodable(primary);
        List<NodeId> sorted = new ArrayList<>(validators);
        sorted.sort(Comparator.comparing(NodeId::value));
        writer.writeList(sorted, (ww, n) -> ww.writeEncodable(n));
        writer.writeU64(validFromTick);
        writer.writeU64(expiresAtTick);
    }

    /**
     * Inverse of {@link #encode(CanonicalWriter)}. The decoded validators list is in the
     * canonical (UUID-sorted) order, which equals the encoded order.
     *
     * @param r the canonical source, positioned at the {@code REGION_LEASE} tag.
     * @return the decoded lease.
     * @throws IllegalStateException if the tag or version is invalid.
     * @Thread-context any thread; one reader per decode call (not thread-safe).
     */
    public static RegionLease decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.REGION_LEASE) {
            throw new IllegalStateException("expected REGION_LEASE tag, got " + tag);
        }
        int version = r.readU16();
        if (version != ENCODING_VERSION) {
            throw new IllegalStateException("unsupported REGION_LEASE encoding version " + version);
        }
        RegionId region = RegionId.decode(r);
        RegionEpoch epoch = RegionEpoch.decode(r);
        NodeId primary = NodeId.decode(r);
        List<NodeId> validators = r.readList(NodeId::decode);
        long validFrom = r.readU64();
        long expiresAt = r.readU64();
        return new RegionLease(region, epoch, primary, validators, validFrom, expiresAt);
    }
}
