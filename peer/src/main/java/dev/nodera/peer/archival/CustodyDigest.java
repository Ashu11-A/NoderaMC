package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalEncoder;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.storage.RegionOrder;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A checkable summary of what a node holds (server task 3, L-62): a Merkle root over
 * {@code (RegionId → head SnapshotVersion)} for every region the node claims, with the leaves in
 * canonical {@link RegionOrder#BY_DIMENSION_XZ} order.
 *
 * <p>The ordering is the whole point. Two honest nodes holding the same state produce the
 * <b>same root</b> whatever order their stores happened to enumerate regions in, so a root can be
 * advertised and compared; and any peer may ask for one region's head plus its inclusion proof and
 * verify it against the advertised root without downloading the world. That is what turns
 * {@code custody: FULL} from a claim into something {@link CustodyAudit} can falsify.
 *
 * <p>Hash domains are separated so a leaf can never be reinterpreted as an interior node: a leaf
 * is {@code sha256(0x00 ‖ canonical(RegionId) ‖ canonical(SnapshotVersion))} and an interior node
 * is {@code sha256(0x01 ‖ left ‖ right)}. An odd node at any level is <b>promoted unchanged</b>
 * rather than paired with itself, which keeps the tree free of the duplicate-leaf ambiguity that
 * self-pairing introduces. The empty digest is the distinct constant {@code sha256(0x02)}, so
 * "I hold nothing" is a statement rather than an absent value.
 *
 * <p>Thread-context: immutable and safe from any thread once built; construction is confined to
 * the calling thread.
 */
public final class CustodyDigest {

    private static final byte LEAF_DOMAIN = 0x00;
    private static final byte NODE_DOMAIN = 0x01;
    private static final byte EMPTY_DOMAIN = 0x02;

    private final List<RegionId> regions;
    private final Map<RegionId, SnapshotVersion> heads;
    private final List<Bytes> leaves;
    private final Bytes root;

    private CustodyDigest(
            List<RegionId> regions,
            Map<RegionId, SnapshotVersion> heads,
            List<Bytes> leaves,
            Bytes root) {
        this.regions = regions;
        this.heads = heads;
        this.leaves = leaves;
        this.root = root;
    }

    /**
     * Build the digest of a node's per-region heads.
     *
     * @param heads  the head version of every region the node claims to hold; iteration order is
     *               irrelevant because the leaves are sorted before hashing.
     * @param hasher the consensus hash function.
     * @return the digest.
     * @throws IllegalArgumentException if an argument or any entry is null.
     * @Thread-context any thread.
     */
    public static CustodyDigest of(Map<RegionId, SnapshotVersion> heads, HashService hasher) {
        Objects.requireNonNull(heads, "heads");
        Objects.requireNonNull(hasher, "hasher");
        Map<RegionId, SnapshotVersion> sorted = new TreeMap<>(RegionOrder.BY_DIMENSION_XZ);
        heads.forEach((region, head) -> sorted.put(
                Objects.requireNonNull(region, "region"), Objects.requireNonNull(head, "head")));

        List<RegionId> order = List.copyOf(sorted.keySet());
        List<Bytes> leaves = new ArrayList<>(order.size());
        for (RegionId region : order) {
            leaves.add(leafHash(region, sorted.get(region), hasher));
        }
        return new CustodyDigest(order, Map.copyOf(sorted), List.copyOf(leaves), rootOf(leaves, hasher));
    }

    /** @return the advertised Merkle root: 32 bytes, and never empty even for an empty world. */
    public Bytes root() {
        return root;
    }

    /** @return how many regions this digest covers. */
    public int regionCount() {
        return regions.size();
    }

    /** @return every covered region, in canonical order; immutable. */
    public List<RegionId> regions() {
        return regions;
    }

    /** @return the head this digest commits to for {@code region}, if it covers it. */
    public Optional<SnapshotVersion> head(RegionId region) {
        return Optional.ofNullable(heads.get(region));
    }

    /**
     * Produce the inclusion proof for one region — the answer a node gives when it is spot-checked.
     *
     * @param region the sampled region.
     * @return the proof, or empty when this digest does not cover the region (which, for a node
     *         claiming {@code FULL}, is itself the audit's answer).
     * @Thread-context any thread.
     */
    public Optional<Proof> proofFor(RegionId region) {
        int index = regions.indexOf(Objects.requireNonNull(region, "region"));
        if (index < 0) {
            return Optional.empty();
        }
        List<Step> path = new ArrayList<>();
        List<Bytes> level = leaves;
        int position = index;
        while (level.size() > 1) {
            int sibling = (position % 2 == 0) ? position + 1 : position - 1;
            if (sibling < level.size()) {
                path.add(new Step(level.get(sibling), position % 2 == 0));
            }
            level = parentLevel(level, HASHER.get());
            position /= 2;
        }
        return Optional.of(new Proof(region, heads.get(region), List.copyOf(path)));
    }

    /**
     * Verify a proof against an advertised root — the check the auditor performs, using only the
     * root it was told and the answer it was given.
     *
     * @param advertisedRoot the root the audited node advertised.
     * @param proof          its answer for the sampled region.
     * @param hasher         the consensus hash function.
     * @return true when the proof reconstructs exactly {@code advertisedRoot}.
     * @throws IllegalArgumentException if an argument is null.
     * @Thread-context any thread.
     */
    public static boolean verify(Bytes advertisedRoot, Proof proof, HashService hasher) {
        Objects.requireNonNull(advertisedRoot, "advertisedRoot");
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(hasher, "hasher");
        Bytes running = leafHash(proof.region(), proof.head(), hasher);
        for (Step step : proof.path()) {
            running = step.onLeft()
                    ? nodeHash(running, step.sibling(), hasher)
                    : nodeHash(step.sibling(), running, hasher);
        }
        return advertisedRoot.equals(running);
    }

    /** One sibling on the path from a leaf to the root. */
    public record Step(Bytes sibling, boolean onLeft) {

        /**
         * @param sibling the sibling hash at this level.
         * @param onLeft  true when the value being proven sits on the LEFT of this sibling.
         * @throws IllegalArgumentException if {@code sibling} is null.
         */
        public Step {
            Objects.requireNonNull(sibling, "sibling");
        }
    }

    /** A node's answer to a spot-check: the head it claims for a region, and why to believe it. */
    public record Proof(RegionId region, SnapshotVersion head, List<Step> path) {

        /**
         * @param region the sampled region.
         * @param head   the head version claimed for it.
         * @param path   the sibling hashes from leaf to root; empty for a single-region world.
         * @throws IllegalArgumentException if an argument is null.
         */
        public Proof {
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(head, "head");
            path = List.copyOf(Objects.requireNonNull(path, "path"));
        }
    }

    // --- internals -------------------------------------------------------------------------

    /**
     * Hashing is thread-confined inside {@link HashService}; a digest built on one thread may be
     * proven on another, so the tree walk borrows a per-thread service rather than retaining the
     * builder's.
     */
    private static final ThreadLocal<HashService> HASHER = ThreadLocal.withInitial(HashService::new);

    private static Bytes rootOf(List<Bytes> leaves, HashService hasher) {
        if (leaves.isEmpty()) {
            return hasher.sha256(new byte[] {EMPTY_DOMAIN});
        }
        List<Bytes> level = leaves;
        while (level.size() > 1) {
            level = parentLevel(level, hasher);
        }
        return level.get(0);
    }

    private static List<Bytes> parentLevel(List<Bytes> level, HashService hasher) {
        List<Bytes> parents = new ArrayList<>((level.size() + 1) / 2);
        for (int i = 0; i < level.size(); i += 2) {
            // An odd node is promoted unchanged rather than paired with itself.
            parents.add(i + 1 < level.size()
                    ? nodeHash(level.get(i), level.get(i + 1), hasher)
                    : level.get(i));
        }
        return parents;
    }

    private static Bytes leafHash(RegionId region, SnapshotVersion head, HashService hasher) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(LEAF_DOMAIN);
        writeAll(out, CanonicalEncoder.encode(region));
        writeAll(out, CanonicalEncoder.encode(head));
        return hasher.sha256(out.toByteArray());
    }

    private static Bytes nodeHash(Bytes left, Bytes right, HashService hasher) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(NODE_DOMAIN);
        writeAll(out, left);
        writeAll(out, right);
        return hasher.sha256(out.toByteArray());
    }

    private static void writeAll(ByteArrayOutputStream out, Bytes bytes) {
        byte[] raw = bytes.toArray();
        out.write(raw, 0, raw.length);
    }
}
