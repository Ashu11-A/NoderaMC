package dev.nodera.core.region;

import dev.nodera.core.NoderaConstants;
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
 * The standing membership of a region's committee at a given {@link RegionEpoch}: one primary
 * plus a list of validators, with an explicit {@code quorumThreshold} (Plan §3.3 / Task 6). A
 * commit requires {@code quorumThreshold} matching votes from committee members.
 *
 * <p>Committee changes are certified by their predecessors (Task 9); the {@code epoch} is
 * monotonic and bumps on every reassignment. The {@code validators} list is defensively copied
 * into an unmodifiable list and rejects {@code null} elements; it is re-sorted by
 * {@code NodeId.value} (UUID bit-order) at encode time for canonical determinism.
 *
 * <p>Thread-context: immutable, any thread.
 */
public record RegionCommittee(
        RegionId region,
        RegionEpoch epoch,
        NodeId primary,
        List<NodeId> validators,
        int quorumThreshold) implements Encodable {

    public RegionCommittee {
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
    public boolean isMember(NodeId node) {
        if (node == null) {
            return false;
        }
        return primary.equals(node) || validators.contains(node);
    }

    /**
     * @return the committee size: {@code 1 (primary) + validators.size()}.
     * @Thread-context any thread.
     */
    public int size() {
        return 1 + validators.size();
    }

    /**
     * Build an MVP committee: 1 primary + 2 validators, quorum
     * {@link NoderaConstants#QUORUM_MVP_REQUIRED} (= 2 of 3). This is the degraded / bootstrap
     * committee shape used before the network reaches peer-era population (Tasks 6–8).
     *
     * @param region        the region.
     * @param epoch         the epoch.
     * @param primary       the primary node.
     * @param twoValidators exactly the two validator nodes (order is irrelevant; canonicalized
     *                      at encode time).
     * @return the MVP committee.
     * @Thread-context any thread.
     */
    public static RegionCommittee mvp(RegionId region, RegionEpoch epoch, NodeId primary, List<NodeId> twoValidators) {
        return new RegionCommittee(region, epoch, primary, twoValidators, NoderaConstants.QUORUM_MVP_REQUIRED);
    }

    /**
     * Canonical encoding: {@code tag(u16) + version(u16) + region(encodable) +
     * epoch(encodable) + primary(encodable) + validators(u32 count + elements, each encodable,
     * SORTED by NodeId.value UUID) + quorumThreshold(u32)}.
     *
     * @param writer the canonical sink.
     * @Thread-context any thread; does not retain the writer.
     */
    @Override
    public void encode(CanonicalWriter writer) {
        writer.writeU16(TypeTags.REGION_COMMITTEE).writeU16(ENCODING_VERSION);
        writer.writeEncodable(region);
        writer.writeEncodable(epoch);
        writer.writeEncodable(primary);
        List<NodeId> sorted = new ArrayList<>(validators);
        sorted.sort(Comparator.comparing(NodeId::value));
        writer.writeList(sorted, (ww, n) -> ww.writeEncodable(n));
        writer.writeU32(Integer.toUnsignedLong(quorumThreshold));
    }

    /**
     * Inverse of {@link #encode(CanonicalWriter)}. The decoded validators list is in canonical
     * (UUID-sorted) order.
     *
     * @param r the canonical source, positioned at the {@code REGION_COMMITTEE} tag.
     * @return the decoded committee.
     * @throws IllegalStateException if the tag or version is invalid.
     * @Thread-context any thread; one reader per decode call (not thread-safe).
     */
    public static RegionCommittee decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.REGION_COMMITTEE) {
            throw new IllegalStateException("expected REGION_COMMITTEE tag, got " + tag);
        }
        int version = r.readU16();
        if (version != ENCODING_VERSION) {
            throw new IllegalStateException("unsupported REGION_COMMITTEE encoding version " + version);
        }
        RegionId region = RegionId.decode(r);
        RegionEpoch epoch = RegionEpoch.decode(r);
        NodeId primary = NodeId.decode(r);
        List<NodeId> validators = r.readList(NodeId::decode);
        int quorum = (int) r.readU32();
        return new RegionCommittee(region, epoch, primary, validators, quorum);
    }
}
